/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// 输入测试台: every kind of input field an IME meets (uat/TESTBED.md); a test tool, in no release.
plugins {
    id("youmo.app-convention")
}

android {
    namespace = "org.fcitx.fcitx5.android.testbed"
    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.testbed"
    }
    buildTypes {
        // one package for the scripts and the docs, whichever build is installed
        debug { applicationIdSuffix = null }
    }
}
