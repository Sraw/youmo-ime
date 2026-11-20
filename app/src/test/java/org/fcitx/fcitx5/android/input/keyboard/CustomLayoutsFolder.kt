/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import java.io.File

/**
 * [CustomKeyboards] back on [dir] after a test pointed it at a temporary folder, or a later class in the same JVM is
 * offered layouts that are not there.
 */
fun forgetTheTestsLayouts(dir: () -> File) {
    // a listing of the test's folder still on its way would land after the forgetting
    CustomKeyboards.awaitListed()
    CustomKeyboards.forget()
    CustomKeyboards.dir = dir
    CustomKeyboards.changed(dir())
    CustomKeyboards.awaitListed()
}
