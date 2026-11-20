/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.os.Build

/**
 * The SDK the screenshot goldens are recorded at. At minSdk 29 the framework draws text and shapes a little
 * differently: there a capture still lays out and draws, but is compared with nothing.
 */
const val SCREENSHOT_SDK = 35

val comparesScreenshots: Boolean get() = Build.VERSION.SDK_INT == SCREENSHOT_SDK
