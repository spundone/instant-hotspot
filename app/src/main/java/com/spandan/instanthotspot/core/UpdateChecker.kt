package com.spandan.instanthotspot.core

import com.spandan.instanthotspot.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class UpdateCheckResult(
    val success: Boolean,
    val latestTag: String?,
    val releaseUrl: String?,
    val bodyPreview: String?,
    val isNewerThanInstalled: Boolean,
    val userMessage: String,
)

object UpdateChecker {
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ih-update-check")
    }

    fun checkInBackground(callback: (UpdateCheckResult) -> Unit) {
        io.execute {
            val r = runCatching { fetchLatest() }.getOrElse { t ->
                UpdateCheckResult(
                    success = false,
                    latestTag = null,
                    releaseUrl = null,
                    bodyPreview = null,
                    isNewerThanInstalled = false,
                    userMessage = t.message ?: "Update check failed",
                )
            }
            callback(r)
        }
    }

    private fun fetchLatest(): UpdateCheckResult {
        val u = URL(ProjectInfo.latestReleaseApiUrl())
        val conn = (u.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "InstantHotspot/${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader().use { it?.readText() } ?: ""
        if (code == 404) {
            return UpdateCheckResult(
                success = false,
                latestTag = null,
                releaseUrl = ProjectInfo.releasesPageUrl(),
                bodyPreview = null,
                isNewerThanInstalled = false,
                userMessage = "No public releases found yet. Open the releases page in the browser.",
            )
        }
        if (code == 403 || code == 429) {
            return UpdateCheckResult(
                success = false,
                latestTag = null,
                releaseUrl = ProjectInfo.releasesPageUrl(),
                bodyPreview = null,
                isNewerThanInstalled = false,
                userMessage = "GitHub rate-limited the request. Try again later or open Releases.",
            )
        }
        if (code !in 200..299) {
            return UpdateCheckResult(
                success = false,
                latestTag = null,
                releaseUrl = ProjectInfo.releasesPageUrl(),
                bodyPreview = null,
                isNewerThanInstalled = false,
                userMessage = "Could not check updates (HTTP $code).",
            )
        }
        val o = JSONObject(text)
        val tag = o.optString("tag_name", "").trim().ifBlank { o.optString("name", "") }
        // `html_url` points at the tag page; use a stable endpoint so links never break if tags are cleaned up.
        val stableUrl = ProjectInfo.latestReleasePageUrl()
        val body = o.optString("body", "").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(5)
            .joinToString("\n")
        val installed = BuildConfig.VERSION_NAME
        val newer = isRemoteNewer(sanitizeVersion(tag), sanitizeVersion(installed))
        return UpdateCheckResult(
            success = true,
            latestTag = tag,
            releaseUrl = stableUrl,
            bodyPreview = body.ifBlank { null },
            isNewerThanInstalled = newer,
            userMessage = when {
                tag.isEmpty() -> "Release found but no version tag."
                newer -> "Newer release: $tag (installed $installed)"
                else -> "You are up to date ($installed). Latest: $tag"
            },
        )
    }

    private fun sanitizeVersion(s: String): String {
        return s.trim().removePrefix("v").removePrefix("V").ifBlank { "0" }
    }

    private fun isRemoteNewer(remote: String, current: String): Boolean {
        val r = versionParts(remote) ?: return false
        val c = versionParts(current) ?: return false
        for (i in 0 until 3) {
            if (r[i] > c[i]) return true
            if (r[i] < c[i]) return false
        }
        return false
    }

    private fun versionParts(s: String): List<Int>? {
        val parts = s.split(".", "-", "_", limit = 4)
        if (parts.isEmpty()) return null
        return listOf(
            parts.getOrNull(0)?.toIntOrNull() ?: 0,
            parts.getOrNull(1)?.toIntOrNull() ?: 0,
            parts.getOrNull(2)?.toIntOrNull() ?: 0,
        )
    }
}
