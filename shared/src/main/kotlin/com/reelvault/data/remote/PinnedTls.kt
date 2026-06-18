// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.remote

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS trust that pins the daemon's self-signed certificate by its SHA-256
 * fingerprint (lowercase hex of the leaf DER — the exact string the daemon
 * advertises in its mDNS `fp` TXT record and serves at `GET /fingerprint`).
 *
 * The daemon's cert SANs are advisory only (the client pins by fingerprint, not
 * name), so we ignore the system trust chain AND name-based checks:
 *   • The OkHttp media/pairing client ([pinnedHttpClient]) pins via a custom
 *     trust manager and disables hostname verification outright.
 *   • gRPC channel creation is platform-specific (see [ChannelFactory]).
 */
object PinnedTls {
    /** Lowercase-hex SHA-256 of a certificate's DER encoding. */
    fun fingerprintOf(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }

    /** Accepts only a leaf cert whose SHA-256(DER) equals [expectedFingerprintHex]. */
    class PinningTrustManager(expectedFingerprintHex: String) : X509TrustManager {
        private val expected = expectedFingerprintHex.trim().lowercase()
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("no server certificate presented")
            val actual = fingerprintOf(leaf)
            if (actual != expected) {
                throw CertificateException(
                    "certificate fingerprint mismatch (expected $expected, got $actual)")
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Trusts any certificate. ONLY for the one-shot `GET /fingerprint` TOFU read
     *  on a manually-entered host (we have no pin yet); never used for data. */
    internal class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** An OkHttp client that pins [fingerprintHex] and ignores hostname. */
    fun pinnedHttpClient(fingerprintHex: String): OkHttpClient {
        val tm = PinningTrustManager(fingerprintHex)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** An OkHttp client that trusts everything — TOFU fingerprint read only. */
    fun insecureHttpClient(): OkHttpClient {
        val tm = TrustAllManager()
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
