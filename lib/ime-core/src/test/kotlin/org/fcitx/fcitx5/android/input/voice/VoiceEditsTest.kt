/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones.Companion.reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音先问再记's following of voice's text (061-voice-learn-from-edits.md §2–§3). */
class VoiceEditsTest {

    private class Words : VoiceEdits.Words {
        val chars = HashMap<Char, IntArray>()
        var added = emptySet<String>()
        var learned = emptySet<String>()
        var blocked = emptySet<String>()
        val dictionary = HashMap<String, String>()

        // what voice would not put in: VoiceHomophones' rules (a common word left, say), faked
        var refused = emptySet<String>()
        val taken = ArrayList<String>()

        override fun readings(c: Char) = chars[c]
        override fun mine() = VoiceEdits.Mine(added, learned, blocked)
        override fun pinyinOf(text: String) = dictionary[text]
        override fun takes(text: String, at: Int, heard: String, word: String, pinyin: String, replacing: String?): Boolean {
            taken += "$text@$at:$heard>$word($pinyin)" + (replacing?.let { "-$it" } ?: "")
            return word !in refused
        }
    }

    // the characters' readings, and what [set] sets: a row that sets the user's words has its own (R26 F8147: rows set
    // and reset one shared, and a row failed before its reset left them set for the rows after it)
    private fun words(set: Words.() -> Unit = {}) = Words().apply {
        for ((c, readings) in listOf(
            '我' to listOf("wo" to 3), '找' to listOf("zhao" to 3), '了' to listOf("le" to 0, "liao" to 3),
            '徐' to listOf("xu" to 2), '许' to listOf("xu" to 3), '静' to listOf("jing" to 4), '婧' to listOf("jing" to 4),
            '靖' to listOf("jing" to 4), '长' to listOf("chang" to 2, "zhang" to 3), '掌' to listOf("zhang" to 3),
            '公' to listOf("gong" to 1), '攻' to listOf("gong" to 1), '式' to listOf("shi" to 4), '势' to listOf("shi" to 4),
            '柜' to listOf("gui" to 4), '贵' to listOf("gui" to 4),
        )) chars[c] = readings.map { (s, t) -> reading(Syllables.id(s), t) }.toIntArray()
        set()
    }

    private val words = words()

    private val traditional = mapOf('靜' to '静', '許' to '许')
    private fun edits(words: Words = this.words) = VoiceEdits(words) { s -> s.map { traditional[it] ?: it }.joinToString("") }

    // voice's [said] in an empty field, its last [n] deleted by Backspace, [typed] typed into the gap by pinyin:
    // what is asked once the field reads as followed
    private fun VoiceEdits.retype(said: String, n: Int, typed: String, after: String = ""): VoiceEdits.Ask? {
        said(said, said)
        repeat(n) { backspace() }
        committed(typed, typed = true)
        val reply = putIn(typed)
        assertTrue("open after its own commit", reply.open)
        return reply.ask?.let { confirm(said.dropLast(n) + typed, after) }
    }

    @Test
    @Uat("VO-41")
    fun `a word deleted and typed again, read the same, is asked about`() {
        val ask = asked(edits().retype("我找徐静", 2, "徐婧"))
        assertRows(
            "the word typed" to { assertEquals("徐婧", ask!!.word) },
            "read as each character is, tones and all" to { assertEquals("xu jing", ask!!.pinyin) },
            "what voice heard there" to { assertEquals("徐静", ask!!.heard) },
            "voice's own replacing asked, at the place, with the word added" to { assertEquals(listOf("我找徐静@2:徐静>徐婧(xu jing)"), words.taken) },
            "no hint: a word" to { assertFalse(ask!!.hint) },
        )
    }

    private fun asked(ask: VoiceEdits.Ask?) = ask.also { assertNotNull("asked", it) }

    @Test
    @Uat("VO-47")
    fun `a gap filled and not reported, voice going on after it, the next change is asked about`() {
        val e = edits()
        e.said("我找徐静", "我找徐静")
        repeat(2) { e.backspace() }
        // typed while a talk went on: the app reports it not
        e.committed("徐婧", typed = true)
        e.said("。", "我找徐婧。")
        repeat(3) { e.backspace() }
        e.committed("徐靖", typed = true)
        val reply = e.putIn("徐靖")
        assertRows(
            "followed on" to { assertTrue(reply.open) },
            "asked: the earlier commit no report waited for" to { assertEquals("徐靖", reply.ask?.let { e.confirm("我找徐靖", "") }?.word) },
        )
    }

