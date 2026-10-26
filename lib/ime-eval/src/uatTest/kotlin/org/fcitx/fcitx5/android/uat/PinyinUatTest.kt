/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.ModelLevel
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** uat/06-pinyin.md: full pinyin, each case from B0 (a fresh install, the 普通文本 field focused, empty). */
class PinyinUatTest {

    @Test
    @Uat("PY-01")
    fun spaceCommitsTheFirstSentence() = with(EngineReplay()) {
        type("nihao")
        space()
        expectText("你好")
    }

    @Test
    @Uat("PY-02")
    fun aWholeSentence() = with(EngineReplay()) {
        type("womenyiqiqugongyuan")
        space()
        expectText("我们一起去公园")
    }

    @Test
    @Uat("PY-03")
    fun theFirstHalfPickedTheRestStillOffered() = with(EngineReplay()) {
        type("womenqunali")
        pick("我们")
        expectText("")
        assertEquals("我们qunali", preedit.replace(" ", "").replace("'", ""))
        val first = candidates.first()
        assertTrue("first $first", first.startsWith("去") && first.length >= 3)
    }

    @Test
    @Uat("PY-04")
    fun anotherCandidateCommitsThatOne() = with(EngineReplay()) {
        type("shi")
        val third = page[2]
        pickAt(2)
        expectText(third)
    }

    @Test
    @Uat("PY-05")
    fun backspaceDeletesTheLastLetter() = with(EngineReplay()) {
        type("nihao")
        val before = page
        backspace()
        assertEquals("niha", preedit.replace(" ", "").replace("'", ""))
        assertNotEquals(before, page)
    }

    @Test
    @Uat("PY-07")
    fun initials() = with(EngineReplay()) {
        type("zg")
        expectOnFirstPage("中国")
        backspace(2)
        type("wsm")
        expectOnFirstPage("为什么")
    }

    @Test
    @Uat("PY-10")
    fun theWordsBeforeChooseTheWord() {
        with(EngineReplay()) {
            type("daxue")
            expectFirst("大学")
        }
        with(EngineReplay()) {
            type("zuotianwanshangxialeyichang")
            space()
            expectText("昨天晚上下了一场")
            type("daxue")
            expectFirst("大雪")
        }
    }

    @Test
    @Uat("PY-14")
    fun pinyinTypedWrongStillGivesTheWord() = with(EngineReplay()) {
        for ((code, words) in listOf("zhagn" to listOf("张", "章"), "zhon" to listOf("中"), "jv" to listOf("句"), "nihso" to listOf("你好"))) {
            type(code)
            expectOnFirstPage(*words.toTypedArray())
            backspace(code.length)
            expectPreedit("")
        }
    }

    @Test
    @Uat("PY-22")
    fun chineseAndEnglishMixed() = with(EngineReplay()) {
        type("wodeiphone")
        space()
        expectText("我的iPhone")
    }

    @Test
    @Uat("PY-26")
    fun wordsThatMayFollowAfterACommit() = with(EngineReplay()) {
        type("jintian")
        space()
        expectText("今天")
        val offered = page
        assertTrue("nothing offered after 今天", offered.isNotEmpty())
        pickAt(0)
        expectText("今天" + offered.first())
    }


    private fun EngineReplay.wxm(): List<String> {
        focus("")
        commit("。")
        type("wxm")
        return candidates.also { backspace(3) }
    }

    @Test
    @Uat("PY-34")
    fun aNamePickedPieceByPieceTwiceIsAWordAndInItsInitials() = with(EngineReplay()) {
        val start = wangXiaoming()
        assertEquals("before anything learned: $start", "王小明", start.first())
        backspace("wangxiaoming".length)
        val initials = wxm()
        learnWangXiaoming { n, shown -> if (n == 1) assertEquals("once: $shown", listOf("王小明", "汪小明"), shown.take(2)) }
        assertEquals("twice", "汪小明", wangXiaoming().first())
        backspace("wangxiaoming".length)
        val after = wxm()
        assertEquals("wxm $after (before: ${initials.take(3)})", initials.first(), after.first())
        assertEquals("wxm $after", "汪小明", after[1])
    }

