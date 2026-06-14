// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import Foundation
import Network

/// A loopback HTTP/1.1 reverse proxy that lets `AVPlayer` stream HLS from the
/// daemon over its fingerprint-pinned, self-signed TLS cert.
///
/// `AVPlayer`'s internal networking won't call our `URLSession` delegate, so a
/// plain `AVURLAsset(https://…)` can't validate (let alone pin) a self-signed
/// cert. Instead we point `AVPlayer` at `http://127.0.0.1:<port>/…` — cleartext
/// to loopback, which ATS exempts — and forward every request to the daemon over
/// the EXISTING pinned `URLSession` (`FingerprintPinningDelegate` + bearer
/// token). The HLS playlist's relative segment URIs resolve against the loopback
/// base, so nothing needs rewriting and `AVPlayer` stays on its native code path.
///
/// One instance per playback session. It binds to 127.0.0.1 ONLY — it relays an
/// authenticated, *unpinned* cleartext copy of the stream and must never be
/// reachable on the LAN. Retain it for the player's lifetime; `stop()` on teardown.
public final class LoopbackMediaProxy: @unchecked Sendable {
    /// Holds an `NWConnection` so it can cross the `@Sendable` boundary of the
    /// `URLSession`/`NWConnection` completion closures (Network's types aren't
    /// `Sendable`, but are safe for concurrent use).
    private final class ConnBox: @unchecked Sendable {
        let conn: NWConnection
        init(_ conn: NWConnection) { self.conn = conn }
    }

    enum ProxyError: Error { case cancelled }

    private let endpoint: MediaClient.Endpoint
    private let upstream: URLSession
    private let base: URL
    private let queue = DispatchQueue(label: "reelvault.loopback-proxy")
    private let lock = NSLock()
    private var listener: NWListener?
    private var port: UInt16 = 0
    private var didResume = false

    public init(endpoint: MediaClient.Endpoint) {
        self.endpoint = endpoint
        let delegate = FingerprintPinningDelegate(expectedFingerprintHex: endpoint.fingerprintHex)
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpMaximumConnectionsPerHost = 6   // match AVPlayer's parallel segment fetches
        cfg.timeoutIntervalForRequest = 60      // first master GET blocks server-side ≤20s
        self.upstream = URLSession(configuration: cfg, delegate: delegate, delegateQueue: nil)
        var c = URLComponents()
        c.scheme = "https"
        c.host = endpoint.host
        c.port = endpoint.mediaPort
        self.base = c.url!
    }

