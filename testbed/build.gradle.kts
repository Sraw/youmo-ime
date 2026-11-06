/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

/*
 * 输入测试台: one app with every kind of input field and the app behaviours an IME meets
 * (uat/TESTBED.md). A tool for the emulator and phone tests; not in a release, nothing depends on it.
 * Framework Views and no library: nothing new for verification-metadata, a build in seconds.
 */
plugins {
    id("org.fcitx.fcitx5.android.app-convention")
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
