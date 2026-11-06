/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What one hold shows and types as streaming recognition goes (060-voice-tagger.md §4.2, §5.1):
 * the segments the recognizer's endpoints closed, each its chosen text ([VoiceChoice]), then the
 * one being said, joined as the tagger was measured on (voice-eval `fusion_exp.py`'s `joined`: a
 * segment ending in no mark of its own gets a 「，」, except between two English words, which keep
 * a space), polished as it streams ([polish]; none with 语音整理 off), then cleaned
 * ([VoiceText.clean]: no spaces in Chinese, numbers in digits). The field's end mark ([VoiceEnd]) is
 * the caller's. The rules come after the tagger: it was trained and measured on the recognizer's text.
 *
 * Not thread-safe: one thread, the recognizer's, as the tagger runs in [heard] and [closed].
 */
class VoiceLive(private val polish: VoicePolishStream? = null) {

    private var said = ""
    private var hearing = ""

    /** The segments closed and the one being said, as the recognizer wrote them, joined. */
    val raw: String
        get() = join(said, hearing)

    /** The segment being said is [raw] so far; what to show. */
    fun heard(raw: String): String {
        hearing = raw.trim()
        return shown()
    }

    /**
     * The segment being said closed at an endpoint, [raw] its chosen text; what to show. A segment
     * of a sound only ([VoiceText.clean]'s 「嗯。」, 「咳咳。」) is left out: the recognizer writes one
     * for a noise as often as for a word.
     */
    fun closed(raw: String): String {
        hearing = ""
        if (VoiceText.clean(raw) != null) said = join(said, raw.trim())
        return shown()
    }

    /** What was said, polished and cleaned, the segment being said taken as it is; null if nothing worth typing. */
    fun finish(): String? {
        val text = raw
        return VoiceText.clean(polish?.finish(text) ?: text)
    }

    private fun shown(): String {
        val text = raw
        val shown = polish?.update(text)?.let { it.polished + it.rest } ?: text
        return VoiceText.clean(shown).orEmpty()
    }

    companion object {
        private const val MARKS = "，。？！、；：,.?!;:…"

        /** [after] following [before], as the tagger's input had segments one after another. */
        fun join(before: String, after: String): String = when {
            before.isEmpty() -> after
            after.isEmpty() -> before
            // the recognizer's context outlives its endpoint: the next segment may start with the
            // mark that ends this one, its own say over where this one ends (，， and ，。 otherwise)
            after.first() in MARKS -> before.trimEnd { it in MARKS } + after
            before.last() in MARKS -> before + after
            before.last().isAsciiWord() && after.first().isAsciiWord() -> "$before $after"
            else -> "$before，$after"
        }

        private fun Char.isAsciiWord() = this < '\u0080' && isLetterOrDigit()
    }
}
