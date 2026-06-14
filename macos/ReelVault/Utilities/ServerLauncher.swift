// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import ReelVaultKit
import Network

/// Spawns the bundled `reelvault-core` daemon when the client can't find a
/// running one. The launcher locates the binary, starts it with the
/// appropriate `--port=N`, and parses the
/// `REELVAULT_LISTENING_ON=127.0.0.1:N` line from stdout so the client knows
/// where to connect.
///
/// Switching catalogs at runtime is done via the daemon's `OpenCatalog` RPC,
/// not by re-spawning — the launcher's job is just to make sure a daemon
/// process exists.
@MainActor
final class ServerLauncher {
    static let shared = ServerLauncher()

    /// Where the daemon ended up listening.
    struct Listening: Equatable {
        let host: String
        let port: Int
    }

    private var process: Process?

    private init() {}

    /// Quick TCP-connect liveness check. Used at startup to decide whether
    /// we need to spawn our own daemon.
    func isReachable(host: String, port: Int, timeoutMs: Int = 250) -> Bool {
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(port).bigEndian
        inet_pton(AF_INET, host, &addr.sin_addr)

        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return false }
        defer { close(fd) }

        // Set socket to non-blocking so we can enforce a short timeout.
        let flags = fcntl(fd, F_GETFL, 0)
        _ = fcntl(fd, F_SETFL, flags | O_NONBLOCK)

