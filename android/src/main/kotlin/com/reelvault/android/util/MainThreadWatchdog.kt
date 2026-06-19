// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.android.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lightweight main-thread stall detector — a mini "ANR watchdog".
 *
 * A daemon thread posts a ping to the main looper and waits up to
 * [stallThresholdMs]. If the ping hasn't run by then the main thread is stuck;
 * we log how long it has been blocked and the main thread's current stack
 * trace, re-dumping every [reDumpIntervalMs] until it recovers.
 *
 * This captures exactly what a plain `adb logcat` lacks when the app freezes to
 * a black screen: WHERE the UI thread is wedged. Look for the "RVWatchdog" tag.
 * Started in debug builds only (see [ReelVaultApp]).
 */
object MainThreadWatchdog {
    private const val TAG = "RVWatchdog"

    @Volatile private var thread: Thread? = null

    fun start(
        stallThresholdMs: Long = 2_000L,
        pollIntervalMs: Long = 1_000L,
        reDumpIntervalMs: Long = 3_000L,
    ) {
        if (thread != null) return
        val mainHandler = Handler(Looper.getMainLooper())
        thread = Thread(
            { loop(mainHandler, stallThresholdMs, pollIntervalMs, reDumpIntervalMs) },
            "rv-main-watchdog",
        ).apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Main-thread watchdog started (threshold=${stallThresholdMs}ms)")
    }

    private fun loop(
        mainHandler: Handler,
        stallThresholdMs: Long,
        pollIntervalMs: Long,
        reDumpIntervalMs: Long,
    ) {
        var stalled = false
        var stallStartedAt = 0L
        var lastDumpAt = 0L
        while (!Thread.currentThread().isInterrupted) {
            val ran = CountDownLatch(1)
            val postedAt = SystemClock.uptimeMillis()
            // post() returns false once the looper is exiting — stop quietly.
            if (!mainHandler.post { ran.countDown() }) return
            val completed = try {
                ran.await(stallThresholdMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            }
            val now = SystemClock.uptimeMillis()
            if (completed) {
                if (stalled) {
                    Log.w(TAG, "Main thread recovered after ~${now - stallStartedAt}ms")
                }
                stalled = false
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (e: InterruptedException) {
                    return
                }
            } else {
                if (!stalled) {
                    stalled = true
                    stallStartedAt = postedAt
                    lastDumpAt = 0L
                }
                if (now - lastDumpAt >= reDumpIntervalMs) {
                    dumpMainStack(now - stallStartedAt)
                    lastDumpAt = now
                }
                // Keep looping (re-posting) until the main thread drains — the
                // posted pings queue up harmlessly and run once it recovers.
            }
        }
    }

    private fun dumpMainStack(blockedForMs: Long) {
        val main = Looper.getMainLooper().thread
        val sb = StringBuilder()
            .append("MAIN THREAD STALLED for ~").append(blockedForMs).append("ms — current stack:\n")
        for (frame in main.stackTrace) {
            sb.append("    at ").append(frame).append('\n')
        }
        Log.w(TAG, sb.toString())
    }
}