    /// Start listening on 127.0.0.1 and resolve with the assigned port once ready.
    public func start() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<UInt16, Error>) in
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            // Loopback ONLY — never expose the unpinned cleartext relay on the LAN.
            params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
            let l: NWListener
            do {
                l = try NWListener(using: params)
            } catch {
                cont.resume(throwing: error)
                return
            }
            self.lock.lock(); self.listener = l; self.lock.unlock()
            l.newConnectionHandler = { [weak self] conn in self?.handle(conn) }
            l.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    self.resumeOnce(cont, .success(l.port?.rawValue ?? 0))
                case .failed(let err):
                    self.resumeOnce(cont, .failure(err))
                case .cancelled:
                    self.resumeOnce(cont, .failure(ProxyError.cancelled))
                default:
                    break
                }
            }
            l.start(queue: self.queue)
        }
    }

    /// `http://127.0.0.1:<port>/hls/<id>/<height>/index.m3u8` — hand THIS to
    /// `AVPlayer` (plain http to loopback, no pinning/ATS work needed). We point
    /// at the media playlist, not a master: ffmpeg can publish a variant-less
    /// (empty) master mid-transcode, which AVPlayer dead-ends on.
    public func hlsURL(videoId: String, height: Int) -> URL {
        lock.lock(); let p = port; lock.unlock()
        var c = URLComponents()
        c.scheme = "http"
        c.host = "127.0.0.1"
        c.port = Int(p)
        c.path = "/hls/\(videoId)/\(height)/index.m3u8"
        return c.url!
    }

    /// `http://127.0.0.1:<port>/hls/<id>/<height>/status` — JSON progress
    /// (`{segments, complete, segSeconds}`) for the client's readiness gate.
    public func statusURL(videoId: String, height: Int) -> URL {
        lock.lock(); let p = port; lock.unlock()
        var c = URLComponents()
        c.scheme = "http"
        c.host = "127.0.0.1"
        c.port = Int(p)
        c.path = "/hls/\(videoId)/\(height)/status"
        return c.url!
    }

    public func stop() {
        lock.lock(); let l = listener; listener = nil; lock.unlock()
        l?.cancel()
        upstream.invalidateAndCancel()
    }

    // MARK: - Continuation

    private func resumeOnce(_ cont: CheckedContinuation<UInt16, Error>, _ result: Result<UInt16, Error>) {
        lock.lock()
        if didResume { lock.unlock(); return }
        didResume = true
        if case .success(let p) = result { port = p }
        lock.unlock()
        cont.resume(with: result)
    }

    // MARK: - Per-connection proxying (one request, forward, stream back, close)

    private func handle(_ conn: NWConnection) {
        let box = ConnBox(conn)
        conn.start(queue: queue)
        readHead(box, buffer: Data())
    }

    /// Read until the CRLF-CRLF that ends the request head. We only need the
    /// request-line target and the `Range` header; `AVPlayer` never sends a body.
    private func readHead(_ box: ConnBox, buffer: Data) {
        box.conn.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { box.conn.cancel(); return }
            var buf = buffer
            if let data { buf.append(data) }
            if let sep = buf.range(of: Data([0x0d, 0x0a, 0x0d, 0x0a])) {
                self.dispatch(box, head: buf[..<sep.lowerBound])
                return
            }
            if error != nil || isComplete { box.conn.cancel(); return }
            if buf.count > 64 * 1024 { self.writeError(box, 431); return }
            self.readHead(box, buffer: buf)
        }
    }

    private func dispatch(_ box: ConnBox, head: Data) {
        let text = String(decoding: head, as: UTF8.self)
        let lines = text.components(separatedBy: "\r\n")
        guard let reqLine = lines.first else { box.conn.cancel(); return }
        let parts = reqLine.split(separator: " ")
        guard parts.count >= 2 else { writeError(box, 400); return }
        let target = String(parts[1])           // e.g. /hls/<id>/<height>/master.m3u8
        var range: String?
        for line in lines.dropFirst() where line.lowercased().hasPrefix("range:") {
            range = String(line.dropFirst("range:".count)).trimmingCharacters(in: .whitespaces)
        }
        forward(box, target: target, range: range)
    }

    private func forward(_ box: ConnBox, target: String, range: String?) {
        guard let url = URL(string: target, relativeTo: base)?.absoluteURL else {
            writeError(box, 400); return
        }
        var req = URLRequest(url: url)
        if let token = endpoint.bearerToken { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let range { req.setValue(range, forHTTPHeaderField: "Range") }   // byte-range passthrough
        let task = upstream.dataTask(with: req) { [weak self] data, resp, error in
            guard let self else { box.conn.cancel(); return }
            if let error {
                NSLog("ReelVault proxy: upstream ERROR for \(target): \(error.localizedDescription)")
                self.writeError(box, 502); return
            }
            guard let http = resp as? HTTPURLResponse, let data else {
                NSLog("ReelVault proxy: no/invalid response for \(target)")
                self.writeError(box, 502); return
            }
            // Trace every fetch so a failed segment (404 ahead of the live head,
            // 500 from a dead transcode) is visible in the client log next to the
            // AVPlayer error-log entry that references the same URI.
            if (200..<300).contains(http.statusCode) {
                NSLog("ReelVault proxy: \(http.statusCode) \(target) (\(data.count)B)")
            } else {
                NSLog("ReelVault proxy: upstream HTTP \(http.statusCode) for \(target) (\(data.count)B)")
            }
            self.writeResponse(box, http: http, body: data, target: target)
        }
        task.resume()
    }

    private func writeResponse(_ box: ConnBox, http: HTTPURLResponse, body: Data, target: String) {
        let ct = http.value(forHTTPHeaderField: "Content-Type")
            ?? (target.contains(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t")
        var headers = "Content-Type: \(ct)\r\n"
        headers += "Content-Length: \(body.count)\r\n"
        if let cr = http.value(forHTTPHeaderField: "Content-Range") { headers += "Content-Range: \(cr)\r\n" }
        headers += "Accept-Ranges: bytes\r\n"
        // Close per request: AVPlayer parallelises across connections, so we skip
        // HTTP/1.1 keep-alive framing (easy to get wrong) entirely.
        headers += "Connection: close\r\n"
        let head = "HTTP/1.1 \(http.statusCode) \(Self.reason(http.statusCode))\r\n\(headers)\r\n"
        var out = Data(head.utf8)
        out.append(body)
        box.conn.send(content: out, completion: .contentProcessed { _ in box.conn.cancel() })
    }

    private func writeError(_ box: ConnBox, _ status: Int) {
        let head = "HTTP/1.1 \(status) \(Self.reason(status))\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        box.conn.send(content: Data(head.utf8), completion: .contentProcessed { _ in box.conn.cancel() })
    }

    private static func reason(_ s: Int) -> String {
        switch s {
        case 200: return "OK"
        case 206: return "Partial Content"
        case 400: return "Bad Request"
        case 404: return "Not Found"
        case 431: return "Request Header Fields Too Large"
        case 502: return "Bad Gateway"
        default: return "Error"
        }
    }
}