    @Test
    @Uat("VO-41")
    fun `the sentence's mark deleted with the word, the word typed again is asked about (review S5)`() {
        val e = edits()
        e.said("我找徐静。", "我找徐静。")
        repeat(3) { e.backspace() }
        e.committed("徐婧", typed = true)
        val reply = e.putIn("徐婧")
        assertRows(
            "judged once the Chinese is typed again, the mark not yet" to { assertTrue(reply.open) },
            "asked" to { assertEquals("徐婧", e.confirm("我找徐婧", "")?.word) },
            "the mark typed after: a mark, the window over (review N14)" to {
                e.committed("。", typed = true)
                assertFalse(e.open)
            },
        )
    }

    @Test
    @Uat("VO-43", "VO-48")
    fun `what is not asked about`() = assertRows(
        "a tone not the same (Q2: 许 xu3 for 徐 xu2)" to { assertNull(edits().retype("我找许静", 2, "徐静")) },
        "typed as voice wrote it: nothing changed" to { assertNull(edits().retype("我找徐静", 2, "徐静")) },
        "a character not Chinese: the window over (review N14)" to {
            val e = edits().apply {
                said("我找徐静", "我找徐静")
                repeat(2) { backspace() }
                committed("徐A", typed = true)
            }
            assertFalse(e.open)
        },
        "one character typed again (Q3), with no word around it voice would take" to {
            assertNull(edits(words { refused = setOf("徐婧", "找徐婧", "婧了") }).retype("我找徐静", 1, "婧"))
        },
        "a word added by hand already" to {
            assertNull(edits(words { added = setOf("徐婧") }).retype("我找徐静", 2, "徐婧"))
        },
        "a word blocked in it" to {
            assertNull(edits(words { blocked = setOf("婧") }).retype("我找徐静", 2, "徐婧"))
        },
        "a word voice would not put in (a common word it leaves)" to {
            assertNull(edits(words { refused = setOf("徐婧") }).retype("我找徐静", 2, "徐婧"))
        },
        "a polyphone kept beside the changed one, the dictionary not settling it: its reading not known" to { assertNull(edits().retype("长长", 2, "掌长")) },
    )

    @Test
    @Uat("VO-42", "VO-52")
    fun `asked once in a window, again in the next (Q6)`() {
        val e = edits()
        assertRows(
            "asked" to { asked(e.retype("我找徐静", 2, "徐婧")) },
            "the same word in the same window: not again" to {
                e.said("，徐静", "我找徐婧，徐静")
                repeat(2) { e.backspace() }
                e.committed("徐婧", typed = true)
                assertNull(e.putIn("徐婧").ask)
            },
            "a talk of its own, another window: asked again" to { asked(e.retype("找徐静", 2, "徐婧")) },
        )
    }

    @Test
    @Uat("VO-43")
    fun `the word to ask about`() = assertRows(
        "one character typed again, a learned word over it: that word (§2.4 a)" to {
            assertEquals("徐婧", edits(words { learned = setOf("徐婧") }).retype("我找徐静", 1, "婧")?.word)
        },
        "a learned word added since: not asked (the hint aside)" to {
            val mine = words {
                learned = setOf("徐婧")
                added = setOf("徐婧")
            }
            assertEquals(null, edits(mine).retype("我找徐静", 1, "婧")?.word?.takeIf { it.isNotEmpty() })
        },
        "two to four typed, one of them kept: all typed (§2.4 b)" to { assertEquals("zhang gui", edits().retype("找长柜", 2, "掌柜")?.pinyin) },
        "two to four typed, the changed ones first (§2.4 b)" to { assertEquals("公式", edits().retype("我找攻势", 3, "找公式")?.word) },
        "the changed ones refused: all typed" to {
            assertEquals("找公式", edits(words { refused = setOf("公式") }).retype("我找攻势", 3, "找公式")?.word)
        },
        "the dictionary's reading settles a polyphone kept beside the changed one" to {
            assertEquals("zhang zhang", edits(words { dictionary["掌长"] = "zhang zhang" }).retype("长长", 2, "掌长")?.pinyin)
        },
        "traditional typed is read simplified" to { assertEquals("徐婧", edits().retype("我找徐靜", 2, "徐婧")?.word) },
    )

    @Test
    @Uat("VO-51")
    fun `one character typed again is told once how to have it remembered (Q3)`() {
        val ask = edits().retype("我找徐静", 1, "婧")
        assertRows(
            "a hint, a word around it voice would take" to { assertTrue(ask!!.hint) },
            "no word: nothing to add" to { assertEquals("", ask!!.word) },
        )
    }

