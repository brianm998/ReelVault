// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.util

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory

/**
 * Overrides the Dock tile tooltip for an unbundled macOS process.
 *
 * Why this exists: for raw `java` processes (i.e. `./gradlew run`, before
 * jpackage wraps the app in a .app bundle with an Info.plist), macOS reads
 * the Dock tooltip from `[[NSProcessInfo processInfo] processName]`, which
 * defaults to the executable's last path component — "java".
 *
 * Things that *don't* fix it (we tried them all):
 *   - `-Xdock:name=ReelVault` JVM arg. JBR's launcher swallows the flag —
 *     it's missing from `RuntimeMXBean.inputArguments` at runtime — but
 *     never propagates the value to NSApp on this build.
 *   - `-Dapple.awt.application.name=ReelVault` (whether as a JVM `-D` flag
 *     or via `System.setProperty` before `application{}` runs). The
 *     property does land in the system-properties dictionary, but AWT
 *     uses it only for the menu-bar app label, not the Dock tooltip.
 *   - `Taskbar.setIconImage` / `Window(icon=…)`. Those affect the *icon*,
 *     not the tooltip text. The icon swap landed; the tooltip didn't.
 *
 * What does work: calling `-[NSProcessInfo setProcessName:]` directly via
 * the Objective-C runtime (JNA → libobjc → NSProcessInfo). Apple
 * documents `processName` as `copy` (settable) on NSProcessInfo, and the
 * Dock re-reads it the next time the tile becomes active.
 *
 * Call this once from `main()` *before* `application{}` runs. On
 * non-macOS hosts or if libobjc fails to load (extremely unlikely on
 * macOS), the call is a silent no-op so the app still starts.
 */
object MacDockName {
    private val logger = LoggerFactory.getLogger(MacDockName::class.java)

    fun set(name: String) {
        val os = System.getProperty("os.name", "")
        if (!os.contains("Mac", ignoreCase = true)) return

        try {
            val objc = NativeLibrary.getInstance("objc")
            // Objective-C runtime entry points. objc_msgSend is variadic;
            // JNA's Function.invoke handles that — we just pass the args
            // as an Object[]. Return value is always a Pointer (id).
            val objc_getClass = objc.getFunction("objc_getClass")
            val sel_registerName = objc.getFunction("sel_registerName")
            val objc_msgSend = objc.getFunction("objc_msgSend")

            fun cls(n: String): Pointer =
                objc_getClass.invoke(Pointer::class.java, arrayOf<Any>(n)) as Pointer
            fun sel(n: String): Pointer =
                sel_registerName.invoke(Pointer::class.java, arrayOf<Any>(n)) as Pointer
            fun send(receiver: Pointer, selector: Pointer, vararg args: Any?): Pointer? {
                // Build the JNA call payload as a single Array<Any?> — Kotlin's
                // `arrayOf<Any?>(…) + args` overload-resolves ambiguously
                // between "append element" and "concatenate arrays".
                val payload = Array<Any?>(args.size + 2) { i ->
                    when (i) {
                        0 -> receiver
                        1 -> selector
                        else -> args[i - 2]
                    }
                }
                return objc_msgSend.invoke(Pointer::class.java, payload) as? Pointer
            }

            val nsProcessInfo = cls("NSProcessInfo")
            val processInfo = send(nsProcessInfo, sel("processInfo"))
                ?: error("[NSProcessInfo processInfo] returned null")

            val nsString = cls("NSString")
            val nameStr = send(nsString, sel("stringWithUTF8String:"), name)
                ?: error("Could not build NSString from \"$name\"")

            send(processInfo, sel("setProcessName:"), nameStr)
            logger.info("Set NSProcessInfo.processName = \"{}\"", name)
        } catch (t: Throwable) {
            // Best-effort cosmetic tweak. Swallow so a JNA / libobjc
            // hiccup never blocks app startup — the user just sees the
            // default "java" tooltip until we figure out why.
            logger.warn("Could not set macOS process name: {}", t.toString())
        }
    }
}