    @Test
    @Uat("PY-54")
    fun afterTheSameWordsPickedOnceIsSecondTwiceFirst() = with(EngineReplay()) {
        fun taDl(pick: String?): List<String> {
            type("ta")
            pick("他")
            type("dl")
            return candidates.also { if (pick != null) pick(pick) }
        }
        assertEquals("带来", taDl("点了").first())
        assertEquals(listOf("带来", "点了"), taDl("点了").take(2))
        assertEquals(listOf("点了", "带来"), taDl(null).take(2))
    }
    // U-20 (D32 B): the input after 他, picked or not, the list as shown; the field then emptied
    private fun EngineReplay.afterTa(keys: String, pick: String?, after: Boolean = true): List<String> {
        if (after) {
            type("ta")
            pick("他")
        }
        type(keys)
        val shown = candidates
        if (pick != null) pick(pick) else backspace(keys.length)
        focus("")
        commit("。")
        return shown
    }

    @Test
    @Uat("PY-09")
    fun aWordTypedTwiceInFullGoesThirdUnderItsInitials() = with(EngineReplay()) {
        assertTrue("fresh", "奉化" !in afterTa("fh", null).take(4))
        repeat(2) { afterTa("fenghua", "奉化") }
        val fh = afterTa("fh", "奉化")
        assertEquals("fh $fh", 2, fh.indexOf("奉化"))
        afterTa("fh", "奉化")
        assertEquals("picked twice under fh", "奉化", afterTa("fh", null).first())
        repeat(2) { afterTa("dianle", "点了") }
        val dl = afterTa("dl", null)
        assertEquals("dl $dl", "带来", dl.first())
        assertTrue("dl $dl", "点了" in dl.take(3))
    }

    @Test
    @Uat("PY-09")
    fun aWordUsedUnderTheInitialsStaysFirstThere() = with(EngineReplay()) {
        // 知道 made zd's first by two picks there (真的 is first fresh)
        repeat(2) {
            focus("")
            type("zd")
            pick("知道")
        }
        repeat(2) {
            focus("")
            type("zhidao")
            pick("指导")
        }
        focus("")
        type("zd")
        val zd = candidates
        assertEquals("zd $zd", "知道", zd.first())
        assertTrue("zd $zd", "指导" in zd.take(3))
    }

    private fun EngineReplay.fenghuaTwice() = repeat(2) { afterTa("fenghua", "奉化") }

    @Test
    @Uat("PY-09")
    fun aWordTypedTwiceInFullGoesThirdUnderPartsOfItToo() = with(EngineReplay()) {
        fenghuaTwice()
        for (keys in listOf("fengh", "feng'h")) {
            val shown = afterTa(keys, null)
            assertEquals("$keys $shown", 2, shown.indexOf("奉化"))
        }
        // the emoji of the first after the lifted word, not before it (R15, user 2026-10-25): one
        // Android 14 draws (凤凰's 🐦‍🔥 is Emoji 15.1, not offered there), as docs/pinyin.md's example
        repeat(2) { afterTa("shoujia", "售价") }
        val shown = afterTa("shouj", null)
        assertEquals("shouj $shown", listOf("手机", "收集", "售价", "📱"), shown.take(4))
    }

    // a pin in front, the rest a place down: the lifted word fourth (U-83, R14)
    @Test
    @Uat("PY-09c")
    fun aPinGoesFirstAndTheLiftedWordAPlaceDown() = with(EngineReplay()) {
        fenghuaTwice()
        type("ta")
        pick("他")
        type("fh")
        val before = candidates
        assertEquals("fh $before", 2, before.indexOf("奉化"))
        val c = before[4]
        learn(LearningOp.Pin(4, c))
        backspace(2)
        focus("")
        commit("。")
        // the pin's emoji (😡 of 发火) goes beside it: the words alone
        val after = afterTa("fh", null).filter { t -> t.all { it in '\u4e00'..'\u9fff' } }
        assertEquals("fh ${before.take(6)}", listOf(c, before[0], before[1], "奉化"), after.take(4))
    }

