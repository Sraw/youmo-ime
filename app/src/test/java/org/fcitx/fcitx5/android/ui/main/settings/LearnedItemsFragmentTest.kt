/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.ShuangpinNames
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14
import org.fcitx.fcitx5.android.engine.pinyin.nineSp
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.SchemeMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

class LearnedItemsFragmentTest {

    private fun kind(kind: String) = LearnedItemsFragment.kind(Code(kind, "ab"))

    private companion object {
        // a theme's 17 keys (keyboard-scene's test data k17.xml): the 14 keys' groups and three more, a on two keys
        val OWN_GROUPS = NineKeyLetters.G14.tag().split(',') + listOf("pq rs", "abc", "xyz")
        val OWN = NineKeyLetters.custom(OWN_GROUPS)
    }

    // a pin says which keyboard it is of, by the kind's prefix: each kind Kinds makes, named
    @Test
    @Uat("SP-19")
    fun eachKeyboardsKindIsNamed() {
        assertEquals(R.string.learning_kind_full, kind(Kinds.FULL))
        assertEquals(R.string.learning_kind_handwriting, kind(Kinds.HANDWRITING))
        assertEquals(R.string.learning_kind_shuangpin, kind(Kinds.shuangpin("Ziranma")))
        assertEquals(R.string.learning_kind_nine_keys, kind(Kinds.nineKeys("pinyin")))
        assertEquals(R.string.learning_kind_nine_keys, kind(Kinds.nineKeysMultiTap("pinyin")))
        assertEquals(R.string.learning_kind_table, kind(Kinds.table("wbx")))
        assertEquals(R.string.learning_kind_keys14, kind(Kinds.keys(NineKeyLetters.G14.tag())))
        assertEquals(R.string.learning_kind_shuangpin_keys18, kind(Kinds.shuangpinKeys("Ziranma", NineKeyLetters.G18.tag())))
        assertEquals(R.string.learning_kind_shuangpin_nine, kind(Kinds.shuangpinKeys("Xiaohe", NineKeyLetters.NINE_SP.tag())))
        // I1's: pinyin's 18 keys, 双拼's 14
        assertEquals(R.string.learning_kind_keys18, kind(Kinds.keys(NineKeyLetters.G18.tag())))
        assertEquals(R.string.learning_kind_shuangpin_keys14, kind(Kinds.shuangpinKeys("Ziranma", NineKeyLetters.G14.tag())))
        // 060-render-p3 T9b: pinyin on a theme's keys of its own groups, whichever
        assertEquals(R.string.learning_kind_keys_own, kind(Kinds.keys(OWN.tag())))
    }

    // a 14-key pin's keys are the 14 keys' codes (g14), shown as the letters on each key; the nine keys' digits stay digits
    @Test
    @Uat("SP-19")
    fun aGroupedKeyboardsKeysAreShownAsTheirLetters() {
        assertEquals("bn ui", LearnedItemsFragment.keys(Code(Kinds.keys(NineKeyLetters.G14.tag()), g14("bn ui"))))
        // the separator key's ' is in the keys as typed, shown as itself
        assertEquals("zx ui ' as bn", LearnedItemsFragment.keys(Code(Kinds.keys(NineKeyLetters.G14.tag()), "${g14("zx")}${g14("ui")}'${g14("as")}${g14("bn")}")))
        assertEquals("64", LearnedItemsFragment.keys(Code(Kinds.nineKeys("pinyin"), "64")))
        // a theme's keys of its own groups (060-render-p3 T9b): their codes as their letters, a letter on two keys each its own
        assertEquals("bn ui as abc", LearnedItemsFragment.keys(Code(Kinds.keys(OWN.tag()), listOf("bn", "ui", "as", "abc").joinToString("") { OWN.codes[OWN_GROUPS.indexOf(it)].toString() })))
        // a tag of no grouped keyboard this version knows: as stored, not misread through another's letters
        assertEquals(g14("bn ui"), LearnedItemsFragment.keys(Code(Kinds.keys("ab,cd"), g14("bn ui"))))
        // 双拼's: the 18 keys' and the nine keys' private-use codes, as their letters
        val g18 = NineKeyLetters.G18
        assertEquals("bn io", LearnedItemsFragment.keys(Code(Kinds.shuangpinKeys("Ziranma", g18.tag()), "${g18.key('n')}${g18.key('o')}")))
        assertEquals("mno ghi", LearnedItemsFragment.keys(Code(Kinds.shuangpinKeys("Ziranma", NineKeyLetters.NINE_SP.tag()), nineSp("64"))))
        // pinyin's 18 and 双拼's 14 alike
        assertEquals("bn io", LearnedItemsFragment.keys(Code(Kinds.keys(g18.tag()), "${g18.key('n')}${g18.key('o')}")))
        assertEquals("bn ui", LearnedItemsFragment.keys(Code(Kinds.shuangpinKeys("Ziranma", NineKeyLetters.G14.tag()), g14("bn ui"))))
    }

    // Robolectric for the label's strings, this test alone: the rest stay plain JVM
    @RunWith(RobolectricTestRunner::class)
    class Label {

        // a grouped 双拼 pin names its scheme too: the same keys read other syllables under another
        @Test
        fun aGroupedShuangpinPinNamesItsKeyboardAndScheme() {
            val ctx = RuntimeEnvironment.getApplication()
            fun label(kind: String) = LearnedItemsFragment.label(ctx, Code(kind, "ab"))
            assertEquals("Shuangpin, 18 keys · Ziranma", label(Kinds.shuangpinKeys("Ziranma", NineKeyLetters.G18.tag())))
            assertEquals("Shuangpin, 9 keys · Xiaohe", label(Kinds.shuangpinKeys("Xiaohe", NineKeyLetters.NINE_SP.tag())))
            // one 0.5 dropped (SchemeMigration.RETIRED): still named, what was learned under it kept
            assertEquals("Shuangpin, 18 keys · Ziguang", label(Kinds.shuangpinKeys("Ziguang", NineKeyLetters.G18.tag())))
            assertEquals("Shuangpin · GB standard", label(Kinds.shuangpin("GB Standard")))
            for (scheme in EngineSettings.SCHEMES.keys + SchemeMigration.RETIRED) assertNotNull(scheme, ShuangpinNames.of(ctx, scheme))
            // a scheme this version doesn't know: the keyboard alone
            assertEquals("Shuangpin, 18 keys", label(Kinds.shuangpinKeys("Nope", NineKeyLetters.G18.tag())))
            // full 双拼 names its scheme too: the same keys are another syllable under another scheme
            assertTrue(label(Kinds.shuangpin("Ziranma")).startsWith(ctx.getString(R.string.learning_kind_shuangpin) + " · "))
            // the other kinds as before, no scheme added
            assertEquals(ctx.getString(R.string.learning_kind_keys14), label(Kinds.keys(NineKeyLetters.G14.tag())))
            assertEquals("Pinyin, a theme's keys", label(Kinds.keys(OWN.tag())))
        }
    }
}
