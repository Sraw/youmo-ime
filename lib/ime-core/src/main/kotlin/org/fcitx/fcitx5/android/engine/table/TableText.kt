/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.CodeTableReader
import org.fcitx.fcitx5.android.engine.data.SourceException
import java.io.BufferedReader
import java.nio.ByteBuffer

/** A code table's text made into what [CodeTable.load] reads. */
object TableText {

    /**
     * Codes the phrases [reader] found listed without a code by the table's 组词规则, as libime
     * does, adding them to its entries. Those the rules cannot code (a character the table has no
     * code for, a length no rule covers) are left out.
     *
     * @return how many were left out
     */
    fun codePhrases(reader: CodeTableReader): Int {
        if (reader.phrases.isEmpty()) return 0
        // the table so far, only to look the characters' codes up
        val table = TableDictionary(CodeTable.load(ByteBuffer.wrap(reader.builder.build().toByteArray()), verify = false))
        var uncoded = 0
        for (phrase in reader.phrases) {
            val code = table.encode(phrase)
            if (code == null) uncoded++ else reader.builder.entry(code, phrase)
        }
        return uncoded
    }

    /**
     * Reads [text] through as the engine will when its input method is first used, so that a
     * table the user imports that cannot be typed with fails to import rather than to type.
     *
     * @throws SourceException where it cannot be read, or if it lists nothing to type
     */
    fun check(text: BufferedReader, source: String) {
        val reader = CodeTableReader()
        reader.read(text, source)
        if (reader.entries == 0 && reader.phrases.isEmpty()) throw SourceException(source, 0, "nothing to type")
    }
}
