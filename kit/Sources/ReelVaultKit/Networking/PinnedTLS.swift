// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import CryptoKit
import Foundation
import GRPCNIOTransportHTTP2
import Security

/// Fingerprint-pinned TLS for talking to a daemon's self-signed certificate.
///
/// grpc-swift's public TLS config has no custom-verification callback, only a
/// trust-roots + verification-mode knob. So we pin by trusting the server's
/// *actual* certificate as the sole trust root (with hostname verification off,
/// since the cert's SANs are advisory). The client obtains that certificate by
/// a TLS probe and confirms its SHA-256 matches the fingerprint advertised over
/// mDNS (or entered manually) before pinning it.
public enum PinnedTLS {
    /// A gRPC client transport security that trusts only `pinnedCertDER`.
    public static func clientSecurity(
        pinnedCertDER: Data
    ) -> HTTP2ClientTransport.Posix.TransportSecurity {
        .tls { config in
            config.trustRoots = .certificates([.bytes(Array(pinnedCertDER), format: .der)])
            config.serverCertificateVerification = .noHostnameVerification
        }
    }

    /// Lowercase-hex SHA-256 of a DER certificate — the pin value.
    public static func fingerprint(ofDER der: Data) -> String {
        SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
    }

    /// Perform a TLS handshake to `host:port`, capture the presented leaf
    /// certificate, and return its DER. When `expectedFingerprintHex` is set,
    /// returns the DER only if the fingerprint matches (TOFU verification);
    /// otherwise returns whatever was presented for out-of-band confirmation.
    public static func fetchServerCertificate(
        host: String, port: Int, expectedFingerprintHex: String?
    ) async -> Data? {
        await withCheckedContinuation { (cont: CheckedContinuation<Data?, Never>) in
            let delegate = CertCaptureDelegate(expected: expectedFingerprintHex) { der in
                cont.resume(returning: der)
            }
            let session = URLSession(
                configuration: .ephemeral, delegate: delegate, delegateQueue: nil
            )
            guard let url = URL(string: "https://\(host):\(port)/") else {
                cont.resume(returning: nil)
                return
            }
            var req = URLRequest(url: url)
            req.timeoutInterval = 5
            let task = session.dataTask(with: req) { _, _, _ in
                // If the handshake didn't fire the trust challenge, resume nil.
                delegate.resumeIfNeeded(nil)
                session.finishTasksAndInvalidate()
            }
            task.resume()
        }
    }
}

/// URLSession delegate that captures the server's leaf certificate during the
/// TLS handshake, verifies its fingerprint, then cancels the request.
private final class CertCaptureDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
    private let expected: String?
    private let completion: (Data?) -> Void
    private var done = false
    private let lock = NSLock()

    init(expected: String?, completion: @escaping (Data?) -> Void) {
        self.expected = expected?.lowercased()
        self.completion = completion
    }

    func resumeIfNeeded(_ der: Data?) {
        lock.lock()
        defer { lock.unlock() }
        if done { return }
        done = true
        completion(der)
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust
        else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        let der = Self.leafCertificateDER(from: trust)
        if let der {
            let fp = PinnedTLS.fingerprint(ofDER: der)
            if let expected, fp != expected {
                resumeIfNeeded(nil)
            } else {
                resumeIfNeeded(der)
            }
        } else {
            resumeIfNeeded(nil)
        }
        // We only wanted the certificate; don't actually complete the request.
        completionHandler(.cancelAuthenticationChallenge, nil)
    }

    private static func leafCertificateDER(from trust: SecTrust) -> Data? {
        if #available(macOS 12.0, iOS 15.0, *) {
            guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
                  let leaf = chain.first
            else { return nil }
            return SecCertificateCopyData(leaf) as Data
        } else {
            guard SecTrustGetCertificateCount(trust) > 0,
                  let leaf = SecTrustGetCertificateAtIndex(trust, 0)
            else { return nil }
            return SecCertificateCopyData(leaf) as Data
        }
    }
}
