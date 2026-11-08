/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.EnginesFixture
import org.fcitx.fcitx5.android.engine.runtime.EnginesHost
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host's side of a long press's acts (KotlinHost.learning hands the engines
 * [HostCore.actOnCandidate]): under 繁體, a block of 国 on the stroke lookup's second page takes
 * 國 too, each found by its index of all and checked by its text; nothing else (review R3).
 */
class HostCoreAlikeBlockTest : EnginesFixture() {

    private val fillers = "甲乙丙丁戊己庚辛壬癸".map { it.toString() }

    init {
        pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .apply { (fillers + "国" + "國").forEachIndexed { i, c -> unigram(c, -2f - i * 0.1f, 0f).entry(c, intArrayOf(Syllables.id("guo"))) } }
            .build().toByteArray()
        strokes = Strokes.read(("...\n" + (fillers + "国" + "國").joinToString("") { "$it\tzpsss\n" }).reader().buffered(), "stroke").build().toByteArray()
    }

    private val clock = VirtualHostClock(epochMillis = 1_800_000_000_000)

    private val log = mutableListOf<HostEvent>()

    private fun core(engines: Engines): HostCore {
        val converter = ChineseConverterTest.converter(characters = mapOf("国" to "國"))
        val modules = ChineseModules(
            ModuleInputs(PunctuationProfile.parse(""), chttransEnabled = setOf(InputMethodCatalog.PINYIN), converter = { converter }),
        )
        val profile = Profile.load(null, InputMethodCatalog.LISTED.map { it.name }.toSet(), "Default")
        val core = HostCore(EnginesHost(engines), clock, profile, firstRun = true, modules = modules::build)
        core.events += HostEvents { log += it }
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.focus(true)
        "uzps".forEach { core.sendKey(Key(it.code), virtual = true) }
        return core
    }

    private fun Engines.underCode() =
        (learning(null, LearningOp.ListItems(LearningOp.Listed.TABLE_BLOCKS)) as LearningResult.Listed).items.map { it.text }

    @Test
    fun `a block on the second page takes the one shown alike and nothing else`() {
        val engines = Engines(::load, MemoryFiles(), warmUps = inPlace, clock = clock)
        val core = core(engines)
        core.offsetCandidatePage(1)
        val all = core.candidates(0, 30).map { it.text }
        val at = all.indexOf("國")
        // 国 shown 國, the dictionary's own 國 after it: both past the first page
        assertTrue("$all", at >= 7 && all.lastIndexOf("國") > at)
        val acts = ArrayList<String>()
        val act = { event: Int, index: Int, text: String -> core.actOnCandidate(index, event, text).also { acts += "$index $text $it" } }
        val learning = { op: LearningOp -> engines.learning(InputMethodCatalog.PINYIN, op, core::shownText, act) }
        val menu = learning(LearningOp.Menu(at, "國")) as LearningResult.Menu
        assertEquals("国", menu.text)
        // a text not at the index: nothing done
        assertFalse(core.actOnCandidate(at, EngineEvent.BLOCK, "甲"))
        val done = learning(LearningOp.Block(at, menu.text))
        assertTrue("$done $acts", done is LearningResult.Done)
        val blocked = engines.underCode()
        assertEquals("$acts", setOf("国", "國"), blocked.toSet())
        assertEquals(2, blocked.size)
        engines.close()
    }

    // the menu of a paged bar (pagingMode 1, the page's index: LearningOp.OnPage), as KotlinHost sends it
    @Test
    fun `a paged block takes the one shown alike too`() {
        val engines = Engines(::load, MemoryFiles(), warmUps = inPlace, clock = clock)
        val core = core(engines)
        core.setCandidatePagingMode(1)
        core.offsetCandidatePage(1)
        val all = core.candidates(0, 30).map { it.text }
        val page = log.filterIsInstance<HostEvent.Paged>().last().candidates.map { it.text }
        val onPage = page.indexOf("國")
        assertTrue("$all $page", onPage >= 0 && all.indexOf("國") >= 7)
        val done = engines.learning(InputMethodCatalog.PINYIN, LearningOp.OnPage(LearningOp.Block(onPage, "国")), core::shownText) { event, index, text ->
            core.actOnCandidate(index, event, text)
        }
        assertTrue("$done $page", done is LearningResult.Done)
        assertEquals(listOf("国", "國"), engines.underCode().sorted())
        engines.close()
    }
}
