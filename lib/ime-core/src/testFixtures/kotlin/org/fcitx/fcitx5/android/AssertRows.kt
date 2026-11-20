/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

/** A table test of rows each with its why and its check, failing as [org.fcitx.fcitx5.android.engine.assertRows] does. */
fun assertRows(vararg rows: Pair<String, () -> Unit>) {
    require(rows.isNotEmpty()) { "a table of no rows checks nothing" }
    org.fcitx.fcitx5.android.engine.assertRows(rows.asList(), { it.first }) { it.second() }
}
