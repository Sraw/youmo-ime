/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.CHAIZI_DATA
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.EMOJI_DATA
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.READS_PINYIN_DATA
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.TABLES
import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.session.Session
import java.util.concurrent.Executor

/** What the field's last context said learning leaves out ([Action.Context.learnFlags][org.fcitx.fcitx5.android.engine.session.Action.Context.learnFlags]): one per host, shared with every [Engines] it makes so remade engines keep it without a second copy. */
class FieldFlags {
    var value = 0
}

/** The field as [Engines] hears of it: the text before the cursor told to keyboards, [flags] kept for later ones, and the starting input method's data warmed ahead on [warmUps]. Once per [Engines]; on its thread. */
internal class FieldContext(
    private val assets: EngineAssets,
    private val warmUps: Executor,
    private val settings: () -> EngineSettings,
    private val learner: LearningFacade,
    private val english: EnglishLexicon,
    private val tables: TableRegistry,
    private val keyboards: Map<String, Keyboard>,
    private val sessions: Map<String, Session>,
    private val pairs: () -> Map<String, KeyboardPair>,
    val flags: FieldFlags,
) {
    // English's data is read once and kept: warmed up once
    private var englishWarmedUp = false

    // pinyin's data and word index, read once in the background
    private var pinyinWarmedUp = false

    // aside sources (EMOJI_DATA, CHAIZI_DATA, READINGS_ASIDE) already warmed
    private val asideWarmedUp = HashSet<String>()

    /** See [Engines.context]. */
    fun context(
        im: String,
        before: String,
        learning: Boolean,
        cleared: Boolean,
        learnFlags: Int,
        started: Boolean,
        after: String?,
        selected: Boolean,
        said: Boolean,
    ): Boolean {
        // first, so engines remade after a close keep this field's flags even if what follows throws
        flags.value = learnFlags
        // a new typing day's counts are made here, not on a key (HabitCounts.settle)
        learner.settle()
        if (im in READS_PINYIN_DATA || im in TABLES) warmUpPinyin(im)
        // 中英混输 looks up English words at the first key (LatinSync): index them before it
        if (im == PINYIN && !englishWarmedUp && latinAsksEnglish()) warmUpEnglish()
        // code the user's words before the first key, which would otherwise start it
        if (im in TABLES) tables.prepare(im)
        var offers = false
        val pairs = pairs()
        // pinyin's and 双拼's through their pairs, below
        for ((name, keyboard) in keyboards.asSequence().filter { (n, _) -> pairs.values.none { it.holds(n) } }) {
            // only [im]'s: another's candidates would stay shown where nothing resets them
            val shows = keyboard.context(before, learning, cleared, learnFlags, started, after, selected, offers = name == im, said = said)
            if (name == im && shows && sessions[name] is EnglishSession) offers = true
        }
        for ((name, pair) in pairs) pair.context(before, learning, cleared, learnFlags, started, after, selected, offers = im == name, said = said)
        return offers
    }

    // English's keys and word index take hundreds of ms on a phone: built off-thread before the first key needs them
    private fun latinAsksEnglish() = settings().latinWords && learner.useHabits && english.user().words.isNotEmpty()

    /** English's data, its warm-up started the first time. */
    fun warmUpEnglish(): EnglishData? = assets.englishData.also { data ->
        if (data != null && !englishWarmedUp) {
            englishWarmedUp = true
            warmUps.execute(WarmUp(listOf(WarmUp.Step(WarmUp.ENGLISH, data::warmUp)), quiet = false))
        }
    }

    // for input methods reading pinyin data (incl. tables via TableLanguage): read and index its 270k words off
    // the fcitx thread before the first key, so English-only users neither wait nor hold them. Failures are
    // dropped quietly (lazies unset): the session rereads on the fcitx thread, which reports to the addon.
    // Plus the aside sources [im] shows; parts and readings only in full pinyin
    private fun warmUpPinyin(im: String) {
        val settings = settings()
        val aside = HashSet<String>()
        if (settings.emoji) aside += EMOJI_DATA
        if (im == PINYIN && settings.chaizi) aside += CHAIZI_DATA
        if (im == PINYIN) aside += READINGS_ASIDE
        aside -= asideWarmedUp
        if (pinyinWarmedUp && aside.isEmpty()) return
        pinyinWarmedUp = true
        asideWarmedUp += aside
        val steps = ArrayList<WarmUp.Step>()
        steps += WarmUp.Step(WarmUp.PINYIN_WORDS) { assets.pinyinData.wordIndex }
        steps += WarmUp.Step(WarmUp.CHAR_SYLLABLES) { assets.charSyllables }
        if (EMOJI_DATA in aside) steps += WarmUp.Step(WarmUp.EMOJI) { assets.warmUpAside(emoji = true, chaizi = false, charReadings = false) }
        if (CHAIZI_DATA in aside) steps += WarmUp.Step(WarmUp.CHAIZI) { assets.warmUpAside(emoji = false, chaizi = true, charReadings = false) }
        if (READINGS_ASIDE in aside) steps += WarmUp.Step(WarmUp.CHAR_READINGS) { assets.warmUpAside(emoji = false, chaizi = false, charReadings = true) }
        warmUps.execute(WarmUp(steps, quiet = true))
    }

    private companion object {
        // an [asideWarmedUp] key beside the data file names, not the step's name (WarmUp.CHAR_READINGS)
        const val READINGS_ASIDE = "char readings"
    }
}
