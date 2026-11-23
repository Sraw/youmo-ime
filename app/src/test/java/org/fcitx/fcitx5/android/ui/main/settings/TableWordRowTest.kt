/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A word a table remembered shows in the learned words under its input method and code, and is not edited. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN")
class TableWordRowTest {

    private val tabled = UserWord("汪小明", "iije", UserWord.Kind.LEARNED, count = 1f, table = InputMethodCatalog.WUBI)
    private val pinyins = UserWord("汪小明", "wang'xiao'ming", UserWord.Kind.LEARNED, count = 1f)

    @Test
    @Uat("WD-66")
    fun aTablesWordReadsAsItsInputMethodAndCode() {
        assertEquals("五笔 · iije", PinyinWords(UserWord.Kind.LEARNED).detail(RuntimeEnvironment.getApplication().resources, tabled))
    }

    @Test
    @Uat("WD-66")
    fun aTablesWordShowsItsDay() {
        val dated = tabled.copy(lastUsed = 20_412)
        val said = PinyinWords(UserWord.Kind.LEARNED).detail(RuntimeEnvironment.getApplication().resources, dated)
        assertEquals("五笔 · iije · 最近用于 ${formatLocalDay(20_412)}", said)
    }

    @Test
    @Uat("WD-66")
    fun aTablesWordHasNoEdit() = assertRows(
        "a table's word: its code is the rules', nothing to edit" to { assertFalse(PinyinWords(UserWord.Kind.LEARNED).canEdit(tabled)) },
        "pinyin's learned word: edited as before" to { assertTrue(PinyinWords(UserWord.Kind.LEARNED).canEdit(pinyins)) },
        "a blocked word: never edited" to { assertFalse(PinyinWords(UserWord.Kind.BLOCKED).canEdit(pinyins)) },
    )

    // the engine's VoiceAlways(on = false) is ime-core's; this is the row that offers it
    @Test
    fun aVoiceAlwaysWordSaysSoAndOffersToCancelIt() {
        val added = PinyinWords(UserWord.Kind.ADDED)
        val always = UserWord("媲美", "pi'mei", UserWord.Kind.ADDED, voiceAlways = true)
        assertEquals("pi'mei · 语音总是换", added.detail(RuntimeEnvironment.getApplication().resources, always))
        assertEquals(listOf(R.string.edit, R.string.voice_always_cancel, R.string.delete), wordActions(added, always, emptyList()))
        assertEquals(listOf(R.string.edit, R.string.delete), wordActions(added, always.copy(voiceAlways = false), emptyList()))
    }
}