    @Test
    @Uat("VO-43")
    fun `typed by what is no word typed, the window ends (Q5)`() {
        val e = edits()
        e.said("我找徐静", "我找徐静")
        repeat(2) { e.backspace() }
        e.committed("徐婧", typed = false)
        assertRows(
            "a table's or a prediction's commit: not followed" to { assertFalse(e.open) },
            "its report: nothing" to { assertNull(e.putIn("徐婧").ask) },
        )
    }

    @Test
    @Uat("VO-49")
    fun `the window ends where what happened is not sure`() = assertRows(
        "something else put in (punctuation, an emoji, a paste)" to {
            val e = edits()
            e.said("我找徐静", "我找徐静")
            assertFalse(e.putIn("，").open)
            assertFalse(e.open)
        },
        "more typed into the gap than was deleted" to {
            val e = edits()
            e.said("我找徐静", "我找徐静")
            e.backspace()
            e.committed("婧了", typed = true)
            assertFalse(e.open)
        },
        "typed with nothing deleted" to {
            val e = edits()
            e.said("我找徐静", "我找徐静")
            e.committed("了", typed = true)
            assertFalse(e.open)
        },
        "Backspace past voice's start" to {
            val e = edits()
            e.said("徐静", "徐静")
            repeat(3) { e.backspace() }
            assertFalse(e.open)
        },
        "the field read as the prompt is to show is not as followed (review M6)" to {
            val e = edits()
            e.said("我找徐静", "我找徐静")
            repeat(2) { e.backspace() }
            e.committed("徐婧", typed = true)
            asked(e.putIn("徐婧").ask)
            assertNull(e.confirm("我找徐静", ""))
            assertFalse(e.open)
        },
        "the field cleared (a message sent)" to {
            val e = edits()
            e.said("我找徐静", "我找徐静")
            e.context("我找徐静", "", selected = false, editorSaid = true, cleared = true)
            assertFalse(e.open)
        },
        "the cursor somewhere the text followed cannot say" to {
            val e = edits()
            e.said("我找徐静", "我找徐静")
            e.context("别的", "", selected = false, editorSaid = true, cleared = false)
            assertFalse(e.open)
        },
    )

    @Test
    @Uat("VO-49")
    fun `the anchor not read as voice's text waits for a context (review R6)`() = assertRows(
        "a Backspace while waiting: not counted, the window ends" to {
            val e = edits()
            e.said("我找徐静", "我找")
            e.backspace()
            assertFalse(e.open)
        },
        "a context that finds it: followed from there" to {
            val e = edits()
            e.said("我找徐静", null)
            e.context("你好我找徐静", "", selected = false, editorSaid = true, cleared = false)
            repeat(2) { e.backspace() }
            e.committed("徐婧", typed = true)
            assertEquals("徐婧", e.putIn("徐婧").ask?.word)
        },
        "a context that finds a stretch deleted while waiting: not counted" to {
            val e = edits()
            e.said("我找徐静", null)
            e.context("我找", "", selected = false, editorSaid = true, cleared = false)
            assertFalse(e.open)
        },
    )

    @Test
    @Uat("VO-49")
    fun `edits made other ways than Backspace at the end`() = assertRows(
        "the cursor put in the middle, Backspace there" to {
            val e = edits()
            e.said("我找徐静了", "我找徐静了")
            e.context("我找徐静", "了", selected = false, editorSaid = true, cleared = false)
            repeat(2) { e.backspace() }
            e.committed("徐婧", typed = true)
            assertEquals("徐婧", e.putIn("徐婧").ask?.word)
            asked(e.confirm("我找徐婧", "了"))
        },
        "a stretch selected and typed over" to {
            val e = edits()
            e.said("我找徐静了", "我找徐静了")
            e.context("我找", "了", selected = true, editorSaid = true, cleared = false)
            e.committed("徐婧", typed = true)
            assertEquals("徐婧", e.putIn("徐婧").ask?.word)
        },
        "a stretch selected, the cursor put back: nothing deleted" to {
            val e = edits()
            e.said("我找徐静", "我找徐静")
            e.context("我找", "", selected = true, editorSaid = true, cleared = false)
            e.context("我找徐静", "", selected = false, editorSaid = true, cleared = false)
            assertTrue(e.open)
            repeat(2) { e.backspace() }
            e.committed("徐婧", typed = true)
            assertEquals("徐婧", e.putIn("徐婧").ask?.word)
        },
        "a stretch deleted at once (a long press, a cut), then typed" to {
            val e = edits()
            e.said("我找徐静了", "我找徐静了")
            e.context("我找", "了", selected = false, editorSaid = true, cleared = false)
            e.committed("徐婧", typed = true)
            assertEquals("徐婧", e.putIn("徐婧").ask?.word)
        },
        "typed a character at a time" to {
            val e = edits()
            e.said("我找徐静", "我找徐静")
            repeat(2) { e.backspace() }
            e.committed("徐", typed = true)
            assertNull(e.putIn("徐").ask)
            e.committed("婧", typed = true)
            assertEquals("徐婧", e.putIn("婧").ask?.word)
        },
        "a typo of the retyping taken back with Backspace" to {
            val e = edits()
            e.said("我找徐静", "我找徐静")
            repeat(2) { e.backspace() }
            e.committed("许", typed = true)
            e.putIn("许")
            e.backspace()
            e.committed("徐婧", typed = true)
            assertEquals("徐婧", e.putIn("徐婧").ask?.word)
        },
        "two talks, the second going on at the first's end: one window" to {
            val e = edits()
            e.said("我找", "我找")
            e.said("徐静", "我找徐静")
            repeat(2) { e.backspace() }
            e.committed("徐婧", typed = true)
            assertEquals("徐静", e.putIn("徐婧").ask?.heard)
        },
    )

