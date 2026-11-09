/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * A number the polish tagger marked ([VoicePolishTags], its `num` head; 060-voice-tagger.md 数字头)
 * written as one types it, as the [Kind] the tagger gave it. Whether a run of characters is a
 * number at all is the tagger's say, not this: 一个, 十分有趣, 三四个人 are never marked. A run that
 * does not read as its kind is written as it was said.
 *
 * voice-eval's `numeval.py render_run`, but for these:
 * - a time's 半, 刻 and 整 and a bare hour stay characters (8点半, 8点一刻, 7点整, 8点), not 8:30, 7:00;
 *   an hour past 24 or minutes past 59 (八点七十), or 分 with no minutes (八点分), as said;
 * - 万/亿 at the end kept (33万, 3.5亿), where render writes the whole value;
 * - a value of two points (一点二点三) as said; a model's later part with a place by value (三点十二 3.12);
 * - a ratio with neither 比 nor 分之 as said; 百分之百 as said (百 alone reads as no number);
 * - digits with a place among them (一百二十三) read as a value, 123.
 */
object ChineseNumbers {

    /** The tagger's `num` classes, by name as its json lists them. */
    enum class Kind {
        NONE, VALUE, DIGITS, DATE, TIME, MONEY, PERCENT, MODEL, RATIO;

