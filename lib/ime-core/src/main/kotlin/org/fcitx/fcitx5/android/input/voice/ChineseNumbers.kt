/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Numbers the recognizer writes in characters (二零零二年, 百分之五十七, 三十三万) as one types
 * them (2002年, 57%, 33万); words that only look like numbers (一个, 一夜之间, 十分有趣, 三四线,
 * 万一) left alone. sherpa-onnx's number FST turned 一夜之间 into 1夜之间: these are the rules of
 * the recognizer's own output (X-ASR's, which writes no digits), each in a test.
 *
 * Converted: a year (二零零二年, 九八年), a date (六月二十三日), a percentage, a decimal (十三点六,
 * 零点五, 三点五亿), a time (六点半, 早上九点), a number of more than one character with a place
 * (十五, 一百二十, 三十三万) or of three digits and more (一一七, 一一零), 九零后.
 * Kept: a single digit otherwise (一个, 三年, 五分钟), two digits side by side (三四线, 七八个),
 * a place without a digit before it (万一, 千万, 成千上万), 十 alone before 分/足/全 (十分有趣),
 * a rough count (几十万, 二十几岁), and what does not read as a number (十六十七, 二十亿万).
 */
object ChineseNumbers {

    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )
    private val SMALL = mapOf('十' to 10L, '百' to 100L, '千' to 1000L)
    private val BIG = mapOf('万' to 10_000L, '亿' to 100_000_000L)

    private const val HOURS = 24
    private const val MINUTES = 60
    private const val PERCENT = "百分之"

    // after a decimal: what makes 一点五 a quantity and not "a little"
    private const val UNITS = "万亿%倍米元块克斤吨升度岁秒"
    // the number in a point made, not an hour: 这一点十分重要, 第三点
    private const val NOT_HOUR_BEFORE = "这那哪第每几"
    // 点整 that goes on as a word: 这一点整体来看, 差一点整天
    private const val NOT_SHARP_AFTER = "体个理齐洁合数改顿治天晚夜"

    // what goes on from a time's 十分 (三点十分出发), where 十分 as "very" goes on to a word (十分奇怪)
    private const val AFTER_TEN_PAST = "的左右前后起到开见了出就才时，。、！？,.!? "

    // numbers in sayings, kept as said (those whose number is a single digit or starts with a
    // place, as 一心一意 or 百发百中, are kept anyway)
    private val SAYINGS = listOf(
        "十字", "十有八九", "十之八九", "十拿九稳", "十恶不赦", "十万火急", "十万八千里", "十面埋伏",
        "十指连心", "十室九空", "十年寒窗", "十年树木", "十八般", "十八罗汉", "三十而立", "四十不惑",
        "五十知天命", "七十二变", "三十六计", "九九归一",
    )
    private val TIME_BEFORE = listOf("早上", "上午", "中午", "下午", "晚上", "凌晨", "傍晚", "夜里", "今晚", "明早")

    private fun isNumberChar(c: Char) = c in DIGITS || c in SMALL || c in BIG

    private fun runEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && isNumberChar(text[end])) end++
        return end
    }

    fun convert(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val saying = SAYINGS.firstOrNull { text.startsWith(it, i) }
            i = when {
                saying != null -> {
                    out.append(saying)
                    i + saying.length
                }
                text.startsWith(PERCENT, i) -> percent(text, i, out)
                isNumberChar(text[i]) -> number(text, i, out)
                else -> {
                    out.append(text[i])
                    i + 1
                }
            }
        }
        return out.toString()
    }

    /** 百分之五十七 as 57%, 百分之三十几 as it was; where it ends. */
    private fun percent(text: String, start: Int, out: StringBuilder): Int {
        val run = numberAt(text, start + PERCENT.length)
        if (run == null || text.getOrNull(run.end) == '几') {
            out.append(PERCENT)
            return start + PERCENT.length
        }
        out.append(run.digits).append('%')
        return run.end
    }

    /** The number at [start] into [out], as digits or as it was; where it ends. */
    private fun number(text: String, start: Int, out: StringBuilder): Int {
        val run = numberAt(text, start)
        if (run == null) {
            // no number, all of it: 十六十七 is not 十 and 67
            val end = runEnd(text, start)
            out.append(text, start, end)
            return end
        }
        out.append(decide(text, start, run) ?: run.raw)
        return run.end
    }

    private class Run(val end: Int, val digits: String, val raw: String, val decimal: Boolean = false, val time: Boolean = false)

    /** The number starting at [start] and its digits, with a decimal or a time's 点 in it; null if none. */
    private fun numberAt(text: String, start: Int): Run? {
        val end = runEnd(text, start)
        if (end == start) return null
        val raw = text.substring(start, end)
        // 二六六万: digits read one by one, then a place
        val prefix = raw.dropLast(1)
        if (raw.last() in BIG && prefix.length >= 3 && prefix.all { it in DIGITS }) {
            return Run(end, integer(prefix) + raw.last(), raw)
        }
        val whole = integer(raw) ?: return null
        if (end + 1 < text.length && text[end] == '点') {
            (time(text, start, end, whole) ?: decimal(text, start, end, whole))?.let { return it }
        }
        return Run(end, written(raw, whole), raw)
    }

    /**
     * 六点半, 九点十五分: a time; none if its hour is past 24 or its minutes past 59 (八点五百分之一),
     * or if it is a point made (这一点十分重要).
     */
    private fun time(text: String, start: Int, point: Int, whole: String): Run? {
        val hour = whole.toLongOrNull()
        if (hour == null || hour > HOURS || text.getOrNull(start - 1)?.let { it in NOT_HOUR_BEFORE } == true) return null
        val next = text[point + 1]
        if (next == '半' || (next == '整' && text.getOrNull(point + 2)?.let { it in NOT_SHARP_AFTER } != true)) {
            return Run(point + 2, "${whole}点$next", text.substring(start, point + 2), time = true)
        }
        var m = point + 1
        while (m < text.length && (text[m] in DIGITS || text[m] in SMALL)) m++
        val said = text.substring(point + 1, m)
        val minutes = integer(said)?.toLongOrNull() ?: return null
        if (text.getOrNull(m) != '分' || minutes >= MINUTES || text.getOrNull(m + 1) == '钟') return null
        // 一点十分奇怪: "a bit very strange", no time
        if (said == "十" && text.getOrNull(m + 1)?.let { it !in AFTER_TEN_PAST } == true) return null
        // 十点零五分 as 10点05分
        val written = if (said.startsWith('零')) "%02d".format(minutes) else "$minutes"
        return Run(m + 1, "${whole}点${written}分", text.substring(start, m + 1), time = true)
    }

    /** 十三点六, 三点五亿; 九点五十, with no 分, neither a decimal nor sure to be a time. */
    private fun decimal(text: String, start: Int, point: Int, whole: String): Run? {
        var f = point + 1
        while (f < text.length && text[f] in DIGITS && text[f] != '两') f++
        if (f == point + 1 || text.getOrNull(f)?.let { it in SMALL } == true) return null
        val fraction = text.substring(point + 1, f).map { DIGITS.getValue(it) }.joinToString("")
        val unit = text.getOrNull(f)?.takeIf { it in BIG }
        val end = if (unit != null) f + 1 else f
        return Run(end, "$whole.$fraction${unit ?: ""}", text.substring(start, end), decimal = true)
    }

    /** 三十三万 as 33万, 四百亿 as 400亿 and 三万亿 as 3万亿, as written; 十二万三千 in full. */
    private fun written(raw: String, value: String): String {
        val places = raw.takeLastWhile { it in BIG }
        val before = raw.dropLast(places.length)
        if (places.isNotEmpty() && before.isNotEmpty() && before.none { it in BIG }) {
            integer(before)?.let { return "$it$places" }
        }
        return value
    }

    /** The integer [raw] reads as, as digits; null if it does not read as one (万一, 十十, 二十亿万). */
    private fun integer(raw: String): String? {
        if (raw.isEmpty()) return null
        // digits only: 二零零二, 一一七
        if (raw.all { it in DIGITS }) return raw.map { DIGITS.getValue(it) }.joinToString("")
        // a place first is no number, but 十 (十五)
        if (raw.first() in BIG || (raw.first() in SMALL && raw.first() != '十')) return null
        val reading = Reading()
        return if (raw.all { reading.read(it) }) reading.value()?.toString() else null
    }

    /** An integer read a character at a time, as said: 三十三万六千零五, 一万二 (12000). */
    private class Reading {
        private var total = 0L
        private var section = 0L
        private var digit = -1L
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
            if (digit >= 0 || (d == 0L && zero)) return false
            if (d == 0L) zero = true else digit = d
            return true
        }

        private fun trailing() = when {
            digit < 0 -> 0L
            zero -> digit
            else -> digit * maxOf(place / 10, 1)
        }

        private fun small(unit: Long): Boolean {
            if (unit >= lastSmall) return false
            section += (if (digit < 0) 1 else digit) * unit
            next(unit)
            lastSmall = unit
            return true
        }

        private fun big(unit: Long): Boolean {
            // 三万亿: a place on a place, the larger on the smaller
            val bare = section == 0L && digit < 0
            if (unit > lastBig && bare && !zero) {
                total *= unit
                lastBig = unit
                place = unit
                return true
            }
            // 二十亿万, 万万: a misrecognition, no number
            if (unit >= lastBig || bare) return false
            // 十万二 is 120000: the place below that of the number before 万, not below 万
            val lowest = if (digit >= 0 || zero || lastSmall == Long.MAX_VALUE) 1 else lastSmall
            total += (section + trailing()) * unit
            section = 0
            next(unit * lowest)
            lastSmall = Long.MAX_VALUE
            lastBig = unit
            return true
        }

        private fun next(unit: Long) {
            digit = -1
            zero = false
            place = unit
        }

        // 一千零: a 零 with nothing after it is no number
        fun value() = if (zero && digit < 0) null else total + section + trailing()
    }

    /** What [run] at [start] is written as, or null to leave it in characters. */
    private fun decide(text: String, start: Int, run: Run): String? {
        val raw = run.raw
        val before = text.substring(maxOf(0, start - 2), start)
        val after = text.getOrNull(run.end)
        return when {
            run.time -> run.digits
            run.decimal -> run.digits.takeIf { isQuantity(raw, after) }
            roughOrMinutes(before, after) -> null
            // a single digit: in a date or at a time, else in characters
            raw.length == 1 && raw[0] in DIGITS -> run.digits.takeIf { single(text, start, run, before) }
            raw.all { it in DIGITS } -> run.digits.takeIf { digitsAsDigits(text, run, after) }
            raw == "十" -> run.digits.takeIf { ten(text, run, before, after) }
            else -> run.digits
        }
    }

    // 一点一点 is "bit by bit": a decimal is one with a unit, or more than a digit before it
    private fun isQuantity(raw: String, after: Char?) =
        raw.substringBefore('点').length > 1 || raw.first() == '零' || raw.last() in BIG || (after != null && after in UNITS)

    // a rough count (几十万, 数百, 二十几岁), or the minutes of a time left in characters (九点五十)
    private fun roughOrMinutes(before: String, after: Char?) =
        before.endsWith('几') || before.endsWith('数') || after == '几' ||
            (before.length == 2 && before[1] == '点' && isNumberChar(before[0]))

    // a year (二零零二年, 九八年), a decade (九零后, 八零年代), three digits and more (一一七);
    // not two side by side (三四线, 七八个), nor two pairs (三三两两, 七七八八)
    private fun digitsAsDigits(text: String, run: Run, after: Char?): Boolean {
        val r = run.raw
        val pairs = r.length == 4 && r[0] == r[1] && r[2] == r[3] && DIGITS[r[0]] != DIGITS[r[2]]
        if (pairs && after != '年') return false
        return r.length >= 3 || after == '年' || after == '后' || text.startsWith("年代", run.end)
    }

    // 十 alone: 十分有趣, 十足, 十全十美, 上十 kept; 十分钟, 十个 not
    private fun ten(text: String, run: Run, before: String, after: Char?) =
        text.startsWith("分钟", run.end) ||
            (after != '分' && after != '足' && after != '全' && !before.endsWith('上') && !before.endsWith('全'))

    /** A single digit as a digit: 六月 before a day, 二十三日 after a month, 九点 at a time. */
    private fun single(text: String, start: Int, run: Run, before: String): Boolean = when (text.getOrNull(run.end)) {
        '月' -> numberAt(text, run.end + 1)?.let { text.getOrNull(it.end) == '日' || text.getOrNull(it.end) == '号' } == true
        '日', '号' -> before.endsWith('月')
        '点' -> TIME_BEFORE.any { text.substring(maxOf(0, start - 3), start).endsWith(it) }
        else -> false
    }
}
