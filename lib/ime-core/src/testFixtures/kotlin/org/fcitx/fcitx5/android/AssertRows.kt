/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

/**
 * A table test: each row is why it is there and its check. The rows-of-data form
 * ([org.fcitx.fcitx5.android.engine.assertRows]) does the checking, so both fail the same way:
 * every row checked, one failure naming each failed row, the first failure as its cause.
 */
fun assertRows(vararg rows: Pair<String, () -> Unit>) {
    require(rows.isNotEmpty()) { "a table of no rows checks nothing" }
    org.fcitx.fcitx5.android.engine.assertRows(rows.asList(), { it.first }) { it.second() }
}