        companion object {
            fun of(name: String): Kind = Kind.valueOf(name.uppercase())
        }
    }

    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '幺' to 1,
    )
    private val SMALL = mapOf('十' to 10L, '百' to 100L, '千' to 1000L)
    private val BIG = mapOf('万' to 10_000L, '亿' to 100_000_000L)

    private const val MINUTES = 60
    private const val HOURS = 24
    private const val PERCENT = "百分之"
    private const val FRACTION = "分之"

    // what follows an hour's 点 and stays as said: 8点, 8点半, 8点整
    private val IN_CHARACTERS = setOf("", "半", "整")

    /** [run] as [kind] writes it; as it was if it does not read so. */
    fun written(run: String, kind: Kind): String = when (kind) {
        Kind.NONE -> null
        // a place among them: the tagger's class wrong, the number read as said (一百二十三 123)
        Kind.DIGITS -> if (run.any { it in SMALL || it in BIG }) value(run) else run.map { c -> DIGITS[c]?.let { "$it" } ?: "$c" }.joinToString("")
        Kind.TIME -> time(run)
        Kind.PERCENT -> value(run.removePrefix(PERCENT))?.let { "$it%" }
        Kind.MODEL -> model(run)
        Kind.RATIO -> ratio(run)
        // a date's digits said one by one (二零二六) read so as a value: with no place, digit by digit
        Kind.VALUE, Kind.DATE, Kind.MONEY -> value(run)
    } ?: run

    /** 十五, 三十三万 (33万), 三点五亿 (3.5亿), 零点五; null if no number. */
    private fun value(s: String): String? {
        val big = s.takeLastWhile { it in BIG }
        val body = s.dropLast(big.length)
        val point = body.indexOf('点')
        if (point < 0) return whole(s)
        val first = integerDigits(body.substring(0, point)) ?: return null
        val fraction = digitByDigit(body.substring(point + 1)) ?: return null
        return "$first.$fraction$big"
    }

    /** 十点十五 10:15, 八点五分 8:05, 八点半, 八点一刻, 八点整 and 八点 in characters; null if no time. */
    private fun time(s: String): String? {
        val point = s.indexOf('点')
        if (point < 0) return value(s)
        val hour = integerDigits(s.substring(0, point))?.takeIf { (it.toLongOrNull() ?: Long.MAX_VALUE) <= HOURS } ?: return null
        val said = s.substring(point + 1)
        if (said in IN_CHARACTERS || said.endsWith("刻")) return "${hour}点$said"
        val m = integerDigits(said.removeSuffix("分"))?.toIntOrNull()?.takeIf { it < MINUTES } ?: return null
        // not %02d, which writes the phone's language's digits (٠٥ in Arabic)
        return "$hour:" + "$m".padStart(2, '0')
    }

    /** 二点一点三 2.1.3, V一点二三 1.23: the first part a value, the rest digit by digit (or a value: 三点十二) */
    private fun model(s: String): String? {
        val parts = s.split('点')
        val first = integerDigits(parts[0]) ?: return null
        val rest = parts.drop(1).map { digitByDigit(it) ?: integerDigits(it) ?: return null }
        return (listOf(first) + rest).joinToString(".")
    }

    /** 四分之三 3/4, 三比二 3:2 */
    private fun ratio(s: String): String? {
        val (sep, out) = when {
            FRACTION in s -> FRACTION to "/"
            '比' in s -> "比" to ":"
            else -> return null
        }
        val a = integerDigits(s.substringBefore(sep)) ?: return null
        val b = integerDigits(s.substringAfter(sep)) ?: return null
        return if (sep == FRACTION) "$b$out$a" else "$a$out$b"
    }

    private fun digitByDigit(s: String): String? =
        if (s.isNotEmpty() && s.all { it in DIGITS }) s.map { DIGITS.getValue(it) }.joinToString("") else null

    /** 三十三万 as 33万, 四百亿 as 400亿 and 三万亿 as 3万亿, as written; 十二万三千 in full. */
    private fun whole(raw: String): String? {
        val value = integerDigits(raw) ?: return null
        val places = raw.takeLastWhile { it in BIG }
        val before = raw.dropLast(places.length)
        if (places.isNotEmpty() && before.isNotEmpty() && before.none { it in BIG }) {
            integerDigits(before)?.let { return "$it$places" }
        }
        return value
    }

    /** The integer [raw] reads as, as digits; null if it does not read as one (万一, 十十, 二十亿万). */
    private fun integerDigits(raw: String): String? {
        if (raw.isEmpty()) return null
        // digits only: 二零零二, 一一七
        if (raw.all { it in DIGITS }) return raw.map { DIGITS.getValue(it) }.joinToString("")
        // a place first is no number, but 十 (十五); 幺 only among digits
        if ('幺' in raw) return null
        if (raw.first() in BIG || (raw.first() in SMALL && raw.first() != '十')) return null
        if (raw.any { it !in DIGITS && it !in SMALL && it !in BIG }) return null
        val reading = Reading()
        return if (raw.all { reading.read(it) }) reading.value()?.toString() else null
    }

    /** An integer read a character at a time, as said: 三十三万六千零五, 一万二 (12000). */
    private class Reading {
        private var total = 0L
        private var section = 0L
        private var pendingDigit = -1L
        private var zero = false
        // the place last read: a digit after it is the next place down, 三千五 3500, but after 零 a one
        private var place = 1L
        private var lastSmall = Long.MAX_VALUE
        private var lastBig = Long.MAX_VALUE

        /** false if [c] makes it no number */
        fun read(c: Char): Boolean = when (c) {
            in DIGITS -> digit(DIGITS.getValue(c).toLong())
            in SMALL -> small(SMALL.getValue(c))
            else -> big(BIG.getValue(c))
        }

        // two digits side by side among places: 二十三四 is no number
        // 零零 too
        private fun digit(d: Long): Boolean {
            if (pendingDigit >= 0 || (d == 0L && zero)) return false
            if (d == 0L) zero = true else pendingDigit = d
            return true
        }

        private fun trailing() = when {
            pendingDigit < 0 -> 0L
            zero -> pendingDigit
            else -> pendingDigit * maxOf(place / 10, 1)
        }

        private fun small(unit: Long): Boolean {
            if (unit >= lastSmall) return false
            section += (if (pendingDigit < 0) 1 else pendingDigit) * unit
            next(unit)
            lastSmall = unit
            return true
        }

        private fun big(unit: Long): Boolean {
            // 三万亿: a place on a place, the larger on the smaller
            val bare = section == 0L && pendingDigit < 0
            if (unit > lastBig && bare && !zero) {
                total *= unit
                lastBig = unit
                place = unit
                return true
            }
            // 二十亿万, 万万: a misrecognition, no number
            if (unit >= lastBig || bare) return false
            // 十万二 is 120000: the place below that of the number before 万, not below 万
            val lowest = if (pendingDigit >= 0 || zero || lastSmall == Long.MAX_VALUE) 1 else lastSmall
            // 一亿七万: the 七 of 七万, not the place below 亿
            total += (section + (if (section == 0L) pendingDigit else trailing())) * unit
            section = 0
            next(unit * lowest)
            lastSmall = Long.MAX_VALUE
            lastBig = unit
            return true
        }

        private fun next(unit: Long) {
            pendingDigit = -1
            zero = false
            place = unit
        }

        // 一千零: a 零 with nothing after it is no number
        fun value() = if (zero && pendingDigit < 0) null else total + section + trailing()
    }
}
