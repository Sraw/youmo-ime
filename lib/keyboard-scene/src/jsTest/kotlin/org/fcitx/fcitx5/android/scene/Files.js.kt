/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

// node's fs: the JS tests run in node (build.gradle.kts), and compare with the snapshots the JVM wrote
private val fs: dynamic = js("require('fs')")

internal actual fun readText(path: String): String? =
    if (fs.existsSync(path) as Boolean) fs.readFileSync(path, "utf8") as String else null

internal actual fun writeText(path: String, text: String) {
    fs.writeFileSync(path, text, "utf8")
}

internal actual fun recording(): Boolean = false
