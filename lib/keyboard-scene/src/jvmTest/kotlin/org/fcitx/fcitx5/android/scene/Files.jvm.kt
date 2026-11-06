/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import java.io.File

internal actual fun readText(path: String): String? = File(path).takeIf { it.isFile }?.readText()

internal actual fun writeText(path: String, text: String) {
    File(path).also { it.parentFile.mkdirs() }.writeText(text)
}

internal actual fun recording(): Boolean = System.getProperty("snapshots.record") == "true"
