/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.store.RecordFormat

/**
 * A table's log of the builds till 0.4.0 (`<name>.user`, FXUT), read once for what 0.4's table
 * offered from it as saved phrases (F2 review): each code and text saved, or picked (0.3.0's
 * picks, which 0.4 converted only to counts), and not forgotten after, in the log's order. An
 * entry the table has stands for one of them: that is the caller's to leave out, by the table.
 */
internal object OlderTableLog {
    private val FORMAT = RecordFormat("FXUT", 1)
    private const val PICKED: Byte = 1
    private const val SAVED: Byte = 2
    private const val FORGOT: Byte = 4

    /** The log of the table [im] in [dir]. */
    fun file(dir: UserFiles, im: String) = dir.file(im.removePrefix("engine-") + ".user")

    /** The codes and texts [bytes] keeps as phrases; none if it is no table's log. */
    fun phrases(bytes: ByteArray): List<Pair<String, String>> {
        if (!FORMAT.hasHeader(bytes)) return emptyList()
        val kept = LinkedHashSet<Pair<String, String>>()
        FORMAT.read(bytes) { type, input ->
            // what an older build saw typed (SEEN) made a phrase no more; a type of a later build's, unknown
            if (type != PICKED && type != SAVED && type != FORGOT) return@read
            val key = input.readUTF() to input.readUTF()
            if (key.first.isEmpty() || key.second.isEmpty()) return@read
            if (type == FORGOT) kept -= key else kept += key
        }
        return kept.toList()
    }
}
