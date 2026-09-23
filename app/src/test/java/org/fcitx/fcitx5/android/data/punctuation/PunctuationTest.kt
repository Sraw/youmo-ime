/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import org.fcitx.fcitx5.android.core.RawConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The punctuation table maps a typed key to the punctuation the IME commits, plus an alternate
 * mapping used for paired marks (the second quote of a pair, and so on). It is exchanged with
 * fcitx as a [RawConfig] tree.
 */
class PunctuationTest {

    private fun entryConfig(idx: Int, key: String, mapping: String, alt: String) = RawConfig(
        idx.toString(),
        arrayOf(
            RawConfig(PunctuationManager.KEY, key),
            RawConfig(PunctuationManager.MAPPING, mapping),
            RawConfig(PunctuationManager.ALT_MAPPING, alt),
        )
    )

    private fun config(vararg entries: RawConfig) = RawConfig(
        arrayOf(RawConfig("cfg", arrayOf(RawConfig(PunctuationManager.ENTRIES, arrayOf(*entries)))))
    )

    // region PunctuationMapEntry

    @Test
    fun anEntryReadsItsThreeFieldsFromRawConfig() {
        val e = PunctuationMapEntry(entryConfig(0, ",", "，", "、"))
        assertEquals(",", e.key)
        assertEquals("，", e.mapping)
        assertEquals("、", e.altMapping)
    }

    @Test
    fun anEntryWritesBackTheSameThreeFields() {
        val raw = PunctuationMapEntry(",", "，", "、").toRawConfig(3)
        assertEquals("the index becomes the node name", "3", raw.name)
        assertEquals(",", raw[PunctuationManager.KEY].value)
        assertEquals("，", raw[PunctuationManager.MAPPING].value)
        assertEquals("、", raw[PunctuationManager.ALT_MAPPING].value)
    }

    @Test
    fun anEntrySurvivesARoundTripThroughRawConfig() {
        val original = PunctuationMapEntry("\"", "“", "”")
        assertEquals(original, PunctuationMapEntry(original.toRawConfig(0)))
    }

    @Test
    fun emptyMappingsAreCarriedThrough() {
        val original = PunctuationMapEntry("x", "", "")
        assertEquals(original, PunctuationMapEntry(original.toRawConfig(0)))
    }

    // endregion

    // region parseRawConfig

    @Test
    fun parsesEveryEntryInOrder() {
        val parsed = PunctuationManager.parseRawConfig(
            config(
                entryConfig(0, ",", "，", "、"),
                entryConfig(1, ".", "。", "."),
                entryConfig(2, "?", "？", "?"),
            )
        )
        assertEquals(
            listOf(
                PunctuationMapEntry(",", "，", "、"),
                PunctuationMapEntry(".", "。", "."),
                PunctuationMapEntry("?", "？", "?"),
            ),
            parsed,
        )
    }

    @Test
    fun anEmptyEntryListParsesToNoEntries() {
        assertTrue(PunctuationManager.parseRawConfig(config()).isEmpty())
    }

    /** A config without the `cfg` wrapper is not an error; it simply has no entries. */
    @Test
    fun aConfigWithoutTheCfgNodeYieldsNoEntries() {
        assertTrue(PunctuationManager.parseRawConfig(RawConfig()).isEmpty())
    }

    /**
     * A `cfg` node with no `Entries` child (a damaged config, or a language whose punctuation
     * config was never filled in) degrades to an empty list instead of crashing the settings
     * page. It used to throw, because `RawConfig.get` is `findByName(name)!!`.
     */
    @Test
    fun aCfgNodeWithoutAnEntriesChildYieldsNoEntries() {
        val raw = RawConfig(arrayOf(RawConfig("cfg", arrayOf(RawConfig("Unrelated", "x")))))
        assertTrue(PunctuationManager.parseRawConfig(raw).isEmpty())
    }

    @Test
    fun theWholeTableSurvivesARoundTrip() {
        val entries = listOf(
            PunctuationMapEntry(",", "，", "、"),
            PunctuationMapEntry(".", "。", "."),
            PunctuationMapEntry("\"", "“", "”"),
        )
        val rebuilt = config(*entries.mapIndexed { i, e -> e.toRawConfig(i) }.toTypedArray())
        assertEquals(entries, PunctuationManager.parseRawConfig(rebuilt))
    }

    // endregion
}
