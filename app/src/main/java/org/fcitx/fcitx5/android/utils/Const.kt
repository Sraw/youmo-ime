/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.BuildConfig

object Const {
    const val versionName = "${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_TYPE}"
    // this fork's build hashes don't exist upstream, so commit links must resolve here
    const val githubRepo = "https://github.com/Sraw/youmo-ime"
    const val licenseSpdxId = "LGPL-2.1-or-later"
    const val licenseUrl = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1"
    // upstream's pages describe upstream's app
    const val privacyPolicyUrl = "$githubRepo/blob/HEAD/PRIVACY.md"
    // the user's manual, in the repository so it changes with the app: a release's as it shipped
    val helpUrl = manualUrl(githubRepo, BuildConfig.VERSION_NAME)
    const val releasesUrl = "$githubRepo/releases"
}

/**
 * [repo]'s manual for the app named [versionName]: a release's (bare, as at its tag: 0.2.0) at that
 * tag, whose manual is of what it has; any other build's (0.1.0-5-gabc1234, 0.1.0-untagged) at HEAD.
 */
internal fun manualUrl(repo: String, versionName: String): String {
    val ref = if (versionName.matches(RELEASE_NAME)) "v$versionName" else "HEAD"
    return "$repo/blob/$ref/docs/README.md"
}

private val RELEASE_NAME = Regex("\\d+(\\.\\d+)*")