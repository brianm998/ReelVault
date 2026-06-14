// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

import GRPCCore

/// Client interceptor that attaches the paired-device bearer token to every
/// gRPC call as `authorization: Bearer <token>`, satisfying the daemon's LAN
/// auth interceptor. Added to the client only when a token is present (the
/// macOS loopback path uses none).
public struct BearerTokenInterceptor: ClientInterceptor {
    let token: String

    public init(token: String) {
        self.token = token
    }

    public func intercept<Input: Sendable, Output: Sendable>(
        request: StreamingClientRequest<Input>,
        context: ClientContext,
        next: (StreamingClientRequest<Input>, ClientContext) async throws -> StreamingClientResponse<Output>
    ) async throws -> StreamingClientResponse<Output> {
        var request = request
        request.metadata.replaceOrAddString("Bearer \(token)", forKey: "authorization")
        return try await next(request, context)
    }
}