    private fun EngineReplay.why(text: String) = (learn(LearningOp.Menu(candidates.indexOf(text), text)) as LearningResult.Menu).why?.tier

    // 为什么排在这 (U-81): the lift and the floor have reasons of their own, the first not the lift's
    @Test
    @Uat("PY-09d")
    fun whyItIsHereTellsTheLiftAndTheFloor() = with(EngineReplay()) {
        fenghuaTwice()
        type("ta")
        pick("他")
        type("fh")
        assertEquals(HabitRanker.Tier.LIFTED, why("奉化"))
        assertNotEquals(HabitRanker.Tier.LIFTED, why(candidates.first()))
        backspace(2)
        focus("")
        afterTa("dl", "点了")
        type("dianl")
        assertEquals(HabitRanker.Tier.FLOORED, why("点了"))
    }

    // 滑行 is full pinyin's full spelling (CommitRecorder.code)
    @Test
    @Uat("PY-77b")
    fun aWordGlidedTwiceGoesThirdUnderItsInitials() = with(EngineReplay()) {
        assertTrue("fresh", "奉化" !in afterTa("fh", null).take(5))
        repeat(2) {
            type("ta")
            pick("他")
            glide("fenghua")
            pick("奉化")
            focus("")
            commit("。")
        }
        val fh = afterTa("fh", null)
        assertTrue("fh $fh", "奉化" in fh.take(3))
    }

    @Test
    @Uat("PY-09b")
    fun aWordCorrectedToUnderTheInitialsIsNoLowerThanSecondInFullTillItExpires() = with(EngineReplay()) {
        // no word before: after 他, 他点了 is read so anyway
        assertTrue("fresh", "点了" !in afterTa("dianl", null, after = false).take(2))
        // picked after 他, where the dl list has it to pick; counted under dl whatever the word before
        afterTa("dl", "点了")
        assertTrue("dianl", "点了" in afterTa("dianl", null, after = false).take(2))
        assertTrue("dianle", "点了" in afterTa("dianle", null, after = false).take(2))
        // 30 typing days with no 点了 under dl: its row, its corrections and the floor gone
        repeat(31) {
            UatEngines.Clock.days(1)
            focus("")
            type("nihao")
            space()
        }
        // no word before (你好 just committed would read 你好点了 by context)
        focus("")
        type("dianl")
        assertTrue("expired: $candidates", "点了" !in candidates.take(2))
    }

    @Test
    @Uat("PY-06")
    fun anApostropheSeparatesSyllables() = with(EngineReplay()) {
        type("xi'an")
        expectPreedit("xi'an")
        expectFirst("西安")
    }

    @Test
    @Uat("PY-11")
    fun aChatsBoxSentStillGivesTheWordsBefore() = with(EngineReplay()) {
        type("zuotianwanshangxialeyichang")
        space()
        expectText("昨天晚上下了一场")
        sent()
        type("daxue")
        expectFirst("大雪")
    }

    @Test
    @Uat("PY-12")
    fun theLowLevelStillTypesASentence() = with(EngineReplay(settings(EngineSettings(modelLevel = ModelLevel.LOW)))) {
        type("womenyiqiqugongyuan")
        space()
        expectText("我们一起去公园")
    }

    @Test
    @Uat("PY-18")
    fun aWordOftenMisreadShowsItsReading() = with(EngineReplay()) {
        type("jiaose")
        expectAmong("角色")
        assertTrue("hint 「${hintOf("角色")}」", "jué sè" in hintOf("角色"))
    }

