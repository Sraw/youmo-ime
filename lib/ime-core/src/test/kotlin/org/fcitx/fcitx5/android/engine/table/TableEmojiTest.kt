/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.handwriting.handwritingData
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.session.Action.NextPage
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A table's emoji candidates (EmojiCandidates): as pinyin's, and no 重码 of the code. */
class TableEmojiTest {

    private val table = dictionary("a 工", "a 戈", "aa 式", "aaa 🔧", "kkkk 叫", "wqvb 你好")

    private val emoji = mapOf("工" to listOf("🔧", "🛠️", "⚒️"), "叫" to listOf("📢"), "你好" to listOf("👋"))

    private fun session(emoji: Map<String, List<String>> = this.emoji, options: TableOptions = TableOptions.WUBI): TableSession {
        val d = handwritingData
        return TableSession(table, options, language = TableLanguage(TextWords(d.model, d.wordIndex), emoji = { emoji[it].orEmpty() }))
    }

    @Test
    fun theFirstsEmojiFollowTheSecondWithNoHint() {
        val s = session().type("a").second
        assertEquals(listOf("工", "戈", "🔧", "🛠️", "式"), s.candidates)
        assertEquals(listOf("", "", "", "", "a"), s.hints)
        // an entry of an emoji's text, listed once
        assertFalse("⚒️" in s.candidates)
        assertEquals(1, session().type("a").second.candidates.count { it == "🔧" })
        assertEquals(listOf("工", "戈", "式", "🔧"), session(emptyMap()).type("a").second.candidates)
    }

    @Test
    fun aLoneCandidateStillCommitsItselfTheEmojiAside() {
        val t = session()
        assertEquals("叫", t.type("kkkk").first)
    }

    @Test
    fun anEmojiIsPutInAsTextWithNothingToForgetOrBlock() {
        val t = session()
        val s = t.type("wqv").second
        assertEquals(listOf("你好", "👋"), s.candidates)
        assertEquals(emptySet<Any>(), t.offers(1))
        assertEquals("👋", t.apply(Select(1)).commit)
    }

    @Test
    fun aKeyEndingACodeOnAPageAnEmojiStartsCommitsTheFirstReadingNotTheEmoji() {
        val t = session(options = TableOptions.WUBI.copy(pageSize = 3))
        t.type("a")
        assertEquals("🛠️", t.apply(NextPage).candidates.first())
        // a comma, no code key, ends the code: what it commits, not the emoji first on the page
        assertEquals("工", t.type(",").first)
    }
}
