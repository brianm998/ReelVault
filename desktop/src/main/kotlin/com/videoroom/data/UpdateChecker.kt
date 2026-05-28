// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 VideoRoom Contributors

package com.videoroom.data

import com.videoroom.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val version: String,
    val releaseUrl: String,
    val tagName: String
)

object UpdateChecker {

    suspend fun checkLatestRelease(owner: String, repo: String): ReleaseInfo? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "VideoRoom/${AppVersion.CURRENT}")
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                if (conn.responseCode != 200) return@withContext null
                val body = conn.inputStream.bufferedReader().readText()
                val tagName = """"tag_name"\s*:\s*"([^"]+)"""".toRegex()
                    .find(body)?.groupValues?.getOrNull(1) ?: return@withContext null
                // html_url for the release page appears multiple times; the first occurrence is the release itself
                val htmlUrl = """"html_url"\s*:\s*"([^"]+)"""".toRegex()
                    .find(body)?.groupValues?.getOrNull(1) ?: return@withContext null
                val version = tagName.trimStart('v')
                ReleaseInfo(version = version, releaseUrl = htmlUrl, tagName = tagName)
            } catch (_: Exception) {
                null
            }
        }

    /** Returns true when [latestVersion] is strictly newer than [currentVersion] (semver comparison). */
    fun isNewer(latestVersion: String, currentVersion: String): Boolean {
        fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
        val latest = parts(latestVersion)
        val current = parts(currentVersion)
        val len = maxOf(latest.size, current.size)
        for (i in 0 until len) {
            val l = latest.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