    @Test
    @Uat("PY-19")
    fun eachSwitchTurnedOffTakesItsCandidates() {
        with(EngineReplay(settings(EngineSettings(typos = false)))) {
            type("zhagn")
            assertTrue("zhagn: $page", "张" !in page && "章" !in page)
        }
        with(EngineReplay(settings(EngineSettings(latinWords = false)))) {
            type("iphone")
            expectNotAmong("iPhone")
        }
        with(EngineReplay(settings(EngineSettings(prediction = false)))) {
            type("jintian")
            space()
            expectText("今天")
            assertTrue("predicted $page", page.isEmpty())
        }
        with(EngineReplay(settings(EngineSettings(emoji = false)))) {
            type("haha")
            assertTrue("emoji among ${candidates.take(12)}", candidates.none(::isEmoji))
        }
        with(EngineReplay(settings(EngineSettings(chaizi = false)))) {
            type("uniuniuniu")
            expectNotAmong("犇")
        }
    }

    @Test
    @Uat("PY-20")
    fun fuzzyZZh() = with(EngineReplay(settings(EngineSettings(fuzzy = setOf(Fuzzy.Z_ZH))))) {
        type("zongguo")
        expectOnFirstPage("中国")
    }

    @Test
    @Uat("PY-21")
    fun fuzzyOffByDefault() = with(EngineReplay()) {
        type("zongguo")
        assertTrue("first page $page", page.isNotEmpty() && "中国" !in page)
    }

    @Test
    @Uat("PY-24")
    fun uThenStrokes() = with(EngineReplay()) {
        type("uhsp")
        expectAmong("才", "本", "村")
        for (c in listOf("才", "本", "村")) assertTrue("no reading beside $c", hintOf(c).isNotBlank())
    }

    @Test
    @Uat("PY-27")
    fun typingPutsTheWordsThatMayFollowAway() = with(EngineReplay()) {
        type("jintian")
        space()
        val predicted = page
        assertTrue("nothing offered after 今天", predicted.isNotEmpty())
        type("h")
        expectPreedit("h")
        assertNotEquals(predicted, page)
    }

    @Test
    @Uat("PY-31")
    fun pinnedFirstThenUnpinned() = with(EngineReplay()) {
        type("shi")
        val before = candidates
        val pinned = before[4]
        assertEquals(LearningResult.Done::class, learn(LearningOp.Pin(4, pinned))::class)
        focus("")
        type("shi")
        val after = candidates
        assertEquals("pinned first: $after", pinned, after.first())
        assertEquals("the rest a place down", before.filter { it != pinned }.take(3), after.subList(1, 4))
        // a pin looks at no context
        focus("")
        type("ta")
        pick("他")
        type("shi")
        expectFirst(pinned)
        assertEquals("a custom phrase written", "", UatEngines.phrases)
        focus("")
        type("shi")
        learn(LearningOp.Unpin(0, pinned))
        focus("")
        type("shi")
        expectFirst(before.first())
    }

    @Test
    @Uat("PY-32")
    fun forgettingWhatWasLearned() = with(EngineReplay()) {
        val start = repeat3TaDl()
        assertEquals("the precondition", "点了", candidates.first())
        learn(LearningOp.Delete(0, "点了"))
        while (preedit.isNotEmpty()) backspace()
        expectText("他")
        type("dl")
        val after = candidates
        assertEquals("forgotten: $after (before ${start.take(4)})", "带来", after.first())
        assertNotEquals("点了", after[1])
    }

    // 他 dl, 点了 picked twice in a field emptied each time, then 他 dl a third time: what dl offered the first time
    private fun EngineReplay.repeat3TaDl(): List<String> {
        var first: List<String> = emptyList()
        repeat(3) { n ->
            focus("")
            type("ta")
            pick("他")
            type("dl")
            if (n == 0) first = candidates
            if (n < 2) pick("点了")
        }
        return first
    }

    @Test
    @Uat("PY-37")
    fun vulgarWordsAreNotOnTheFirstPage() = with(EngineReplay()) {
        type("cao")
        val vulgar = listOf("肏", "屌", "操你", "操逼", "操蛋", "草泥马", "肏你")
        assertTrue("first page $page", page.isNotEmpty() && page.none { it in vulgar })
    }

    @Test
    @Uat("PY-44")
    fun chaiziByTheParts() = with(EngineReplay()) {
        type("uniuniuniu")
        expectFirst("犇")
        assertTrue("no reading beside 犇", hintOf("犇").isNotBlank())
        while (preedit.isNotEmpty()) backspace()
        type("ujinjinjin")
        expectFirst("鑫")
    }

