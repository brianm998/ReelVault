package com.videoroom.data.editors

import java.io.File
import java.util.Properties

/**
 * Per-user configuration for one editor — stored in [EditorRegistry]'s
 * properties file.
 */
data class EditorConfig(
    /** Whether this editor is enabled (shows up in the context menu). */
    val enabled: Boolean,
    /** Custom executable / .app path (overrides the catalog's [defaultPaths]). */
    val customPath: String,
) {
    companion object {
        val Default = EditorConfig(enabled = true, customPath = "")
    }
}

/**
 * Detection + persistence + launch for external editors.
 *
 * Detection is filesystem-only: it walks [ExternalEditor.defaultPaths] and any
 * configured custom path, returning the first one that exists. We deliberately
 * don't shell out to `which` or query system caches so detection is fast and
 * has no side effects.
 *
 * Configuration is persisted as a [java.util.Properties] file under
 * `~/.config/videoroom/external_editors.properties`. The format is plain
 * key/value, one editor per row:
 * ```
 * davinci-resolve.enabled=true
 * davinci-resolve.path=/Applications/DaVinci Resolve/DaVinci Resolve.app
 * ```
 */
class EditorRegistry {
    private val configFile: File by lazy {
        val home = System.getProperty("user.home") ?: "."
        val dir = File(home, ".config/videoroom")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "external_editors.properties")
    }

    private val props = Properties().also { p ->
        if (configFile.exists()) {
            try {
                configFile.inputStream().use { p.load(it) }
            } catch (_: Exception) {
                // Ignore corrupt file; default config applies.
            }
        }
    }

    /** Load (or compute the default for) one editor's config. */
    fun configFor(editorId: String): EditorConfig {
        val enabled = props.getProperty("$editorId.enabled")
        val path = props.getProperty("$editorId.path") ?: ""
        // Default to enabled=true so newly-supported editors light up
        // automatically when they're installed.
        return EditorConfig(
            enabled = enabled?.toBooleanStrictOrNull() ?: true,
            customPath = path
        )
    }

    /** Persist one editor's config. Saves the file synchronously. */
    fun update(editorId: String, config: EditorConfig) {
        props.setProperty("$editorId.enabled", config.enabled.toString())
        if (config.customPath.isNotEmpty()) {
            props.setProperty("$editorId.path", config.customPath)
        } else {
            props.remove("$editorId.path")
        }
        save()
    }

    private fun save() {
        try {
            configFile.outputStream().use { props.store(it, "VideoRoom external editors") }
        } catch (_: Exception) {
            // Best-effort; persisted across sessions but not critical.
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Detection
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Resolve the path VideoRoom should launch for [editor], or null if the
     * editor isn't installed at any known location. The custom path (if set)
     * wins; otherwise we probe [ExternalEditor.defaultPaths] for the current
     * host.
     */
    fun resolvePath(editor: ExternalEditor): String? {
        val cfg = configFor(editor.id)
        if (cfg.customPath.isNotEmpty() && File(cfg.customPath).exists()) {
            return cfg.customPath
        }
        val host = HostPlatform.detect()
        val candidates = editor.defaultPaths[host].orEmpty()
        return candidates.firstOrNull { File(it).exists() }
    }

    /** True if [editor] is currently installed (or has a working custom path). */
    fun isInstalled(editor: ExternalEditor): Boolean = resolvePath(editor) != null

    /** Editors that should appear in the "Open with" context menu right now. */
    fun availableEditors(): List<Pair<ExternalEditor, String>> {
        return editorsForCurrentPlatform()
            .filter { configFor(it.id).enabled }
            .mapNotNull { editor ->
                resolvePath(editor)?.let { editor to it }
            }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Launching
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Launch [editor] with the given video file paths. Returns true if the
     * process spawned successfully; the caller can show an error toast on
     * false. If [ExternalEditor.supportsFileArgs] is false, the editor is
     * launched empty and the user must import manually.
     */
    fun launch(editor: ExternalEditor, files: List<String>): Boolean {
        val path = resolvePath(editor) ?: return false
        val argv = buildLaunchArgv(path, files, editor.supportsFileArgs)
        return try {
            ProcessBuilder(argv).inheritIO().start()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Build the platform-appropriate argv to launch [path] (an executable or
     * a macOS `.app` bundle) with [files].
     *
     *   * macOS .app bundle → `open -a "<path>" <files...>` so Launch Services
     *     handles the bundle correctly and any "is the developer trusted?"
     *     prompts show up.
     *   * Anything else → spawn the executable directly with the file paths.
     */
    private fun buildLaunchArgv(
        path: String,
        files: List<String>,
        supportsFileArgs: Boolean,
    ): List<String> {
        val isMacAppBundle = path.endsWith(".app") || path.contains(".app/")
        val filesToPass = if (supportsFileArgs) files else emptyList()
        return when {
            isMacAppBundle -> {
                val base = listOf("/usr/bin/open", "-a", path)
                if (filesToPass.isNotEmpty()) base + filesToPass else base
            }
            else -> listOf(path) + filesToPass
        }
    }

    /** Hand a file off to the OS's default video player. */
    fun openWithDefault(filePath: String): Boolean {
        return try {
            val f = File(filePath)
            if (!f.exists()) return false
            // Desktop.open uses the OS's default association for the file type.
            java.awt.Desktop.getDesktop().open(f)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reveal a file in the OS's native file manager (Finder / Explorer /
     * configured file manager on Linux).
     */
    fun revealInFileManager(filePath: String): Boolean {
        val f = File(filePath)
        if (!f.exists()) return false
        return try {
            when (HostPlatform.detect()) {
                HostPlatform.MacOS -> ProcessBuilder("/usr/bin/open", "-R", filePath).start()
                HostPlatform.Windows -> ProcessBuilder("explorer.exe", "/select,$filePath").start()
                HostPlatform.Linux -> {
                    // xdg-open the parent dir; no portable "select" equivalent.
                    val parent = f.parentFile?.absolutePath ?: return false
                    ProcessBuilder("xdg-open", parent).start()
                }
                HostPlatform.Unknown -> return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Open the editor's homepage in the user's default web browser. */
    fun openHomepage(editor: ExternalEditor): Boolean {
        return try {
            java.awt.Desktop.getDesktop().browse(java.net.URI(editor.homepage))
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** Shared single-process registry. */
        val Default by lazy { EditorRegistry() }
    }
}
