/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.BuildConfig

object Const {
    const val versionName = "${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_TYPE}"
    // this fork's build hashes don't exist upstream, so commit links must resolve here
    const val githubRepo = "https://github.com/Sraw/fcitx5-android"
    const val licenseSpdxId = "LGPL-2.1-or-later"
    const val licenseUrl = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1"
    // upstream's pages describe upstream's app
    const val privacyPolicyUrl = "$githubRepo/blob/HEAD/PRIVACY.md"
    const val faqUrl = "$githubRepo#readme"
}