// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

package com.reelvault.sync

enum class SyncDirection(val proto: String) {
    TO_REMOTE("TO_REMOTE"),
    FROM_REMOTE("FROM_REMOTE"),
    MIRROR("MIRROR");

    companion object {
        fun fromProto(s: String) = values().firstOrNull { it.proto == s } ?: TO_REMOTE
    }
}

data class SyncProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val peerKey: String,
    val direction: SyncDirection,
    val filterJson: String = "",
    val targetHeight: Int = 1080,
    val collectionResolutions: Map<String, CollectionResolution> = emptyMap(),
    val deviceLabel: String = "",
    val lastRunMs: Long? = null,
    val createdMs: Long? = null,
    val updatedMs: Long? = null,
)

data class CollectionResolution(
    val action: Action,
    val renameTo: String? = null,
) {
    enum class Action { COMBINE, RENAME, SKIP }
}

data class SyncJob(
    val id: String = java.util.UUID.randomUUID().toString(),
    val videoId: String,
    val filename: String,
    val direction: SyncDirection,
    var status: SyncJobStatus = SyncJobStatus.PENDING,
    var progress: Float = 0f,
)

enum class SyncJobStatus { PENDING, IN_FLIGHT, DONE, FAILED, SKIPPED }

data class SyncRunResult(
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val conflicts: Int = 0,
    val errors: List<String> = emptyList(),
)
