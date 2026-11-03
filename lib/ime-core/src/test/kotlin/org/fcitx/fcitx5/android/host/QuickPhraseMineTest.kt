/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quick input with the user's own entries (design 051-quickphrase §1.2–1.7). */
class QuickPhraseMineTest {

    private val gh = QuickPhraseEntry("gh", "https://github.com/Sraw/youmo-ime/releases/latest")
    private val addr = QuickPhraseEntry("addr", "北京市海淀区\n3 号楼 502")
    private val builtIn = QuickPhraseTable.load(
        listOf(
            object : PkgDataDir {
                override fun read(path: String) = if (path == "data/quickphrase.d/t.mb") "ghost 👻\n".toByteArray() else null
                override fun list(dir: String) = listOf("t.mb")
            },
        ),
    )

    private class Hooks : QuickPhraseHooks {
        var mine = true
        var letters = NineKeyLetters.STANDARD
        var clock = 100L
        val used = mutableListOf<Pair<QuickPhraseEntry, Long>>()
        var added = 0
        override val nineKeys get() = letters
        override val withMine get() = mine
        override fun used(entry: QuickPhraseEntry, at: Long) {
            used += entry to at
        }
        override fun add() {
            added++
        }
        override fun now() = clock
    }

    private val hooks = Hooks()
    private val book = QuickPhraseBook(builtIn, listOf(gh, addr))
    private val rig = TempModeRig(QuickPhraseTable.EMPTY, { null }, hooks = hooks, book = { book })

    private fun open() = rig.press(TempKeys.GRAVE, KeyStates.SUPER)

    private fun send(codePoint: Int) = rig.press(KeySyms.fromUnicode(codePoint))

    private val comments get() = rig.panel.candidates!!.let { l -> (0 until l.size).map { l.candidate(it).comment } }

    @Test
    @Uat("QU-10")
    fun nothingTypedListsMineThenAddWithNothingLit() {
        open()
        assertEquals(listOf(QuickPhraseExcerpt.of(gh.content), "北京市海淀区⏎", "+ Add"), rig.page)
        assertTrue(rig.page[0].endsWith(QuickPhraseExcerpt.ELLIPSIS))
        assertEquals(listOf("gh", "addr", ""), comments)
        assertEquals(-1, rig.panel.candidates!!.cursorIndex)
        assertEquals("Quick Phrase: ", rig.aux)
        assertTrue(rig.panel.quickPhrase)
        // ＋添加 is no entry; the others are
        assertNull(rig.quickPhrase.entryAt(rig.ic, 2))
        assertEquals(addr, rig.quickPhrase.entryAt(rig.ic, 1)!!.entry)
    }

    @Test
    fun spaceWithNothingTypedLeavesAndTypesASpace() {
        open()
        rig.press(KeySyms.SPACE)
        assertEquals(listOf(" "), rig.commits())
        assertFalse(rig.quickPhrase.isActive(rig.ic))
        assertTrue(hooks.used.isEmpty())
    }

    @Test
    fun aDigitWithNothingTypedIsTypedNotPicked() {
        open()
        rig.type("1")
        assertEquals("1", rig.preedit)
        assertEquals(emptyList<String>(), rig.commits())
    }

    @Test
    fun tappingMineCommitsItWholeAsItIsAndPutsItFirst() {
        open()
        rig.quickPhrase.selectCandidate(rig.ic, 1)
        assertEquals(listOf(addr.content), rig.commits())
        assertEquals(listOf(addr.content), rig.asIs)
        assertEquals(listOf(addr to 100L), hooks.used)
        assertEquals(listOf(addr, gh), book.mine)
        // the built-in ones through the filters, as before; their use not kept
        open()
        rig.type("gho")
        rig.press(KeySyms.SPACE)
        assertEquals(listOf(addr.content, "👻"), rig.commits())
        assertEquals(listOf(addr.content), rig.asIs)
        assertEquals(1, hooks.used.size)
    }

    @Test
    @Uat("QU-13")
    fun inAPasswordFieldMineCommitsButItsUseIsNotKept() {
        rig.ic.capabilities = Capability.PASSWORD
        open()
        rig.quickPhrase.selectCandidate(rig.ic, 1)
        assertEquals(listOf(addr.content), rig.commits())
        assertTrue(hooks.used.isEmpty())
        assertEquals(listOf(gh, addr), book.mine)
    }

