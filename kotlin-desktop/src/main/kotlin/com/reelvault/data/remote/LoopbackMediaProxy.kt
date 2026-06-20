// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * A localhost plaintext HTTP server that proxies media requests to the remote
 * daemon's fingerprint-pinned HTTPS media server, attaching the bearer token.
 *
 * libVLC can't pin a self-signed cert, so it plays the loopback URL and we do
 * the pinned TLS + auth on its behalf — the same trick the iOS/macOS kit uses
 * (`LoopbackMediaProxy`). HLS playlists use relative segment URIs, so libVLC
 * resolves them against the loopback base and they come straight back here; we
 * forward everything, preserving `Range` so seeking + segment fetches work.
 */
class LoopbackMediaProxy(private val endpoint: RemoteConnection.Endpoint) {
    private val logger = LoggerFactory.getLogger(LoopbackMediaProxy::class.java)
    private val client = PinnedTls.pinnedHttpClient(endpoint.fingerprintHex)
    private var server: HttpServer? = null
    @Volatile private var port: Int = 0
    // Once stopped, never rebind: a still-cancelling prepare() coroutine can reach
    // start() after the view's onDispose already called stop(), which would leak a
    // fresh listener nobody owns.
    @Volatile private var stopped = false

    /** Start the loopback listener (idempotent); returns the bound port. */
    @Synchronized
    fun start(): Int {
        if (stopped) return port
        server?.let { return port }
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // A pool so libVLC's concurrent playlist + segment fetches don't serialize.
        s.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "rv-media-proxy").apply { isDaemon = true }
        }
        s.createContext("/") { exchange -> handle(exchange) }
        s.start()
        server = s
        port = s.address.port
        logger.info("Loopback media proxy 127.0.0.1:$port → https://${endpoint.host}:${endpoint.mediaPort}")
        return port
    }

    @Synchronized
    fun stop() {
        stopped = true
        server?.let { try { it.stop(0) } catch (_: Exception) {} }
        server = null
        port = 0
    }

    /** Loopback URL for the HLS playlist of [videoId] at [height] (0 = best). */
    fun hlsUrl(videoId: String, height: Int): String =
        "http://127.0.0.1:$port/hls/$videoId/$height/index.m3u8"

    /**
     * Start the proxy (if needed) and wait until the HLS rendition is a complete,
     * seekable VOD (so libVLC gets a scrub bar), the transcode fails, or we hit
     * [maxWaitMs] (then play progressively). Returns the playlist URL. Mirrors the
     * iOS/macOS readiness gate.
     */
    suspend fun prepare(videoId: String, height: Int, maxWaitMs: Long = 60_000): String =
        withContext(Dispatchers.IO) {
            start()
            val statusUrl = "https://${endpoint.host}:${endpoint.mediaPort}/hls/$videoId/$height/status"
            val deadline = System.currentTimeMillis() + maxWaitMs
            var misses = 0
            while (System.currentTimeMillis() < deadline && !stopped) {
                val st = fetchStatus(statusUrl)
                if (st == null) {
                    // A transient blip (slow NAS warming up) shouldn't end the wait
                    // prematurely; only give up after a few consecutive failures.
                    if (++misses >= 3) break
                    delay(1000)
                    continue
                }
                misses = 0
                if (st.failed || st.complete) break
                delay(1000)
            }
            hlsUrl(videoId, height)
        }

    private fun handle(exchange: HttpExchange) {
        try {
            val uri = exchange.requestURI
            val path = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            val url = "https://${endpoint.host}:${endpoint.mediaPort}$path"
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${endpoint.token}")
            // Pass Range through so seeking + ranged segment fetches work.
            exchange.requestHeaders["Range"]?.firstOrNull()?.let { req.header("Range", it) }
            client.newCall(req.get().build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                resp.header("Content-Type")?.let { exchange.responseHeaders.set("Content-Type", it) }
                resp.header("Content-Range")?.let { exchange.responseHeaders.set("Content-Range", it) }
                resp.header("Accept-Ranges")?.let { exchange.responseHeaders.set("Accept-Ranges", it) }
                exchange.sendResponseHeaders(resp.code, if (bytes.isEmpty()) -1L else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            }
        } catch (e: Exception) {
            logger.warn("proxy ${exchange.requestURI} failed: ${e.message}")
            try { exchange.sendResponseHeaders(502, -1) } catch (_: Exception) {}
        } finally {
            exchange.close()
        }
    }

    private data class HlsStatus(val complete: Boolean, val failed: Boolean)

    private fun fetchStatus(url: String): HlsStatus? = try {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${endpoint.token}").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null
            else resp.body?.string()?.let { t ->
                HlsStatus(
                    complete = Regex("\"complete\"\\s*:\\s*true").containsMatchIn(t),
                    failed = Regex("\"failed\"\\s*:\\s*true").containsMatchIn(t),
                )
            }
        }
    } catch (e: Exception) {
        null
    }
}
