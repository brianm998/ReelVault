// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.data.remote

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackMediaProxyTest {
    private fun ok(m: String, t: String) = LoopbackMediaProxy.isAllowedRequest(m, URI(t))

    @Test fun allowsHlsAndVideoGet() {
        assertTrue(ok("GET", "/hls/abc/720/master.m3u8"))
        assertTrue(ok("GET", "/video/abc?x=1"))
    }

    @Test fun rejectsAbsoluteAndOtherTargets() {
        assertFalse(ok("GET", "http://evil.example/hls/a"))
        assertFalse(ok("GET", "//evil.example/hls/a"))
        assertFalse(ok("GET", "/pair/request"))
        assertFalse(ok("GET", "/hls/../upload"))
        assertFalse(ok("POST", "/hls/a"))
    }
}
