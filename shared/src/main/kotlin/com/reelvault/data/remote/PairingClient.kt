// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.net.InetAddress

/**
 * Talks to the daemon's HTTPS media-server pairing endpoints (fingerprint-pinned):
 *   • `GET  /fingerprint`   — TOFU read of the leaf cert pin (manual host entry).
 *   • `POST /pair/request`  — notify the operator's desktop that a device wants in
 *                             (pops the Allow/Dismiss banner that reveals a code).
 *   • `POST /pair {pin,…}`  — redeem the 6-digit code for a long-lived token.
 *   • `POST /pair/revoke`   — self-revoke a token (Bearer-authed, idempotent).
 *
 * Mirrors the Swift kit's `PairingClient`. JSON shapes are fixed and tiny, so we
 * format/parse them directly rather than add a JSON dependency.
 */
class PairingClient {
    private val logger = LoggerFactory.getLogger(PairingClient::class.java)
    private val jsonMedia = "application/json".toMediaType()

    /** A label for this device, shown on the server's Allow banner / paired list. */
    val deviceName: String by lazy {
        val host = try { InetAddress.getLocalHost().hostName } catch (_: Exception) { null }
        if (host.isNullOrBlank()) "ReelVault" else "$host (ReelVault)"
    }

    /** TOFU: read a manually-entered server's leaf fingerprint (no pin yet). */
    suspend fun fetchFingerprint(host: String, mediaPort: Int): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("https://$host:$mediaPort/fingerprint").build()
            PinnedTls.insecureHttpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()?.trim()?.lowercase()?.takeIf {
                    it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' }
                }
            }
        } catch (e: Exception) {
            logger.warn("GET /fingerprint $host:$mediaPort failed: ${e.message}")
            null
        }
    }

    /** Ask the server to surface a pairing code on its connected desktop clients. */
    suspend fun requestPairing(host: String, mediaPort: Int, fingerprintHex: String) = withContext(Dispatchers.IO) {
        try {
            val body = "{\"device_name\":${jsonString(deviceName)}}".toRequestBody(jsonMedia)
            val req = Request.Builder().url("https://$host:$mediaPort/pair/request").post(body).build()
            PinnedTls.pinnedHttpClient(fingerprintHex).newCall(req).execute().use {}
        } catch (e: Exception) {
            logger.warn("POST /pair/request $host:$mediaPort failed: ${e.message}")
        }
    }

    /** Redeem [pin] for a long-lived bearer token, or `null` if rejected. */
    suspend fun pair(host: String, mediaPort: Int, fingerprintHex: String, pin: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val payload = "{\"pin\":${jsonString(pin.trim())},\"device_name\":${jsonString(deviceName)}}"
                val req = Request.Builder()
                    .url("https://$host:$mediaPort/pair")
                    .post(payload.toRequestBody(jsonMedia))
                    .build()
                PinnedTls.pinnedHttpClient(fingerprintHex).newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val text = resp.body?.string() ?: return@withContext null
                    Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
                }
            } catch (e: Exception) {
                logger.warn("POST /pair $host:$mediaPort failed: ${e.message}")
                null
            }
        }

    /** Self-revoke a token server-side (idempotent). Best-effort. */
    suspend fun revoke(host: String, mediaPort: Int, fingerprintHex: String, token: String) =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://$host:$mediaPort/pair/revoke")
                    .post(ByteArray(0).toRequestBody(null))
                    .header("Authorization", "Bearer $token")
                    .build()
                PinnedTls.pinnedHttpClient(fingerprintHex).newCall(req).execute().use {}
            } catch (e: Exception) {
                logger.warn("POST /pair/revoke $host:$mediaPort failed: ${e.message}")
            }
        }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
