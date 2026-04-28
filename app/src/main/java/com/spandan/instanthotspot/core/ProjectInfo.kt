package com.spandan.instanthotspot.core

import com.spandan.instanthotspot.BuildConfig

/** URLs for the published repo (Magisk / KernelSU use the same flashable zip in Releases). */
object ProjectInfo {
    val GITHUB_OWNER: String = BuildConfig.GITHUB_OWNER
    val GITHUB_REPO: String = BuildConfig.GITHUB_REPO

    fun repositoryUrl(): String = "https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}"
    fun releasesPageUrl(): String = "${repositoryUrl()}/releases"
    fun latestReleasePageUrl(): String = "${repositoryUrl()}/releases/latest"
    fun issuesUrl(): String = "${repositoryUrl()}/issues"
    fun latestReleaseApiUrl(): String =
        "https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases/latest"
}