    @Test
    @Uat("PY-45")
    fun notPartsArePinyinAsEver() = with(EngineReplay()) {
        type("usb")
        expectPreedit("usb")
        assertTrue("usb: ${candidates.take(6)}", candidates.any { "usb" in it.lowercase() })
    }

    @Test
    @Uat("PY-46")
    fun anEmojiCandidateIsNotLearnedFirst() = with(EngineReplay()) {
        type("dianzan")
        val at = candidates.indexOf("👍")
        assertTrue("👍 at $at of ${candidates.take(8)}", at >= 2)
        assertTrue("first ${candidates.first()}", !isEmoji(candidates.first()))
        pick("👍")
        expectText("👍")
        focus("")
        type("dianzan")
        assertNotEquals("👍", candidates.first())
    }

    @Test
    @Uat("PY-48")
    fun theDateTimeAndWeekdayBuiltIn() = onOneDay { today -> with(EngineReplay()) {
        type("rq")
        expectFirst("日期")
        val shown = candidates.subList(1, 8)
        for (d in dates(today)) assertTrue("rq: $d not in $shown", d in shown)
        space()
        expectText("日期")
        val now = LocalTime.now()
        type("sj")
        val times = (-1L..1L).map { now.plusMinutes(it).format(DateTimeFormatter.ofPattern("HH:mm")) }
        assertTrue("sj: ${candidates.take(4)} (now $now)", candidates.drop(1).any { it in times })
        assertTrue("sj: ${candidates.take(4)}", candidates.drop(1).any { Regex("""\d\d时\d\d分""").matches(it) })
        backspace(2)
        type("xq")
        val day = "一二三四五六日"[today.dayOfWeek.value - 1]
        assertTrue("xq: ${candidates.take(4)}", "星期$day" in candidates.drop(1) && "周$day" in candidates.drop(1))
    } }

    @Test
    @Uat("PY-49")
    fun aBuiltInPhraseDeletedIsOneTurnedOffInTheCustomPhrases() = onOneDay { day -> with(EngineReplay()) {
        val (long, dashed, chinese) = dates(day)
        type("rq")
        learn(LearningOp.Unpin(candidates.indexOf(dashed), dashed))
        while (preedit.isNotEmpty()) backspace()
        type("rq")
        expectNotAmong(dashed)
        expectAmong(long, chinese)
        // 自定义短语: the rq line there, turned off; turned on again
        val off = UatEngines.phrases.lines().single { it.startsWith("rq,") && "month_mm" in it }
        assertTrue("not turned off: $off", off.startsWith("rq,-"))
        UatEngines.phrases = UatEngines.phrases.replace(off, off.replace("rq,-", "rq,"))
        engines.reload()
        focus("")
        type("rq")
        expectAmong(dashed)
    } }

    @Test
    @Uat("PY-50")
    fun pinyinGlide() = with(EngineReplay()) {
        glide("nihao")
        expectPreedit("ni'hao")
        expectFirst("你好")
        val other = candidates[1]
        while (preedit.isNotEmpty()) backspace()
        glide("wo")
        glide("men")
        expectAmong("我们")
        focus("")
        glide("nihao")
        pick(other)
        expectText(other)
        expectPreedit("")
    }

    @Test
    @Uat("PY-52")
    fun theBuiltInDatesTurnedOff() = onOneDay { day ->
        val today = dates(day)
        with(EngineReplay(settings(EngineSettings(builtInPhrases = false)))) {
            type("rq")
            expectNotAmong(*today.toTypedArray())
            backspace(2)
            type("xq")
            assertTrue("xq: ${candidates.take(6)}", candidates.none { it.startsWith("星期") && it.length == 3 })
            backspace(2)
            type("riqi")
            expectAmong("日期")
            expectNotAmong(*today.toTypedArray())
            engines.settings = EngineSettings()
            focus("")
            type("rq")
            expectAmong(today[1])
        }
    }

