// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Bearer-token authorization for the remote (LAN) surface.
//!
//! Remote clients pair once (over the HTTPS media server) and receive a
//! long-lived bearer token; the daemon stores only its SHA-256. Every LAN gRPC
//! call (via a tonic interceptor) and every protected media request carries the
//! token as `Authorization: Bearer <token>` and is checked here. The loopback
//! gRPC bind is never wrapped, so desktop clients are exempt.

use crate::db::Database;
use sha2::{Digest, Sha256};

/// SHA-256 (lowercase hex) of a bearer token — what we persist and compare.
pub fn token_hash(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

/// Outcome of a token check.
#[derive(Debug, PartialEq, Eq)]
pub enum AuthResult {
    Authorized,
    Unauthorized,
    /// The paired-device list couldn't be read (database busy/closed): the
    /// caller must answer UNAVAILABLE / 503, not "not paired".
    Unavailable,
}

/// Check an `Authorization` header value (`"Bearer <token>"`) against the
/// paired devices (in-memory; no SQLite write).
pub fn check(db: &Database, authorization: Option<&str>) -> AuthResult {
    let token = match authorization.and_then(|s| s.strip_prefix("Bearer ")) {
        Some(t) if !t.is_empty() => t,
        _ => return AuthResult::Unauthorized,
    };
    match db.is_paired_token(&token_hash(token)) {
        Ok(true) => AuthResult::Authorized,
        Ok(false) => AuthResult::Unauthorized,
        Err(e) => {
            tracing::warn!("auth: paired-device lookup failed: {e}");
            AuthResult::Unavailable
        }
    }
}

/// True if the header belongs to a currently-paired device.
pub fn is_authorized(db: &Database, authorization: Option<&str>) -> bool {
    check(db, authorization) == AuthResult::Authorized
}

// ── Admin-RPC gate for the LAN surface ──────────────────────────────────────

/// gRPC methods that reconfigure the daemon's catalog or library roots. A
/// paired LAN device may read and mark/tag, but these are refused on the LAN
/// listener unless `--remote-admin` is set (loopback is never gated).
///
/// `UpdateConfig` is deliberately NOT listed: the iOS remote client's settings
/// screen uses it for per-user preferences (auto-tag, proxy quality).
pub const LAN_ADMIN_METHODS: &[&str] = &[
    "OpenCatalog",
    "CloseCatalog",
    "AddLibraryLocation",
    "RemoveLibraryLocation",
];

/// True iff the gRPC request path (`/pkg.Service/Method`) names an admin RPC.
pub fn is_admin_rpc_path(path: &str) -> bool {
    path.rsplit('/').next().map(|m| LAN_ADMIN_METHODS.contains(&m)).unwrap_or(false)
}

/// Tower layer that answers admin RPCs with PERMISSION_DENIED before they
/// reach the wrapped service.
#[derive(Clone, Copy)]
pub struct AdminGateLayer {
    pub allow_admin: bool,
}

impl<S> tower::Layer<S> for AdminGateLayer {
    type Service = AdminGate<S>;
    fn layer(&self, inner: S) -> AdminGate<S> {
        AdminGate { inner, allow_admin: self.allow_admin }
    }
}

#[derive(Clone)]
pub struct AdminGate<S> {
    inner: S,
    allow_admin: bool,
}

impl<S, B> tower::Service<tonic::codegen::http::Request<B>> for AdminGate<S>
where
    S: tower::Service<
            tonic::codegen::http::Request<B>,
            Response = tonic::codegen::http::Response<tonic::body::BoxBody>,
        > + Clone
        + Send
        + 'static,
    S::Future: Send + 'static,
    S::Error: Send + 'static,
    B: Send + 'static,
{
    type Response = tonic::codegen::http::Response<tonic::body::BoxBody>;
    type Error = S::Error;
    type Future = std::pin::Pin<
        Box<dyn std::future::Future<Output = Result<Self::Response, Self::Error>> + Send>,
    >;

    fn poll_ready(
        &mut self,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, req: tonic::codegen::http::Request<B>) -> Self::Future {
        if !self.allow_admin && is_admin_rpc_path(req.uri().path()) {
            let resp = tonic::Status::permission_denied(
                "catalog administration is not available to remote devices",
            )
            .into_http();
            return Box::pin(std::future::ready(Ok(resp)));
        }
        // Swap so the instance that was poll_ready'd is the one that's called.
        let clone = self.inner.clone();
        let mut inner = std::mem::replace(&mut self.inner, clone);
        Box::pin(inner.call(req))
    }
}

#[cfg(test)]
mod admin_gate_tests {
    use super::*;
    use tower::{Service, ServiceExt};

    #[derive(Clone)]
    struct Ok200;
    impl Service<tonic::codegen::http::Request<()>> for Ok200 {
        type Response = tonic::codegen::http::Response<tonic::body::BoxBody>;
        type Error = std::convert::Infallible;
        type Future = std::future::Ready<Result<Self::Response, Self::Error>>;
        fn poll_ready(
            &mut self,
            _: &mut std::task::Context<'_>,
        ) -> std::task::Poll<Result<(), Self::Error>> {
            std::task::Poll::Ready(Ok(()))
        }
        fn call(&mut self, _: tonic::codegen::http::Request<()>) -> Self::Future {
            let mut r = tonic::codegen::http::Response::new(tonic::body::empty_body());
            r.headers_mut().insert("x-reached-inner", "1".parse().unwrap());
            std::future::ready(Ok(r))
        }
    }

    async fn reached(allow: bool, path: &str) -> bool {
        let mut svc = tower::Layer::layer(&AdminGateLayer { allow_admin: allow }, Ok200);
        let req = tonic::codegen::http::Request::builder().uri(path).body(()).unwrap();
        let resp = svc.ready().await.unwrap().call(req).await.unwrap();
        resp.headers().contains_key("x-reached-inner")
    }

    async fn status_of(allow: bool, path: &str) -> Option<String> {
        let mut svc = tower::Layer::layer(&AdminGateLayer { allow_admin: allow }, Ok200);
        let req = tonic::codegen::http::Request::builder().uri(path).body(()).unwrap();
        let resp = svc.ready().await.unwrap().call(req).await.unwrap();
        resp.headers().get("grpc-status").map(|v| v.to_str().unwrap().to_string())
    }

    #[tokio::test]
    async fn admin_rpcs_denied_on_lan() {
        // 7 == PERMISSION_DENIED
        for m in LAN_ADMIN_METHODS {
            let got = status_of(false, &format!("/reelvault.ReelVault/{m}")).await;
            assert_eq!(got.as_deref(), Some("7"), "{m}");
        }
    }

    #[tokio::test]
    async fn read_and_mark_rpcs_pass() {
        for m in ["ListVideos", "GetMetadata", "TagVideos", "UpdateConfig"] {
            let p = format!("/reelvault.ReelVault/{m}");
            assert_eq!(status_of(false, &p).await, None, "{m}");
            assert!(reached(false, &p).await, "{m} must reach the inner service");
        }
    }

    #[tokio::test]
    async fn remote_admin_flag_allows() {
        assert_eq!(status_of(true, "/reelvault.ReelVault/OpenCatalog").await, None);
    }
}
