/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * The marks voice input knows, in one place (voice-9): each rule's own set is named here with what it
 * means, so a mark the tagger starts writing is added to every set it belongs in, not to the one
 * copy someone remembered. [TAGGED] is every character the polish tagger's `after` labels write
 * (VoiceMarksTest checks it against the model's table, and that alone: no test places a new mark in the
 * sets); the sets below say which of them each rule treats as a mark. Quotes and 「；：」 are not in all of them (VoiceText's [PAUSES], VoiceLive's
 * [JOINING] has no quotes): kept as they were, a change to them is one to measure on the goldens first.
 */
object VoiceMarks {

    /** every character the tagger's `after` labels are made of (polish-tagger.json's `after`) */
    const val TAGGED = "，。？！、…：；“”"

    /** what VoiceLive trims at a joint and an end: a mark, not text */
    const val JOINING = "，。？！、；：,.?!;:…"

    /** the marks a talk is typed up to as it goes: not 「.」 (3.5, e.g.), 「：」 (what follows belongs to it) nor 「…」 */
    const val LIVE_ENDS = "。？！；，、?!;,"

    /** a pause the recognizer writes for a cough or a breath (VoiceText), as a regex character class's body */
    const val PAUSES = "。，、！？.,!?…\\s"

    /**
     * a full stop and what the recognizer leaves at a stretch's end that closes nothing (a comma); not a
     * colon, which says something follows ("如下："), nor a question's or an exclamation's, which were meant
     */
    const val CLOSING = "。.，,、；;"

    /** every mark a text may end in (VoiceEnd) */
    const val ENDING = "$CLOSING：:？?！!…"

    /** norm's full width marks, and what English writes for each, index for index */
    const val WIDE = "，？！：；"
    const val ASCII = ",?!:;"

    /** a sentence's stop: no mark is put after one */
    const val STOPS = ".。？！"

    /** marks end a number though the tagger put them in one (numeval.py render); each of [LIVE_ENDS] among them */
    val NUMBER_BREAKS = setOf("，", "。", "？", "！", "、", "…", "：", "；", ",", "?", "!", ";")

    /** a pause's mark that goes before another mark, as a regex character class's body */
    const val PAUSE_BEFORE = "，、；："
}