    @Test
    @Uat("VO-49")
    fun `an emoji's Backspace is not counted`() = assertRows(
        "an emoji at the cursor: its Backspace waits, the next one ends the window" to {
            val e = edits()
            e.said("我找徐静😀", "我找徐静😀")
            // the steps of one scenario, in order: each needs the one before (R26 F8499)
            e.backspace()
            assertTrue("still open, waiting", e.open)
            e.backspace()
            assertFalse("the next Backspace, not knowing where it is: over", e.open)
        },
        // R26 F8293: any emoji in the window made every Backspace end it
        "an emoji elsewhere in the window: a Backspace over Chinese is counted" to {
            val w = edits()
            w.said("😀我找徐静", "😀我找徐静")
            repeat(2) { w.backspace() }
            w.committed("徐婧", typed = true)
            assertEquals("徐静", w.putIn("徐婧").ask?.heard)
        },
        // R26 F8439: the unit a Backspace takes at a gap is the one before the gap, not the cursor's
        "an emoji before a gap: its Backspace waits, the next one ends the window" to {
            val g = edits()
            g.said("我😀找", "我😀找")
            // in order: 找 taken (a gap), then the emoji before the gap, then one more
            g.backspace()
            g.backspace()
            assertTrue("still open, waiting", g.open)
            g.backspace()
            assertFalse("the next Backspace, not knowing where it is: over", g.open)
        },
    )

    @Test
    @Uat("VO-44")
    fun `asked again at the same place, the word it adds takes the first's place (review M4)`() {
        val e = edits()
        val first = e.retype("我找徐静", 2, "徐婧")!!
        e.remembered(first)
        repeat(2) { e.backspace() }
        e.committed("徐靖", typed = true)
        val second = e.putIn("徐靖").ask?.let { e.confirm("我找徐靖", "") }
        assertRows(
            "the second asked" to { assertEquals("徐靖", second?.word) },
            "replacing the first" to { assertEquals("徐婧", second?.replaces) },
            "the first undone: nothing to replace" to {
                val f = edits()
                f.remembered(asked(f.retype("我找徐静", 2, "徐婧"))!!)
                f.forgot("徐婧")
                repeat(2) { f.backspace() }
                f.committed("徐靖", typed = true)
                assertNull(f.putIn("徐靖").ask?.replaces)
            },
        )
    }

    @Test
    @Uat("VO-49")
    fun `only the last characters are kept`() {
        val e = edits()
        val long = "我".repeat(VoiceEdits.MAX_CHARS) + "找徐静"
        assertRows(
            "a talk longer than kept: its end followed" to {
                e.said(long, long)
                repeat(2) { e.backspace() }
                e.committed("徐婧", typed = true)
                assertEquals("徐婧", e.putIn("徐婧").ask?.word)
            },
        )
    }

    @Test
    @Uat("VO-49")
    fun `what the host read is cut at its length`() = assertRows(
        "a read as long as the host reads ends a longer text" to { assertTrue(VoiceEdits.endsWithPart("我".repeat(64), "找" + "我".repeat(64))) },
        "a shorter read is all the field has" to { assertFalse(VoiceEdits.endsWithPart("我".repeat(10), "找" + "我".repeat(10))) },
        "after the cursor the same" to { assertTrue(VoiceEdits.startsWithPart("我".repeat(64), "我".repeat(64) + "找")) },
    )
}
