/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.CHAIZI_DATA
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.EMOJI_DATA
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.HANDWRITING
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.READS_PINYIN_DATA
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.TABLES
import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.session.Session
import java.util.concurrent.Executor

/**
 * What the field's last context said learning leaves out ([Action.Context.learnFlags][org.fcitx.fcitx5.android.engine.session.Action.Context.learnFlags]),
 * for the engines' sessions made after it. One for the host: given to each [Engines] it makes, so
 * one made anew in the same field (after a close) has them (review-int N1), with no second copy to
 * fall out of step (review B 3.8).
 */
class FieldFlags {
    var value = 0
}

/**
 * The field the user types in, as [Engines] hears of it: its text before the cursor told to the
 * keyboards made ([context]), its [flags] kept for those made after, and what the input method it
 * starts in will want read ahead of its first key, on [warmUps]. Once for each [Engines]: one made
 * anew reads them again. On the engines' thread.
 */
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
    // English's data is read once and kept: warmed up once, however often its session is made
    private var englishWarmedUp = false

    // pinyin's data and word index, which the sessions of READS_PINYIN_DATA and the tables read as they
    // are made: read once, in the background
    private var pinyinWarmedUp = false

    // of what is placed beside the candidates (EMOJI_DATA, CHAIZI_DATA, READINGS_ASIDE), what was warmed up
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
        // first, as the host kept its own copy before it told the engines: engines made after a close
        // start with this field's flags even if what follows throws
        flags.value = learnFlags
        // the counts a new typing day changed, made anew here rather than on a key (HabitCounts.settle)
        learner.settle()
        if (im in READS_PINYIN_DATA || im in TABLES) warmUpPinyin(im)
        // 中英混输 asks English's words of each English word learned at the first key (LatinSync): indexed before it
        if (im == PINYIN && !englishWarmedUp && latinAsksEnglish()) warmUpEnglish()
        // the user's words coded for the table before its first key, which would otherwise start it
        if (im in TABLES) tables.prepare(im)
        var offers = false
        val pairs = pairs()
        // pinyin's and 双拼's through their pairs, below
        for ((name, keyboard) in keyboards.asSequence().filter { (n, _) -> pairs.values.none { it.holds(n) } }) {
            // only [im]'s: another's candidates would stay on show where nothing resets them, its contexts ignored
            val shows = keyboard.context(before, learning, cleared, learnFlags, started, after, selected, offers = name == im, said = said)
            if (name == im && shows && sessions[name] is EnglishSession) offers = true
        }
        for ((name, pair) in pairs) pair.context(before, learning, cleared, learnFlags, started, after, selected, offers = im == name, said = said)
        return offers
    }

    // English's data, its keys and word index (some hundreds of milliseconds on a phone) built off
    // this thread before the first key wants them; a key that comes sooner waits for them, as it would anyway
    private fun latinAsksEnglish() = settings().latinWords && learner.useHabits && english.user().words.isNotEmpty()

    /** English's data, its warm-up started the first time. */
    fun warmUpEnglish(): EnglishData? = assets.englishData.also { data ->
        if (data != null && !englishWarmedUp) {
            englishWarmedUp = true
            warmUps.execute(WarmUp(listOf(WarmUp.Step(WarmUp.ENGLISH, data::warmUp)), quiet = false))
        }
    }

    // told as a field starts in an input method that reads pinyin's data (READS_PINYIN_DATA, a table,
    // which ranks and learns by them: TableLanguage), before its first key: the data read and its 270
    // thousand words indexed meanwhile, off the fcitx thread; one who types only English neither waits
    // for them nor holds them. A key that comes sooner waits for them, and a switch from English to one
    // of those reads them on the fcitx thread as it makes the session (activate's Reset). Whatever
    // reading them throws is dropped, the lazies left unset (WarmUp, quiet): the session reads them again
    // as it is made, on the fcitx thread, which hands it to the addon (EngineBridge.Unloadable).
    // Beside it only what [im] shows as the settings have it, held from then on: no emoji in handwriting,
    // characters by their parts and their readings (笔画查字's) only in full pinyin
    private fun warmUpPinyin(im: String) {
        val settings = settings()
        val aside = HashSet<String>()
        if (settings.emoji && im != HANDWRITING) aside += EMOJI_DATA
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
        // what charReading reads off pinyin's dictionary, warmed up as the data files are: a key of
        // [asideWarmedUp] beside the data files' names, not the step's name (WarmUp.CHAR_READINGS)
        const val READINGS_ASIDE = "char readings"
    }
}