    // review L3: an entry deleted from its card goes from the list at once, what is typed kept
    @Test
    @Uat("QU-17")
    fun refreshedAfterAReloadTheListIsTheNewBooks() {
        open()
        rig.quickPhrase.reload(QuickPhraseBook(builtIn, listOf(gh)))
        assertEquals(listOf(QuickPhraseExcerpt.of(gh.content), "北京市海淀区⏎", "+ Add"), rig.page)
        rig.quickPhrase.refresh(rig.ic)
        assertEquals(listOf(QuickPhraseExcerpt.of(gh.content), "+ Add"), rig.page)
        rig.type("g")
        rig.quickPhrase.reload(QuickPhraseBook(builtIn))
        rig.quickPhrase.refresh(rig.ic)
        assertEquals("g", rig.preedit)
        assertEquals(listOf("ghost"), comments)
        // off, nothing is shown
        rig.press(KeySyms.ESCAPE)
        rig.quickPhrase.refresh(rig.ic)
        assertFalse(rig.quickPhrase.isActive(rig.ic))
        assertEquals(emptyList<String>(), rig.page)
    }

    @Test
    fun addLeavesAndAsksTheApp() {
        open()
        rig.quickPhrase.selectCandidate(rig.ic, 2)
        assertEquals(1, hooks.added)
        assertFalse(rig.quickPhrase.isActive(rig.ic))
        assertEquals(emptyList<String>(), rig.commits())
    }

    @Test
    @Uat("QU-14")
    fun habitsOffShowNoneOfMine() {
        hooks.mine = false
        open()
        assertEquals(emptyList<String>(), rig.page)
        assertEquals("Quick Phrase: ", rig.aux)
        rig.type("gh")
        assertEquals(listOf("👻"), rig.page)
    }

    @Test
    fun noneOfMineYetShowsTheHintAndAdd() {
        val rig = TempModeRig(QuickPhraseTable.EMPTY, { null }, hooks = hooks, book = { QuickPhraseBook(builtIn) })
        rig.press(TempKeys.GRAVE, KeyStates.SUPER)
        assertEquals("Quick Phrase: type a key, or tap + Add for your own", rig.aux)
        assertEquals(listOf("+ Add"), rig.page)
    }

    @Test
    @Uat("QU-11")
    fun theNineKeysFindAKeyByItsLettersShowingDigits() {
        open()
        send(Keyboard.pinyinNineKey('4'))
        send(Keyboard.pinyinNineKey('4'))
        assertEquals("44", rig.preedit)
        assertEquals(listOf("gh", "ghost"), comments)
        // digits typed select nothing; Space commits the first
        rig.press(KeySyms.SPACE)
        assertEquals(listOf(gh.content), rig.commits())
        // the user's letters: 4 no longer has g
        hooks.letters = NineKeyLetters.parse("abcg,def,hi,jkl,mno,pqrs,tuv,wxyz")
        open()
        send(Keyboard.pinyinNineKey('2'))
        send(Keyboard.pinyinNineKey('4'))
        assertEquals(listOf("gh", "ghost"), comments)
    }

    @Test
    @Uat("QU-11")
    fun the14KeysFindByTheWholeKeyAndBackspaceKeepsStep() {
        open()
        send(Keyboard.tapKey(g14Code('g'), 0.9f))
        send(Keyboard.tapKey(g14Code('g'), 0.1f))
        assertEquals("hg", rig.preedit)
        assertEquals(listOf("gh", "ghost"), comments)
        rig.press(KeySyms.BACKSPACE)
        rig.type("x")
        assertEquals("hx", rig.preedit)
        assertEquals(emptyList<String>(), rig.page)
        rig.press(KeySyms.BACKSPACE)
        rig.type("h")
        assertEquals(listOf("gh", "ghost"), comments)
        // the cursor moved back: Delete takes the key after it
        rig.press(TempKeys.HOME)
        rig.press(KeySyms.DELETE)
        assertEquals("h", rig.preedit)
        assertEquals(emptyList<String>(), rig.page)
    }

    @Test
    fun typingCaseAsideFindsMine() {
        open()
        rig.type("ADD")
        assertEquals(listOf("北京市海淀区⏎"), rig.page)
        rig.press(KeySyms.SPACE)
        assertEquals(listOf(addr.content), rig.asIs)
    }
}
