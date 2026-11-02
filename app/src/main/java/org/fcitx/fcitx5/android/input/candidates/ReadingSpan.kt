/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ReplacementSpan
import org.fcitx.fcitx5.android.input.bar.CandidateBarHeight
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One character of a candidate with its 候选词注音 ([org.fcitx.fcitx5.android.core.CandidateWord.readings]),
 * the reading's text half the character's ([CandidateBarHeight.READING_SCALE]), in [color]: pinyin
 * centred above it, the character widened where the pinyin is wider; zhuyin upright to its right, the
 * symbols shrunk to fit the character's height, ˊˇˋ beside the last and ˙ over the first, as
 * Taiwan's textbooks print it.
 *
 * A hint's reading (候选词注音 off: a misreading's, a part's, a stroke lookup's; [hintSize] above 0) is
 * pinyin too, but in the line's height: [hintSize] its text size ([CandidateBarHeight.hintSize]), [hintRoom]
 * what the view has above the baseline, so the candidate stays where its neighbours are.
 */
class ReadingSpan(
    private val reading: String,
    private val color: Int,
    private val hintSize: Float = 0f,
    private val hintRoom: Float = 0f
) : ReplacementSpan() {

    private val hint = hintSize > 0f
    private val zhuyin = !hint && isZhuyin(reading)
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun small(paint: Paint, size: Float = if (hint) hintSize else paint.textSize * CandidateBarHeight.READING_SCALE): TextPaint = small.apply {
        set(paint)
        textSize = size
        color = this@ReadingSpan.color
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            // the text's own fonts (a Han character's fallback is taller than the paint's): a line of it plain
            // and one of it read are then the same height
            paint.getFontMetricsInt(text, start, end - start, start, end - start, false, fm)
            // room above for the pinyin where the view lays out by lines (the floating window's)
            if (!zhuyin && !hint) {
                val line = ceil(paint.textSize * CandidateBarHeight.READING_LINE).toInt()
                fm.ascent -= line
                fm.top -= line
            }
        }
        return ceil(width(paint, text, start, end)).toInt()
    }

    private fun width(paint: Paint, text: CharSequence, start: Int, end: Int): Float {
        val w = paint.measureText(text, start, end)
        return if (zhuyin) w + columnWidth(paint) else max(w, small(paint).measureText(reading))
    }

    private fun columnWidth(paint: Paint): Float = small(paint).measureText(COLUMN_SAMPLE) * COLUMN_ROOM

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val w = paint.measureText(text, start, end)
        if (!zhuyin) {
            val size = width(paint, text, start, end)
            canvas.drawText(text, start, end, x + (size - w) / 2, y.toFloat(), paint)
            val p = small(paint)
            // a hint's: on the characters' tops, or lower where the view's top is nearer
            val baseline = if (hint) max(y - paint.textSize * CandidateBarHeight.HAN_TOP - p.descent() - HINT_GAP, y - hintRoom - p.ascent()) else y + paint.ascent() - p.descent()
            canvas.drawText(reading, x + (size - p.measureText(reading)) / 2, baseline, p)
            return
        }
        canvas.drawText(text, start, end, x, y.toFloat(), paint)
        val tone = reading.firstOrNull { it in TONES }
        val neutral = reading.startsWith(NEUTRAL)
        val body = reading.filter { it !in TONES && it != NEUTRAL }
        if (body.isEmpty()) return
        // the column as tall as the character's line, each symbol no larger than half the character
        val faceTop = y + paint.fontMetrics.top
        val faceHeight = paint.fontMetrics.bottom - paint.fontMetrics.top
        val half = paint.textSize * CandidateBarHeight.READING_SCALE
        val p = small(paint, min(half, faceHeight / (body.length + if (neutral) 1 else 0)))
        val step = p.descent() - p.ascent()
        val left = x + w
        var line = faceTop + (faceHeight - step * (body.length + if (neutral) 1 else 0)) / 2 - p.ascent()
        if (neutral) {
            canvas.drawText(NEUTRAL.toString(), left, line, p)
            line += step
        }
        body.forEach { c ->
            canvas.drawText(c.toString(), left, line, p)
            line += step
        }
        if (tone != null) canvas.drawText(tone.toString(), left + p.measureText(body.last().toString()), line - step * TONE_RAISE, p)
    }

    companion object {
        private const val TONES = "ˊˇˋ"
        private const val NEUTRAL = '˙'
        private const val COLUMN_SAMPLE = "ㄅ"

        // a zhuyin column's width, of a symbol's: the symbol and its tone mark beside it, narrower
        private const val COLUMN_ROOM = 1.5f

        // a tone mark beside the last symbol, raised a little off its middle
        private const val TONE_RAISE = 0.75f

        fun isZhuyin(reading: String): Boolean = reading.any { it in 'ㄅ'..'ㄯ' }

        /**
         * [text] with a [ReadingSpan] on each code point [readings] has one for (one per code point; empty for none);
         * hints' readings with [hintSize] above 0, [hintRoom] as the span has them.
         */
        fun annotate(builder: SpannableStringBuilder, text: String, readings: List<String>, color: Int, hintSize: Float = 0f, hintRoom: Float = 0f) {
            val start = builder.length
            builder.append(text)
            if (readings.isEmpty()) return
            var at = 0
            var i = 0
            while (at < text.length) {
                val n = Character.charCount(text.codePointAt(at))
                readings.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                    builder.setSpan(ReadingSpan(it, color, hintSize, hintRoom), start + at, start + at + n, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                at += n
                i++
            }
        }

        // a pixel between a hint and its characters, out of the hint's size, so the bar keeps its height
        private const val HINT_GAP = 1f

        /** A hint's reading size ([CandidateBarHeight.hintSize]) for text in [paint], [baseline] below the view's top. */
        fun hintSize(paint: Paint, baseline: Float, floor: Float): Float {
            val fm = paint.fontMetrics
            val room = baseline - paint.textSize * CandidateBarHeight.HAN_TOP - HINT_GAP
            return CandidateBarHeight.hintSize(paint.textSize, room, (fm.descent - fm.ascent) / paint.textSize, floor)
        }

        /** Whether [readings] are pinyin above the characters, which the candidate's view makes room for. */
        fun above(readings: List<String>): Boolean = readings.any { it.isNotEmpty() } && readings.none(::isZhuyin)
    }
}