    @Test
    @Uat("PY-72")
    fun aWordNoLongerOfferedAfterAnother() = with(EngineReplay()) {
        type("nihao")
        space()
        val b = page.first()
        assertEquals(LearningResult.Done::class, learn(LearningOp.NoPredict(0, b))::class)
        commit("。")
        type("nihao")
        space()
        assertTrue("$b still after 你好: $page", b !in page)
        commit("。")
        type(checkNotNull(engines.pinyinOf(b)).replace(" ", "").replace("'", ""))
        expectAmong(b)
        val listed = engines.learning(im, LearningOp.ListItems(LearningOp.Listed.NO_PREDICT)) as LearningResult.Listed
        assertEquals("listed ${listed.items}", 1, listed.items.size)
    }

    @Test
    @Uat("PY-73")
    fun textAlreadyInTheFieldIsTheWordsBefore() = with(EngineReplay()) {
        repeat(2) {
            paste("他")
            type("dl")
            pick("点了")
        }
        paste("他")
        type("dl")
        expectFirst("点了")
    }

}

/**
 * A fresh install's engines with [s] set, as the settings pages set them. For pinyin's cases: [s]
 * replaces [UatEngines.NEW_INSTALL] whole, so a table gets EngineSettings' defaults, not a new
 * install's (a table's case sets its options as its page does, TablesUatTest.wubi).
 */
internal fun settings(s: EngineSettings) = UatEngines.fresh().also { it.settings = s }

/**
 * [block] on today's date, read before it as a case reads it: the engine's built-in dates read the
 * wall clock, so a run across midnight (the date read one day, the engine's the next) is skipped,
 * not failed.
 */
internal fun onOneDay(block: (LocalDate) -> Unit) {
    val day = LocalDate.now()
    try {
        block(day)
    } catch (e: AssertionError) {
        Assume.assumeTrue("midnight passed during the case", LocalDate.now() == day)
        throw e
    }
}

internal fun isEmoji(text: String) = text.codePoints().anyMatch { it in 0x1F000..0x1FAFF || it in 0x2600..0x27BF }

// rq's built-in dates of [d]: 2026年10月17日, 2026-10-17, 二〇二六年十月十七日
internal fun dates(d: LocalDate): List<String> {
    val digit = "〇一二三四五六七八九"
    fun cn(n: Int) = when {
        n < 10 -> digit[n].toString()
        n < 20 -> "十" + (if (n % 10 > 0) digit[n % 10].toString() else "")
        else -> digit[n / 10] + "十" + (if (n % 10 > 0) digit[n % 10].toString() else "")
    }
    return listOf(
        "${d.year}年${d.monthValue}月${d.dayOfMonth}日",
        d.format(DateTimeFormatter.ISO_LOCAL_DATE),
        d.year.toString().map { digit[it - '0'] }.joinToString("") + "年${cn(d.monthValue)}月${cn(d.dayOfMonth)}日",
    )
}


// PY-34's name and its pieces; the field emptied and 。 typed before each, so the words before are the same
internal fun EngineReplay.wangXiaoming(): List<String> {
    focus("")
    commit("。")
    type("wangxiaoming")
    return candidates
}

/** PY-34's steps: 汪小明 picked piece by piece, twice, [seen] told the candidates before each. */
internal fun EngineReplay.learnWangXiaoming(seen: (n: Int, shown: List<String>) -> Unit = { _, _ -> }) = repeat(2) { n ->
    seen(n, wangXiaoming())
    pick("汪")
    pick("小明")
    expectText("。汪小明")
    commit("。")
}

/** The candidates of [keys] typed in a field focused afresh. */
internal fun EngineReplay.typed(keys: String): List<String> {
    focus("")
    type(keys)
    return candidates
}

/** 他 then dl, in a field focused afresh: the candidates of dl after 他, [pick] picked among them if given. */
internal fun EngineReplay.taDl(pick: String?): List<String> {
    focus("")
    type("ta")
    pick("他")
    type("dl")
    return candidates.also { if (pick != null) pick(pick) }
}