        let rc = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                connect(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if rc == 0 { return true } // immediate success
        if errno != EINPROGRESS { return false }

        var wfds = fd_set()
        fdZero(set: &wfds)
        fdSet(fd: fd, set: &wfds)
        var tv = timeval(tv_sec: timeMs.sec(timeoutMs), tv_usec: timeMs.usec(timeoutMs))
        let selRc = select(fd + 1, nil, &wfds, nil, &tv)
        guard selRc > 0 else { return false }

        var err: Int32 = 0
        var len = socklen_t(MemoryLayout<Int32>.size)
        getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len)
        return err == 0
    }

    /// Locate the daemon binary. Lookup order:
    ///   1. `REELVAULT_CORE_BIN` env var.
    ///   2. Inside the app bundle: `Resources/reelvault-core`.
    ///   3. Project-root dev builds (search up from cwd for a `core/target` dir).
    ///   4. `/usr/local/bin/reelvault-core`, `~/.cargo/bin/reelvault-core`.
    /// Returns `nil` if nothing was found — caller should surface a clear error.
    func locateBinary() -> URL? {
        let fm = FileManager.default

        if let env = ProcessInfo.processInfo.environment["REELVAULT_CORE_BIN"], !env.isEmpty {
            let url = URL(fileURLWithPath: env)
            if fm.isExecutableFile(atPath: url.path) { return url }
        }

        // App bundle resources
        if let resURL = Bundle.main.resourceURL?
            .appendingPathComponent("reelvault-core"),
           fm.isExecutableFile(atPath: resURL.path) {
            return resURL
        }

        // Dev tree: walk up from CWD looking for a sibling `core/` directory.
        var dir = URL(fileURLWithPath: fm.currentDirectoryPath)
        for _ in 0..<6 {
            let release = dir.appendingPathComponent("core/target/release/reelvault-core")
            if fm.isExecutableFile(atPath: release.path) { return release }
            let debug = dir.appendingPathComponent("core/target/debug/reelvault-core")
            if fm.isExecutableFile(atPath: debug.path) { return debug }
            dir.deleteLastPathComponent()
        }

        // Common fallback installs.
        for path in [
            "/usr/local/bin/reelvault-core",
            "/opt/homebrew/bin/reelvault-core",
            "\(NSHomeDirectory())/.cargo/bin/reelvault-core",
        ] {
            if fm.isExecutableFile(atPath: path) {
                return URL(fileURLWithPath: path)
            }
        }
        return nil
    }

    /// Launch the daemon and return the host+port it ended up listening on,
    /// or `nil` if we couldn't find / start the binary or it didn't print a
    /// listening line within ~10 seconds.
    func launch(preferredPort: Int = 0, dbPath: String? = nil) async -> Listening? {
        guard let bin = locateBinary() else {
            NSLog("ServerLauncher: reelvault-core binary not found")
            return nil
        }

        // Tear down any previous spawn.
        shutdown()

        let proc = Process()
        proc.executableURL = bin
        var args = ["--port", String(preferredPort)]
        if let db = dbPath, !db.isEmpty {
            args += ["--db-path", db]
        } else {
            args += ["--no-catalog"]
        }
        proc.arguments = args

        let stdoutPipe = Pipe()
        let stderrPipe = Pipe()
        proc.standardOutput = stdoutPipe
        proc.standardError = stderrPipe

        do {
            try proc.run()
        } catch {
            NSLog("ServerLauncher: failed to spawn \(bin.path): \(error)")
            return nil
        }
        self.process = proc
        NSLog("ServerLauncher: spawned \(bin.path) \(args.joined(separator: " "))")

        // Wait up to 10 seconds for the listening line on stdout.
        let sentinel = "REELVAULT_LISTENING_ON="
        let deadline = Date().addingTimeInterval(10)
        let handle = stdoutPipe.fileHandleForReading
        var buffer = Data()

        while Date() < deadline {
            if !proc.isRunning { break }
            // Wait briefly then drain any available bytes.
            try? await Task.sleep(nanoseconds: 100_000_000) // 100 ms
            let chunk = handle.availableData
            if chunk.isEmpty { continue }
            buffer.append(chunk)
            if let s = String(data: buffer, encoding: .utf8) {
                for line in s.split(separator: "\n") {
                    NSLog("[core] \(line)")
                    if let idx = line.range(of: sentinel) {
                        let hostPort = line[idx.upperBound...].trimmingCharacters(in: .whitespaces)
                        let parts = hostPort.split(separator: ":")
                        if parts.count == 2, let p = Int(parts[1]) {
                            // Detach a task to keep reading stdout so the pipe
                            // doesn't back up and stall the daemon.
                            spawnPipeDrain(handle: handle, label: "stdout")
                            spawnPipeDrain(handle: stderrPipe.fileHandleForReading, label: "stderr")
                            return Listening(host: String(parts[0]), port: p)
                        }
                    }
                }
            }
        }

        NSLog("ServerLauncher: daemon never printed listening line; killing it")
        proc.terminate()
        self.process = nil
        return nil
    }

    /// Best-effort shutdown of any daemon this launcher started.
    func shutdown() {
        guard let p = process else { return }
        process = nil
        p.terminate()
        // Don't wait synchronously — the on-quit teardown can race with the
        // process exiting naturally.
    }

    /// Fire-and-forget pipe drain so stdout/stderr don't fill up.
    private func spawnPipeDrain(handle: FileHandle, label: String) {
        DispatchQueue.global(qos: .background).async {
            while true {
                let data = handle.availableData
                if data.isEmpty { break }
                if let s = String(data: data, encoding: .utf8) {
                    for line in s.split(separator: "\n") {
                        NSLog("[core/\(label)] \(line)")
                    }
                }
            }
        }
    }
}

// MARK: - fd_set helpers (Swift can't index sockaddr's fd_set bitmask directly)

private func fdZero(set: inout fd_set) {
    set = fd_set()
}

private func fdSet(fd: Int32, set: inout fd_set) {
    // On Darwin, fd_set has 32 Int32 slots covering 1024 fds.
    let intOffset = Int(fd / 32)
    let bitOffset = Int(fd % 32)
    let mask: Int32 = 1 << bitOffset
    withUnsafeMutablePointer(to: &set) { ptr in
        ptr.withMemoryRebound(to: Int32.self, capacity: 32) { arr in
            arr[intOffset] |= mask
        }
    }
}

private enum timeMs {
    static func sec(_ ms: Int) -> Int { ms / 1000 }
    static func usec(_ ms: Int) -> Int32 { Int32((ms % 1000) * 1000) }
}
