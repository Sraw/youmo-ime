/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** Voice input's mark sets in one place, so a new tagger mark goes into every set it belongs in. [TAGGED] is checked against the model. */
object VoiceMarks {

    /** polish-tagger.json's `after` labels' characters. */
    const val TAGGED = "，。？！、…：；“”"

    /** What VoiceLive trims at a joint and an end. */
    const val JOINING = "，。？！、；：,.?!;:…"

    /** Not 「.」 (3.5), 「：」 (what follows belongs to it) nor 「…」. */
    const val LIVE_ENDS = "。？！；，、?!;,"

    /** For a cough or a breath (VoiceText), as a regex character class's body. */
    const val PAUSES = "。，、！？.,!?…\\s"

    /** Closes nothing at a stretch's end; not a colon ("如下："), a question's or an exclamation's, which were meant. */
    const val CLOSING = "。.，,、；;"

    /** VoiceEnd's. */
    const val ENDING = "$CLOSING：:？?！!…"

    /** Full width, index for index with what English writes. */
    const val WIDE = "，？！：；"
    const val ASCII = ",?!:;"

    /** No mark is put after one. */
    const val STOPS = ".。？！"

    /** Each of [LIVE_ENDS] among them. */
    val NUMBER_BREAKS = setOf("，", "。", "？", "！", "、", "…", "：", "；", ",", "?", "!", ";")

    /** As a regex character class's body. */
    const val PAUSE_BEFORE = "，、；："
}
