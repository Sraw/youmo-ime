/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.english.EnglishDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishDecoderTest
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.english.GlideDecoderTest.Companion.glided
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.EnginesFixture
import org.fcitx.fcitx5.android.engine.runtime.EnginesHost
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联想's × on real sessions (review 061-small M1): the predictions put away, nothing else. Escape, as it
 * was, reset the session: English lost its own space and a correction's undo, pinyin what was committed.
 */
class DropPredictionsEnginesTest : EnginesFixture() {

    private val clock = VirtualHostClock(epochMillis = 1_800_000_000_000)
    private val log = mutableListOf<HostEvent>()

    private fun core(im: String): HostCore {
        // each row's own events: a row before that failed leaves its own behind (review F8120)
        log.clear()
        // what follows the and hello: predictions, which a model offers by its pairs alone
        english = EnglishDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram(EnglishText.START, -9f, -0.5f)
            .unigram("the", -1.3f, 0f)
            .unigram("you", -1.8f, 0f)
            .unigram("hello", -3f, 0f)
            .unigram("fed", -3f, 0f)
            .bigram("the", "you", -0.5f, 0f)
            .bigram("hello", "you", -0.5f, 0f)
            .build().toByteArray()
        val engines = Engines(::load, null, warmUps = inPlace, clock = clock)
        val core = HostCore(EnginesHost(engines), clock, Profile.load(null, InputMethodCatalog.LISTED.map { it.name }.toSet(), "Default"), firstRun = true)
        core.events += HostEvents { log += it }
        core.activate(1)
        core.setCapabilities(Capability.PREEDIT or Capability.CLIENT_UNFOCUS_COMMIT)
        core.focus(true)
        core.setCurrentInputMethod(im)
        // the field's text before, as the app tells it: English offers nothing to follow text it knows nothing of
        engines.context(im, "I ")
        return core
    }

    private fun HostCore.type(text: String) = text.forEach { sendKey(Key(it.code), virtual = true) }

    private fun candidates() = log.filterIsInstance<HostEvent.Candidates>().last()

    // the × tapped on predictions shown: the list empty, no longer 联想's
    private fun HostCore.dropShown() {
        assertTrue("predictions shown: ${candidates()}", candidates().predicting && candidates().candidates.isNotEmpty())
        assertTrue(dropPredictions())
        assertEquals(HostEvent.Candidates.EMPTY, candidates())
        log.clear()
    }

    @Test
    fun theCrossPutsThePredictionsAwayAndKeepsTheSession() {
        assertRows(
            "English, a correction (teh → the): × empties the list, and Backspace still takes the correction back" to {
                val core = core(InputMethodCatalog.ENGLISH)
                core.type("fed teh ")
                assertEquals("the ", log.filterIsInstance<HostEvent.Commit>().last().text)
                core.dropShown()
                core.sendKey(Key(KeySyms.BACKSPACE), virtual = true)
                // the + its space deleted, teh typed again: what the correction's undo does (a reset forgets it, the app deletes a space)
                assertEquals(HostEvent.DeleteSurrounding(4, 0), log.filterIsInstance<HostEvent.DeleteSurrounding>().singleOrNull())
                assertEquals("teh", log.filterIsInstance<HostEvent.ClientPreedit>().last().text.toString())
                assertFalse(candidates().predicting)
            },
            "English, hello and its own space: after ×, a comma still takes the space's place (hello, not hello ,)" to {
                val core = core(InputMethodCatalog.ENGLISH)
                core.type("hello ")
                core.dropShown()
                core.type(",")
                assertEquals(HostEvent.DeleteSurrounding(1, 0), log.filterIsInstance<HostEvent.DeleteSurrounding>().singleOrNull())
                assertEquals(", ", log.filterIsInstance<HostEvent.Commit>().last().text)
            },
            "English, a glided word (with the space before it): after ×, Backspace still takes it whole" to {
                val s = EnglishSession(EnglishDecoderTest.data()).apply { apply(Action.Context("Hi,")) }
                val glided = s.glided("the").commit
                assertEquals(" the ", glided)
                s.apply(Action.DropPredictions)
                assertEquals(glided.length, s.apply(Action.Backspace).deleteBefore)
            },
            "pinyin, 好 committed: after ×, ni follows it still (拟 first by 好拟, 你 first with nothing before)" to {
                val core = core(InputMethodCatalog.PINYIN)
                core.type("hao")
                assertTrue(core.select(candidates().candidates.indexOfFirst { it.text == "好" }, "好"))
                core.dropShown()
                core.type("ni")
                assertEquals("拟", candidates().candidates.first().text)
            },
        )
    }
}
