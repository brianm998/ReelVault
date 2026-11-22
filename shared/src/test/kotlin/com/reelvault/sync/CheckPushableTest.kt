// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CheckPushableTest {
    private fun message(block: () -> Unit): String =
        try { block(); fail("expected failure"); "" } catch (e: Exception) { e.message ?: "" }

    @Test fun zeroByteOriginalGivesClearError() {
        val f = File.createTempFile("empty", ".mov")
        try {
            assertTrue(message { checkPushable("empty.mov", f, "abc") }.contains("empty (0 bytes)"))
        } finally { f.delete() }
    }

    @Test fun nonEmptyOriginalReturnsSize() {
        val f = File.createTempFile("clip", ".mov")
        try {
            f.writeBytes(ByteArray(10))
            assertEquals(10L, checkPushable("clip.mov", f, "abc"))
        } finally { f.delete() }
    }

    @Test fun missingFileAndMissingHashAreReported() {
        assertTrue(message { checkPushable("x.mov", File("/no/such/file"), "abc") }.contains("not a local file"))
        val f = File.createTempFile("clip", ".mov")
        try {
            f.writeBytes(ByteArray(1))
            assertTrue(message { checkPushable("clip.mov", f, "") }.contains("hash not computed"))
        } finally { f.delete() }
    }
}
