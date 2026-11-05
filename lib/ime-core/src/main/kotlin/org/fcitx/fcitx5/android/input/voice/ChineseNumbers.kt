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
 * Converted: a year (二零零二年, 九八年), a date (六月二十三日, 二零零二年一月), a percentage (百分之三十到四十
 * both), a decimal (十三点六, 零点五, 三点五亿, 三点一四一五), a time (六点半, 早上九点, 下午三点四十), a number of
 * more than one character with a place (十五, 一百二十, 三十三万) or of three digits and more (一一七, 幺三五零,
 * 九幺幺), 九零后, money (五块三毛, 十二块五), a kilo-unit's number (二十五千克).
 * Kept: a single digit otherwise (一个, 三年, 五分钟), two digits side by side (三四线, 七八个),
 * a place without a digit before it (万一, 千万, 成千上万), 十 alone before 分/足/全 (十分有趣),
 * a rough count (几十万, 二十几岁), a time with no 分 and no time of day (九点五十, 十点五十, 十一点三五), a
 * fraction (十六分之三), dotted numbers (幺二七点零点零点幺), and what does not read as a number (十六十七, 二十亿万).
 */
object ChineseNumbers {

    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        // 一 in a run of digits said one by one: phone numbers, codes (幺三八, 九幺幺); a word alone (幺妹)
        '幺' to 1,
    )
    private val SMALL = mapOf('十' to 10L, '百' to 100L, '千' to 1000L)
    private val BIG = mapOf('万' to 10_000L, '亿' to 100_000_000L)

    private const val HOURS = 24
    private const val MINUTES = 60
    private const val PERCENT = "百分之"
    private const val FRACTION = "分之"

    // after a decimal: what makes 一点五 a quantity and not "a little"
    private const val UNITS = "万亿%倍米元块克斤吨升度岁秒平"
    // after 千 that is a unit's (二十五千克: kilo), not a place
    private const val KILO = "米克瓦"
    private const val YUAN = "元块"
    private const val JIAO = "角毛"
    // the number in a point made, not an hour: 这一点十分重要, 第三点
    private const val NOT_HOUR_BEFORE = "这那哪第每几"
    // 点整 that goes on as a word: 这一点整体来看, 差一点整天
    private const val NOT_SHARP_AFTER = "体个理齐洁合数改顿治天晚夜"
    // 一点半 that goes on as a word, 一点 being "a bit": 没有一点半点, 有一点半信半疑
    private const val NOT_HALF_AFTER = "点信"

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
        // 百分之三十到四十: the second a percentage too
        val second = text.getOrNull(run.end)?.takeIf { it in "到至" }?.let { numberAt(text, run.end + 1) }
        if (second != null && second.after.endsAPercent()) {
            out.append(text[run.end]).append(second.digits).append('%')
            return second.end
        }
        return run.end
    }

    private fun Char?.endsAPercent() = this == null || !Character.isIdeographic(code) || this in "的之左右"

    /** The number at [start] into [out], as digits or as it was; where it ends. */
    private fun number(text: String, start: Int, out: StringBuilder): Int {
        val run = numberAt(text, start)
        if (run == null) {
            // no number, all of it: 十六十七 is not 十 and 67
            val end = runEnd(text, start)
            out.append(text, start, end)
            return end
        }
        // 十六分之三: a fraction, both numbers as said
        // (九十分之后 is a score and after it)
        val fraction = (text.startsWith(FRACTION, run.end) && isNumberChar(text.getOrElse(run.end + FRACTION.length) { ' ' })) ||
            (text.startsWith(FRACTION, start - FRACTION.length) && isNumberChar(text.getOrElse(start - FRACTION.length - 1) { ' ' }))
        out.append((if (fraction) null else decide(run)) ?: run.raw)
        return run.end
    }

    private enum class Kind { Whole, Decimal, Time }

    /** The number of [text] from [start] to [end], written [digits]; [before] it two chars, [after] it one. */
    private class Run(val text: String, val start: Int, val end: Int, val digits: String, val kind: Kind = Kind.Whole) {
        val raw = text.substring(start, end)
        val before = text.substring(maxOf(0, start - 2), start)
        val after = text.getOrNull(end)
    }

    /** The number starting at [start] and its digits, with a decimal or a time's 点 in it; null if none. */
    private fun numberAt(text: String, start: Int): Run? {
        var end = runEnd(text, start)
        if (end == start) return null
        // 十千米 is ten kilometres: no number reads 十千
        if (text[end - 1] == '千' && kilo(text, start, end)) end--
        val raw = text.substring(start, end)
        // 二六六万: digits read one by one, then a place
        val prefix = raw.dropLast(1)
        if (raw.last() in BIG && prefix.length >= 3 && prefix.all { it in DIGITS }) {
            return Run(text, start, end, integerDigits(prefix) + raw.last())
        }
        val whole = integerDigits(raw) ?: return null
        if (end + 1 < text.length && text[end] == '点') {
            (time(text, start, end, whole) ?: decimal(text, start, end, whole))?.let { return it }
        }
        return Run(text, start, end, written(raw, whole))
    }

    private fun kilo(text: String, start: Int, end: Int) =
        end - start > 1 && text.getOrNull(end).isIn(KILO) && integerDigits(text.substring(start, end)) == null

    /**
     * 六点半, 九点十五分: a time; none if its hour is past 24 or its minutes past 59 (八点五百分之一),
     * or if it is a point made (这一点十分重要) or a bit (没有一点半点).
     */
    private fun time(text: String, start: Int, point: Int, whole: String): Run? {
        val hour = whole.toLongOrNull()
        if (hour == null || hour > HOURS || text.getOrNull(start - 1).isIn(NOT_HOUR_BEFORE)) return null
        val next = text[point + 1]
        if (halfOrSharp(next, text.getOrNull(point + 2), whole)) {
            return Run(text, start, point + 2, "${whole}点$next", Kind.Time)
        }
        var minuteEnd = point + 1
        while (minuteEnd < text.length && (text[minuteEnd] in DIGITS || text[minuteEnd] in SMALL)) minuteEnd++
        val said = text.substring(point + 1, minuteEnd)
        val minutes = integerDigits(said)?.toLongOrNull() ?: return null
        if (minutes >= MINUTES) return null
        // 十点零五分 as 10点05分; not %02d, which writes the phone's language's digits (٠٥ in Arabic)
        val written = if (said.startsWith('零')) "$minutes".padStart(2, '0') else "$minutes"
        if (text.getOrNull(minuteEnd) != '分') {
            // 下午三点四十: after a time of day a time, 分 or not
            return Run(text, start, minuteEnd, "${whole}点$written", Kind.Time).takeIf { afterTimeOfDay(text, start) && bareMinutes(text, said, minuteEnd) }
        }
        if (text.getOrNull(minuteEnd + 1) == '钟') return null
        // 一点十分奇怪: "a bit very strange", no time; 十三点十分三十六秒 one
        val following = text.getOrNull(minuteEnd + 1)
        if (said == "十" && following.goesOnAWord()) return null
        return Run(text, start, minuteEnd + 1, "${whole}点${written}分", Kind.Time)
    }

    // minutes with no 分 that cannot be anything else: ten and more, or 零 and a digit (三点零五), and nothing
    // after them that makes them another word or a count (晚上一点一点暗下来, 三点零几分, 八点一刻, 九点五十个人, 七点五十块)
    private fun bareMinutes(text: String, said: String, end: Int) =
        (said.length > 1 || said[0] in SMALL) && !text.getOrNull(end).isIn("点几刻个号人年$UNITS")

    private fun Char?.goesOnAWord() = this != null && this !in AFTER_TEN_PAST && !isNumberChar(this)

    /** 六点半 or 八点整, from [next] after the 点 of the hour [whole] and [after] after it; not 一点半点 or 一点整体. */
    private fun halfOrSharp(next: Char, after: Char?, whole: String) = when (next) {
        '半' -> !(whole == "1" && after.isIn(NOT_HALF_AFTER))
        '整' -> !after.isIn(NOT_SHARP_AFTER)
        else -> false
    }

    // after a number's 点: 幺二七点零点零点幺; not a word's (重点三点五倍)
    private fun dotted(text: String, start: Int) = text.getOrNull(start - 1) == '点' && isNumberChar(text.getOrElse(start - 2) { ' ' })

    /** 十三点六, 三点五亿; 九点五十, with no 分, neither a decimal nor sure to be a time. */
    private fun decimal(text: String, start: Int, point: Int, whole: String): Run? {
        var fractionEnd = point + 1
        while (fractionEnd < text.length && text[fractionEnd] in DIGITS && text[fractionEnd] != '两') fractionEnd++
        // 幺二七点零点零点幺 (an address) is no decimal, at its start nor its end
        if (dotted(text, start) || fractionEnd == point + 1 || text.getOrNull(fractionEnd).isIn("十百千点")) return null
        val fraction = text.substring(point + 1, fractionEnd).map { DIGITS.getValue(it) }.joinToString("")
        val unit = text.getOrNull(fractionEnd)?.takeIf { it in BIG }
        val end = if (unit != null) fractionEnd + 1 else fractionEnd
        return Run(text, start, end, "$whole.$fraction${unit ?: ""}", Kind.Decimal)
    }

    // a char not there (before the text or past it) is in no set
    private fun Char?.isIn(chars: String) = this != null && this in chars

    /** 三十三万 as 33万, 四百亿 as 400亿 and 三万亿 as 3万亿, as written; 十二万三千 in full. */
    private fun written(raw: String, value: String): String {
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

    /** What [run] is written as, or null to leave it in characters. */
    private fun decide(run: Run): String? {
        val raw = run.raw
        return when {
            run.kind == Kind.Time -> run.digits
            run.kind == Kind.Decimal -> run.digits.takeIf { isQuantity(run) }
            rough(run) || besideBarePoint(run) -> null
            // a single digit: in a date or at a time, else in characters
            raw.length == 1 && raw[0] in DIGITS -> run.digits.takeIf { singleDigitAsDigit(run) }
            raw.all { it in DIGITS } -> run.digits.takeIf { digitsAsDigits(run) }
            raw == "十" -> run.digits.takeIf { tenAsDigits(run) }
            else -> run.digits
        }
    }

    // 一点一点 is "bit by bit": a decimal is one with a unit, or more than a digit before it, but for
    // an hour with minutes said digit by digit (十一点三五, as 十点三五): with no 分, a time as likely;
    // or three digits after the point and more, no minutes (三点一四一五九)
    private fun isQuantity(run: Run) =
        run.raw.first() == '零' || run.raw.last() in BIG || run.after.isIn(UNITS) ||
            run.digits.substringAfter('.').length > 2 ||
            (run.raw.substringBefore('点').length > 1 && !minutesDigitByDigit(run))

    private fun minutesDigitByDigit(run: Run): Boolean {
        val hour = run.digits.substringBefore('.').toLongOrNull() ?: return false
        val minutes = run.digits.substringAfter('.').takeIf { it.length == 2 }?.toIntOrNull() ?: return false
        return hour <= HOURS && minutes < MINUTES
    }

    // a rough count (几十万, 数百, 二十几岁)
    private fun rough(run: Run) = run.before.endsWith('几') || run.before.endsWith('数') || run.after == '几'

    // the hour or the minutes of a time left in characters, 九点五十 and 十点五十 alike: a 点 with no 分
    // after its number is neither a decimal nor sure to be a time
    private fun besideBarePoint(run: Run) =
        (run.before.length == 2 && run.before[1] == '点' && isNumberChar(run.before[0])) ||
            (run.after == '点' && run.end + 1 < run.text.length && isNumberChar(run.text[run.end + 1]))

    // a year (二零零二年, 九八年), a decade (九零后, 八零年代), three digits and more (一一七);
    // not two side by side (三四线, 七八个), nor two pairs (三三两两, 七七八八)
    private fun digitsAsDigits(run: Run): Boolean {
        val r = run.raw
        val pairs = r.length == 4 && r[0] == r[1] && r[2] == r[3] && DIGITS[r[0]] != DIGITS[r[2]]
        if (pairs && run.after != '年') return false
        return r.length >= 3 || run.after == '年' || run.after == '后' || run.text.startsWith("年代", run.end)
    }

    // 十 alone: 十分有趣, 十足, 十全十美, 上十 kept, but not the hour of 早上十点; 十分钟, 十个 not
    private fun tenAsDigits(run: Run) =
        run.text.startsWith("分钟", run.end) ||
            (!run.after.isIn("分足全") && (!run.before.endsWith('上') || atTimeOfDay(run)) && !run.before.endsWith('全'))

    // 早上九点, 晚上十点: an hour after a time of day
    private fun atTimeOfDay(run: Run) = run.after == '点' && afterTimeOfDay(run.text, run.start)

    private fun afterTimeOfDay(text: String, start: Int) = TIME_BEFORE.any { text.startsWith(it, start - it.length) }

    /**
     * A single digit as a digit: 六月 before a day or after a year, 二十三日 after a month, 九点 at a time,
     * money in 元 and 角 (五块三毛, 十二块五).
     */
    private fun singleDigitAsDigit(run: Run): Boolean = when (run.after) {
        // a year of two digits and more (三年一月 is three years and a month)
        '月' -> numberAt(run.text, run.end + 1)?.after.isIn("日号") || yearBefore(run)
        '日', '号' -> run.before.endsWith('月')
        '点' -> afterTimeOfDay(run.text, run.start)
        '元', '块' -> jiaoAt(run.text, run.end + 1)
        '角', '毛' -> after(run, YUAN) && jiaoEnds(run.text, run.end)
        '分' -> after(run, JIAO)
        // the last of the money: 十二块五, 五块三毛五
        else -> (after(run, YUAN) || after(run, JIAO)) && (run.after == null || !Character.isIdeographic(run.after.code))
    }

    // a year of two digits and more said one by one before it: 二零零二年一月, not 三年一月 or 一百年一月 (a hundred years and a month)
    private fun yearBefore(run: Run): Boolean {
        if (!run.text.startsWith("年", run.start - 1)) return false
        var from = run.start - 1
        while (from > 0 && run.text[from - 1] in DIGITS) from--
        return run.start - 1 - from >= 2 && !isNumberChar(run.text.getOrElse(from - 1) { ' ' })
    }

    // right after a number and one of [chars]: 二零零二年一月, 十二块五
    private fun after(run: Run, chars: String) = run.before.length == 2 && isNumberChar(run.before[0]) && run.before[1] in chars

    // a digit at [i] that is 角 (五块三毛, 五块三): with 角 or 毛 after it, or nothing that is a word
    private fun jiaoAt(text: String, i: Int): Boolean {
        if (text.getOrNull(i)?.let { it in DIGITS } != true) return false
        val next = text.getOrNull(i + 1)
        return if (next.isIn(JIAO)) jiaoEnds(text, i + 1) else next == null || !Character.isIdeographic(next.code)
    }

    // the 角 or 毛 at [i] ends the money, not a word (三角形, 五角星): nothing that is a word after it, or 钱, 分's digit
    private fun jiaoEnds(text: String, i: Int): Boolean {
        val next = text.getOrNull(i + 1) ?: return true
        return !Character.isIdeographic(next.code) || next == '钱' || next in DIGITS
    }
}
