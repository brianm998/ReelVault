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
    /** When true, this profile is included in WorkManager / scheduled auto-sync runs. */
    val autoSync: Boolean = false,
) {
    companion object {
        fun fromJson(obj: org.json.JSONObject): SyncProfile = SyncProfile(
            id = obj.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
            name = obj.optString("name"),
            peerKey = obj.optString("peerKey"),
            direction = SyncDirection.values().firstOrNull { it.name == obj.optString("direction") }
                ?: SyncDirection.TO_REMOTE,
            filterJson = obj.optString("filterJson"),
            targetHeight = obj.optInt("targetHeight", 1080),
            deviceLabel = obj.optString("deviceLabel"),
            autoSync = obj.optBoolean("autoSync", false),
        )
    }
}

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
