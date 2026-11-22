// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.sync

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import reelvault.Reelvault

class PushFileTest {
    /** Fake remote: indexes a video (keyed by hash) when the final chunk lands. */
    private class FakeRemote : PushTransport {
        val videosByHash = mutableMapOf<String, String>()
        var uploadedChunks = 0
        var applied = mutableListOf<String>()
        var failApplyTimes = 0
        var part = 0L
        override fun bytesReceived(uploadId: String) = part
        override fun uploadChunk(uploadId: String, filename: String, total: Long, offset: Long, contentHash: String, chunk: ByteArray): String? {
            uploadedChunks++
            part = offset + chunk.size
            if (part < total) return null
            part = 0
            return videosByHash.getOrPut(contentHash) { "remote-${videosByHash.size + 1}" }
        }
        override suspend fun remoteVideoForHash(contentHash: String) = videosByHash[contentHash]
        override suspend fun localCatalogData(videoId: String): Reelvault.VideoCatalogData =
            Reelvault.VideoCatalogData.getDefaultInstance()
        override suspend fun applyRemoteCatalogData(remoteVideoId: String, data: Reelvault.VideoCatalogData) {
            if (failApplyTimes > 0) { failApplyTimes--; throw Exception("apply failed") }
            applied.add(remoteVideoId)
        }
    }

    private fun entry() = Reelvault.SyncManifestEntry.newBuilder()
        .setVideoId("local-1").setFilename("a.mov").setContentHash("hash1").build()

    private fun withFile(block: (File) -> Unit) {
        val f = File.createTempFile("clip", ".mov")
        try { f.writeBytes(ByteArray(100)); block(f) } finally { f.delete() }
    }

    @Test fun finishedPushIsNotResentOnLaterRun() = withFile { f ->
        val r = FakeRemote()
        runBlocking {
            pushFile(entry(), f, 100, r)
            val chunks = r.uploadedChunks
            pushFile(entry(), f, 100, r)
            assertEquals("second run uploads nothing", chunks, r.uploadedChunks)
        }
        assertEquals(listOf("remote-1", "remote-1"), r.applied)
        assertEquals(1, r.videosByHash.size)
    }

    @Test fun failedApplyRetryDoesNotReuploadOrDuplicate() = withFile { f ->
        val r = FakeRemote().apply { failApplyTimes = 1 }
        runBlocking {
            try { pushFile(entry(), f, 100, r); fail("expected apply failure") }
            catch (e: Exception) { assertTrue(e.message!!.contains("apply failed")) }
            assertEquals(1, r.uploadedChunks)
            // The remote's stale full-size state must not trigger a restart from 0.
            r.part = 100
            pushFile(entry(), f, 100, r)
        }
        assertEquals("no second upload", 1, r.uploadedChunks)
        assertEquals(1, r.videosByHash.size)
        assertEquals(listOf("remote-1"), r.applied)
    }
}
