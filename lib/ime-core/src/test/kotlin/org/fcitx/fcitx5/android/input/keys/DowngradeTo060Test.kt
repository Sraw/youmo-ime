/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What 0.6.0 makes of what this version writes (061-text-variables §4.3, ST-126): its readers rewritten here by
 * v0.6.0's rules (KeyLists.kt:123 needsMigration, :136 parse's header, KeyListsCache's choice; SwipeSlot.kt:197
 * parseValue, a line it cannot read skipped), not its code, which this version no longer has.
 */
class DowngradeTo060Test {

    // v0.6.0 KeyLists.kt:136: parse is null for any header but `@2`; :123 needsMigration only for none, no number or `@1`
    private object KeyLists060 {
        fun parses(text: String) = text.trimStart { it.isWhitespace() || it == '﻿' }.lineSequence().first().trimEnd() == "@2"
        fun needsMigration(text: String): Boolean {
            val h = text.trimStart { it.isWhitespace() || it == '﻿' }.lineSequence().first().trimEnd()
            return !Regex("@[0-9]+").matches(h) || h == "@1"
        }

        // KeyListsCache: none of this format's and none to migrate: Empty, the defaults, nothing written over
        fun usesDefaults(text: String) = !parses(text) && !needsMigration(text)
    }

    // v0.6.0 SwipeSlot.kt:197 parseValue: a value it knows kept, any other line skipped, the slot its preset
    private object SwipeSlots060 {
        fun kept(text: String): Map<String, String> = text.split('\n').drop(1).mapNotNull { line ->
            val name = line.substringBefore('\t')
            val value = line.substringAfter('\t')
            val known = value == "first" || value == "none" || value.startsWith("text:") ||
                org.fcitx.fcitx5.android.input.shortcut.KeyCommand.ofKey(value) != null
            if (known) name to value else null
        }.toMap()
    }

    @Test
    fun listsOfNoMarkAreReadBy060AsBefore() {
        val text = KeyLists.of(mapOf(KeyId("full/q") to UserList(false, listOf("1", "Q")))).serialize()
        assertTrue(KeyLists060.parses(text))
    }

    @Test
    fun listsMarkedAre060sDefaultsNeverWrittenOver() {
        val text = KeyLists.of(mapOf(KeyId("full/q") to UserList(false, listOf("\$day", "Q"), marks = mapOf("\$day" to ItemMark.Variables)))).serialize()
        assertTrue(text.startsWith("@3"))
        assertFalse(KeyLists060.parses(text))
        assertTrue(KeyLists060.usesDefaults(text))
        // back on this version: read as written
        assertEquals(mapOf("\$day" to ItemMark.Variables), KeyLists.parse(text)!![KeyId("full/q")]!!.marks)
    }

    @Test
    fun aSlotOfVariablesIsSkippedBy060TheOthersKept() {
        val text = SwipeSlots.Default.with("full/q", false, SwipeSlot.Text("\$day", variables = true))
            .with("full/w", false, SwipeSlot.Text("w")).serialize()
        val kept = SwipeSlots060.kept(text)
        assertNull(kept["full/q.down"])
        assertEquals("text:w", kept["full/w.down"])
    }
}
