package com.videoroom.data.server

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Spawns the bundled `videoroom-core` daemon when the client can't find a
 * running one. The launcher locates the binary, starts it with the
 * appropriate `--db-path` / `--port=0`, and parses the
 * `VIDEOROOM_LISTENING_ON=127.0.0.1:N` line from stdout to discover which
 * port the kernel actually assigned.
 *
 * The launcher does NOT manage the catalog directly — the spawned process
 * owns its own catalog state, swapped at runtime via the `OpenCatalog` RPC.
 * Restarting the process is wasteful when the client wants to switch catalogs;
 * we use it as a fallback only when no daemon is running yet.
 */
class ServerLauncher {
    private val logger = LoggerFactory.getLogger(ServerLauncher::class.java)

    /** Where the daemon ended up listening (host + port). */
    data class Listening(val host: String, val port: Int)

    @Volatile
    private var process: Process? = null

    /**
     * Quick liveness check on [host]:[port] using a TCP connect. Used by the
     * client at startup before deciding whether to spawn its own daemon.
     */
    fun isReachable(host: String, port: Int, timeoutMs: Int = 250): Boolean {
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolve the path of the daemon binary. Lookup order:
     *  1. `VIDEOROOM_CORE_BIN` environment variable (full path to executable).
     *  2. Sibling-to-jar location: `<jar dir>/resources/videoroom-core[.exe]`.
     *  3. Cargo dev builds: `<project root>/core/target/release/videoroom-core`
     *     then `…/target/debug/videoroom-core`.
     *  4. `PATH` lookup (Windows: `where`, Unix: `which`).
     *
     * Returns `null` if nothing was found — the caller should surface a clear
     * error to the user.
     */
    fun locateBinary(): File? {
        // 1. Env override
        System.getenv("VIDEOROOM_CORE_BIN")?.takeIf { it.isNotBlank() }?.let { p ->
            val f = File(p)
            if (f.isFile && f.canExecute()) return f
        }

        val binaryName = if (isWindows()) "videoroom-core.exe" else "videoroom-core"

        // 2. Bundled alongside the jar
        try {
            val jarLocation = ServerLauncher::class.java
                .protectionDomain.codeSource?.location?.toURI()
            if (jarLocation != null) {
                val jarDir = File(jarLocation).parentFile
                val candidates = listOf(
                    File(jarDir, binaryName),
                    File(jarDir, "resources/$binaryName"),
                    File(jarDir, "../resources/$binaryName"),
                )
                candidates.firstOrNull { it.isFile && it.canExecute() }?.let { return it }
            }
        } catch (_: Exception) { /* ignore */ }

        // 3. Cargo dev builds. Walk up to a parent that contains `core/`.
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var probe: File? = cwd
        repeat(5) {
            probe?.let { dir ->
                val core = File(dir, "core")
                if (core.isDirectory) {
                    listOf(
                        File(core, "target/release/$binaryName"),
                        File(core, "target/debug/$binaryName")
                    ).firstOrNull { it.isFile && it.canExecute() }?.let { return it }
                }
                probe = dir.parentFile
            }
        }

        // 4. PATH lookup
        try {
            val which = if (isWindows()) "where" else "which"
            val p = ProcessBuilder(which, binaryName).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(2, TimeUnit.SECONDS)
            if (p.exitValue() == 0 && out.isNotBlank()) {
                val first = out.lineSequence().firstOrNull()?.trim()
                if (!first.isNullOrBlank()) {
                    val f = File(first)
                    if (f.isFile && f.canExecute()) return f
                }
            }
        } catch (_: Exception) { /* ignore */ }

        return null
    }

    /**
     * Launch the daemon. Returns the host+port it ended up listening on, or
     * `null` if the binary couldn't be located / failed to start.
     *
     * If [preferredPort] is non-null, the daemon tries to bind that port
     * first and falls back to an OS-assigned one if it's busy. If null, the
     * kernel always picks.
     */
    fun launch(preferredPort: Int? = null, dbPath: String? = null): Listening? {
        val bin = locateBinary() ?: run {
            logger.error("videoroom-core binary not found")
            return null
        }
        val cmd = mutableListOf(bin.absolutePath, "--port", (preferredPort ?: 0).toString())
        if (dbPath.isNullOrBlank()) {
            cmd += "--no-catalog"
        } else {
            cmd += "--db-path"
            cmd += dbPath
        }
        logger.info("Spawning daemon: $cmd")
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            logger.error("Failed to spawn $bin: ${e.message}", e)
            return null
        }
        process = proc

        // Read stdout line-by-line in a background thread; we block here for
        // up to ~10 seconds waiting for the listening line. Anything past
        // that gets forwarded to our logger so the user sees server output
        // in their terminal.
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val sentinel = "VIDEOROOM_LISTENING_ON="
        val deadline = System.currentTimeMillis() + 10_000
        var listening: Listening? = null
        while (System.currentTimeMillis() < deadline && proc.isAlive && listening == null) {
            val line = try { reader.readLine() } catch (_: Exception) { null } ?: break
            logger.info("[core] $line")
            val idx = line.indexOf(sentinel)
            if (idx >= 0) {
                val hostPort = line.substring(idx + sentinel.length).trim()
                val parts = hostPort.split(":")
                if (parts.size == 2) {
                    val port = parts[1].toIntOrNull()
                    if (port != null && port > 0) {
                        listening = Listening(parts[0], port)
                    }
                }
            }
        }

        if (listening == null) {
            logger.error("Daemon never printed listening line; killing it")
            try { proc.destroy() } catch (_: Exception) {}
            return null
        }

        // Detach a thread to keep draining stdout so the pipe doesn't fill up
        // and stall the daemon.
        Thread({
            try {
                var line: String? = reader.readLine()
                while (line != null) {
                    logger.info("[core] $line")
                    line = reader.readLine()
                }
            } catch (_: Exception) { /* process exited */ }
        }, "videoroom-core-stdout").apply { isDaemon = true }.start()

        return listening
    }

    /** Best-effort shutdown of any daemon this launcher started. */
    fun shutdown() {
        val p = process ?: return
        process = null
        try {
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        } catch (_: Exception) { /* ignore */ }
    }

    private fun isWindows(): Boolean =
        (System.getProperty("os.name") ?: "").lowercase().contains("win")
}
