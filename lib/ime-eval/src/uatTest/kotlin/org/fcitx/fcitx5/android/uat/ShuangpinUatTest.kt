/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.eval.EvalSet
import org.fcitx.fcitx5.android.eval.PinyinRun
import org.fcitx.fcitx5.android.eval.ShuangpinSet
import org.fcitx.fcitx5.android.eval.TouchSim
import org.fcitx.fcitx5.android.eval.TypedHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** uat/07-shuangpin.md: each case from B0-双拼 (自然码 unless the case sets another 方案). */
class ShuangpinUatTest {

    @Test
    @Uat("SP-02")
    fun xiaohe() = with(EngineReplay(settings(EngineSettings(shuangpin = "Xiaohe")), im = InputMethodCatalog.SHUANGPIN)) {
        type("nihc")
        space()
        expectText("你好")
    }

    @Test
    @Uat("SP-04")
    fun aSyllableWithNoInitial() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type("aj")
        expectAmong("安")
    }

    @Test
    @Uat("SP-05")
    fun twoKeysNoSyllableAreInitials() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type("fx")
        expectPreedit("f x")
        assertTrue("fx: $page", "发现" in page || "方向" in page)
        backspace(2)
        type("vg")
        expectPreedit("zheng")
    }

    @Test
    @Uat("SP-07")
    fun aWordMadeInFullPinyinTypesInShuangpinItsOrderItsOwn() = with(EngineReplay()) {
        learnWangXiaoming()
        ime(InputMethodCatalog.SHUANGPIN)
        type("whxcmy")
        expectStart("王小明", "汪小明")
    }

    @Test
    @Uat("SP-09")
    fun everyKeyboardGlides() {
        with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            glide("nihk")
            assertEquals("ni hao", preedit.replace("'", " "))
            expectAmong("你好")
        }
        with(EngineReplay(im = InputMethodCatalog.WUBI)) {
            glide("wqiy")
            assertTrue("「$preedit」 ${candidates.take(3)} 「$text」", (preedit == "wqiy" && candidates.firstOrNull() == "你") || text == "你")
        }
        with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
            glide("nihao", GlideLayout.nineKeys())
            assertTrue("「$preedit」", preedit.isNotEmpty() && preedit.none(Char::isDigit))
            expectAmong("你好")
        }
    }

    // 简拼 in the middle (docs/pinyin.md「双拼」): a key read alone where it and the next make a syllable
    @Test
    @Uat("SP-30")
    fun aWordTypedWithItsFirstSyllableWholeAndTheRestByInitials() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 自然码 shen = uf, then m, sh, h: mu and uh (shang) are syllables, each key read alone too,
        // for a word typed before; never typed, it is not read so
        type("ufmuh")
        assertTrue("ufmuh, never typed: $page", "什么时候" !in candidates)
        focus("")
        type("ufmeuihb")
        pick("什么时候")
        focus("")
        type("ufmuh")
        assertTrue("ufmuh: $page", "什么时候" in page)
        focus("")
        // zh's key alone: v and z make zhei
        type("yivz")
        assertTrue("yivz, never typed: $page", "一直在" !in candidates)
        focus("")
        type("yivizl")
        pick("一直在")
        focus("")
        type("yivz")
        assertTrue("yivz: $page", "一直在" in page)
    }

    @Test
    @Uat("SP-31")
    fun twoKeysThatMakeASyllableStayThatSyllableFirst() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 自然码 xm is xian: 先 first, the code row xian, as before 简拼 in the middle
        type("xm")
        expectPreedit("xian")
        expectFirst("先")
        backspace(2)
        type("vg")
        expectPreedit("zheng")
    }

    @Test
    @Uat("SP-31")
    fun wholeSyllablesReadAsBeforeTheAbbreviations() {
        // pinyin-chat.tsv typed in 小鹤 whole, as `ime-eval pinyin --scheme xiaohe` against --sp-abbrev off:
        // the decoder and the sentence model, the first candidates the same (none changed when measured)
        val samples = ShuangpinSet.convert(File("data/pinyin-chat.tsv").useLines { EvalSet.parse(it) }, ShuangpinScheme.XIAOHE)
        val model = SentenceModel.load(UatEngines.data(InputMethodCatalog.SENTENCE_MODEL), unpack = true)
        // a key alone reads the words typed before: the 3000 likeliest typed, both ways
        val typed = TypedHistory.likeliest(UatEngines.pinyinData, HISTORY)
        fun run(abbreviations: Boolean) = PinyinRun(UatEngines.pinyinData, ShuangpinSegmenter(ShuangpinScheme.XIAOHE, abbreviations = abbreviations), reranker = { Reranker(model) }, typed = typed)
        val (off, on) = run(false) to run(true)
        assertTrue("${samples.size}", samples.size > 1500)
        val changed = samples.filter { s -> off.candidates(s.input, s.context).firstOrNull() != on.candidates(s.input, s.context).firstOrNull() }
        assertEquals(emptyList<String>(), changed.map { it.input })
        // each syllable's two keys alone, in each scheme (the three the app offers and three 0.5 dropped,
        // as ShuangpinSegmenterTest keeps them): the first never changes, and what page 1 showed it
        // still shows first, but for a few; words fill a page a rare syllable's few characters left short
        for (scheme in ShuangpinSet.SCHEMES.values + DROPPED) {
            fun twoKeys(abbreviations: Boolean) = PinyinRun(UatEngines.pinyinData, ShuangpinSegmenter(scheme, abbreviations = abbreviations), reranker = { Reranker(model) }, typed = typed)
            val (was0, now0) = twoKeys(false) to twoKeys(true)
            val codes = (0 until Syllables.count).mapNotNull { scheme.encode(it) }.distinct()
            val displaced = ArrayList<String>()
            for (code in codes) {
                val (was, now) = was0.candidates(code).take(EngineSettings.DEFAULT_PAGE_SIZE) to now0.candidates(code).take(EngineSettings.DEFAULT_PAGE_SIZE)
                assertEquals("$scheme $code", was.firstOrNull(), now.firstOrNull())
                if (now.take(was.size) != was) displaced += "$code $was $now"
            }
            assertTrue("$displaced of ${codes.size}", displaced.size * 100 <= codes.size)
        }
    }

    @Test
    @Uat("SP-32")
    fun aWordTypedByItsInitialsOnly() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 自然码 sh m sh h: mu and uh are syllables, before them no way read 什么时候; a word typed before only
        type("umuh")
        assertTrue("umuh, never typed", "什么时候" !in candidates)
        focus("")
        type("ufmeuihb")
        pick("什么时候")
        focus("")
        type("umuh")
        expectAmong("什么时候")
    }

    @Test
    @Uat("SP-33")
    fun aWordTypedPartlyByInitialsIsLearnedUnderThoseKeys() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // typed in whole syllables once: read by keys alone from then on
        type("ufmeuihb")
        pick("什么时候")
        focus("")
        type("ufmuh")
        assertTrue("$page", page.first() != "什么时候")
        pick("什么时候")
        type("ufmuh")
        pick("什么时候")
        type("ufmuh")
        expectFirst("什么时候")
        // learned under those keys: by initials only it stays where it was
        type("umuh")
        assertTrue("$page", page.first() != "什么时候")
    }

    // a code that reads as whole syllables ranks as before 简拼 in the middle (061-shuangpin-abbrev review M3)
    @Test
    @Uat("SP-34")
    fun aWordUsedInFullIsNotRaisedUnderAPairItsInitialsSpell() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 自然码 xm is xian, xwmm xia mian: 下面 used there twice goes up under its initials apart, not under xm
        type("xm")
        val fresh = page
        repeat(2) {
            focus("")
            type("xwmm")
            pick("下面")
        }
        focus("")
        type("xm")
        assertEquals(fresh, page)
        focus("")
        type("x'm")
        assertTrue("x'm: $page", "下面" in page.take(3))
    }

    @Test
    @Uat("SP-35")
    fun aWordAddedByHandIsNotPutInUnderAPairItsInitialsSpell() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type("xm")
        val fresh = page
        focus("")
        assertTrue(engines.addWord("小明", "xiao ming"))
        type("xm")
        assertEquals(fresh, page)
        focus("")
        type("xcmy")
        expectFirst("小明")
        focus("")
        type("x'm")
        expectOnFirstPage("小明")
    }

    @Test
    @Uat("SP-36")
    fun whatIsPinnedOrPickedUnderACodeReadWholeIsRankedThere() {
        // 自然码 ky is kuai; 可以 by k and y alone, low on its list, once typed (keyi)
        val at = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            type("ky")
            assertTrue("ky, never typed", "可以" !in candidates)
            focus("")
            type("keyi")
            pick("可以")
            focus("")
            type("ky")
            val at = candidates.indexOf("可以")
            assertTrue("ky: ${candidates.take(40)}", at > 0)
            assertTrue(learn(LearningOp.Pin(at, "可以")) is LearningResult.Done)
            focus("")
            type("ky")
            expectFirst("可以")
            at
        }
        with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            type("keyi")
            pick("可以")
            focus("")
            repeat(2) {
                focus("")
                type("ky")
                pick("可以")
            }
            focus("")
            type("ky")
            assertTrue("ky: ${candidates.take(10)}", candidates.indexOf("可以") in 0 until minOf(at, EngineSettings.DEFAULT_PAGE_SIZE))
        }
    }

    // 自然码 u f, m, u, h: 什么时候 by its initials once typed, each key tapped in its middle or one by its
    // edge (m by n, read as n too); never typed, there is no such reading wherever the keys are tapped
    @Test
    @Uat("SP-41")
    fun initialsInTheMiddleReadTheWordsTypedWhereverTapped() {
        val data = UatEngines.pinyinData
        val board = TouchSim.board("qwerty").boxes
        fun shown(user: UserModel, dx: Float): List<String> {
            val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA, neighbours = true), pageSize = 40, prediction = false, user = user)
            return "ufmuh".map { c -> s.apply(Action.Key(c, touch = board.touch(c, 0f, 0f)!!.copy(dx = if (c == 'm') dx else 0f, dy = 0f))) }.last().candidates
        }
        val fresh = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
        val user = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
        assertEquals(1, TypedHistory.count(user, data, listOf("什么时候\tshen me shi hou")))
        for (dx in listOf(0f, -EDGE)) {
            val before = shown(fresh, dx)
            assertTrue("$dx: $before", "什么时候" !in before)
            val typed = shown(user, dx)
            // after the syllables the keys make (fm is fan, mu is mu), on the first page
            assertEquals("$dx", before.first(), typed.first())
            assertTrue("$dx: $typed", typed.indexOf("什么时候") in 1 until EngineSettings.DEFAULT_PAGE_SIZE)
        }
    }

    // the user's own words are typed ones for good: read by a key alone with no count
    @Test
    @Uat("SP-43")
    fun aWordAddedByHandIsReadByAKeyAloneAtOnce() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 自然码 x, then my (ming); xm is xian
        type("xmy")
        assertTrue("xmy, before", "小明" !in candidates)
        focus("")
        assertTrue(engines.addWord("小明", "xiao ming"))
        type("xmy")
        expectAmong("小明")
    }

    // 借手机: the habits off, nothing is typed before, and no key is read alone
    @Test
    @Uat("SP-46")
    fun withTheHabitsOffNoKeyIsReadAlone() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type("ufmeuihb")
        pick("什么时候")
        focus("")
        type("ufmuh")
        expectAmong("什么时候")
        focus("")
        engines.useHabits = false
        type("ufmuh")
        assertTrue("ufmuh, habits off", "什么时候" !in candidates)
        focus("")
        type("ufmeuihb")
        expectFirst("什么时候")
        focus("")
        engines.useHabits = true
        type("ufmuh")
        expectAmong("什么时候")
    }

    // U-20 (D32 B) in 自然码: fghw twice lifts 奉化 under the part fgh (two keys are a syllable there: fh is fang); full pinyin's uses do not
    @Test
    @Uat("SP-13b", "SP-13c")
    fun aWordTypedTwiceInFullGoesThirdUnderAPartWithinItsKeyboard() {
        with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            fun shown(keys: String, pick: String?): List<String> {
                focus("他")
                type(keys)
                return candidates.also { if (pick != null) pick(pick) else backspace(keys.length) }
            }
            assertTrue("fresh", "奉化" !in shown("fgh", null).take(5))
            repeat(2) { shown("fghw", "奉化") }
            val part = shown("fgh", null)
            assertTrue("fgh $part", "奉化" in part.take(3))
        }
        with(EngineReplay()) {
            repeat(2) {
                focus("他")
                type("fenghua")
                pick("奉化")
            }
            ime(InputMethodCatalog.SHUANGPIN)
            focus("他")
            type("fgh")
            assertTrue("full pinyin's: $candidates", "奉化" !in candidates.take(5))
        }
    }

    // the scheme's letters as the 18 keys or the nine keys send them: each its key's code (spaces apart for reading)
    private fun on(letters: NineKeyLetters, keys: String) = keys.filter { it != ' ' }.map { letters.key(it) }.joinToString("")

    @Test
    @Uat("SP-14")
    fun theEighteenKeysInXiaoheTypeASentenceAndOfferTheSyllables() =
        with(EngineReplay(settings(EngineSettings(shuangpin = "Xiaohe")), im = InputMethodCatalog.SHUANGPIN)) {
            type(on(NineKeyLetters.G18, "wo"))
            assertTrue("${panel.syllables}", "wo" in panel.syllables)
            type(on(NineKeyLetters.G18, "mf jb tm qu ii fj"))
            assertTrue("$page", SENTENCE in page)
            pick(SENTENCE)
            expectText(SENTENCE)
            assertTrue(EngineReplay.noPrivateUse(text))
        }

    @Test
    @Uat("SP-15")
    fun theNineKeysInZiranmaTypeASentenceAndOfferTheSyllables() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type(on(NineKeyLetters.NINE_SP, "wo"))
        assertTrue("${panel.syllables}", "wo" in panel.syllables)
        type(on(NineKeyLetters.NINE_SP, "mf jn tm qu ii fj"))
        assertTrue("$page", SENTENCE in page)
        pick(SENTENCE)
        expectText(SENTENCE)
        assertTrue(EngineReplay.noPrivateUse(text))
    }

    @Test
    @Uat("SP-16")
    fun microsoftsIngIsTheLSemicolonKey() =
        with(EngineReplay(settings(EngineSettings(shuangpin = "MS")), im = InputMethodCatalog.SHUANGPIN)) {
            type(on(NineKeyLetters.G18, "m;"))
            assertTrue("$page", "明" in page || "名" in page)
            backspace(2)
            type(on(NineKeyLetters.G18, "le"))
            assertTrue("$page", "了" in page)
        }

    @Test
    @Uat("SP-17")
    fun theEighteenKeysLearnApartFromTheFullKeyboard() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        val shi = on(NineKeyLetters.G18, "ui")
        val keys = typed(shi)
        val full = typed("ui").take(5)
        assertEquals(full.first(), keys.first())
        val second = keys[1]
        repeat(2) {
            typed(shi)
            pick(second)
            commit("。")
        }
        assertEquals(second, typed(shi).first())
        // learned under the 18 keys' own kind, not the full keyboard's
        val record = String(UatEngines.learningRecord(), Charsets.UTF_8)
        assertTrue(Kinds.shuangpinKeys("Ziranma", NineKeyLetters.G18.tag()) in record)
        // the full keyboard's order (sp:) as it was: nothing of the 18 keys' (spk:) learning
        assertEquals(full, typed("ui").take(5))
    }

    @Test
    @Uat("SP-18")
    fun enterOrASwitchCommitsTheReadingShownInLetters() = with(HostReplay(im = InputMethodCatalog.SHUANGPIN)) {
        keys(on(NineKeyLetters.G18, "xi"))
        val xi = preedit
        enter()
        assertEquals(xi.filter { it != ' ' && it != '\'' }, text)
        focus("")
        keys(on(NineKeyLetters.G18, "nih"))
        val nih = preedit
        ime(InputMethodCatalog.ENGLISH)
        assertEquals(nih.filter { it != ' ' && it != '\'' }, text)
        assertTrue(text, text.isNotEmpty() && text.all { it in 'a'..'z' })
    }

    @Test
    @Uat("SP-22")
    fun onTheNineKeysOneSeparatesTheSyllablesWhileTyping() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 6 4, 1 (the keyboard's ' while typing: T9Keyboard.resolveShuangpin), 4 3
        type(on(NineKeyLetters.NINE_SP, "ni") + "'" + on(NineKeyLetters.NINE_SP, "hd"))
        assertTrue(preedit, '\'' in preedit)
    }

    @Test
    @Uat("SP-23")
    fun microsoftsScreenSemicolonIsAMarkAndAnInitialTakenKeepsItsSyllables() {
        with(HostReplay(engines = settings(EngineSettings(shuangpin = "MS")), im = InputMethodCatalog.SHUANGPIN)) {
            keys(on(NineKeyLetters.G18, "ni"))
            val first = candidates.first()
            // the long press's ; is the screen's character, not the L; key's code
            keys(";")
            expectText("$first；")
        }
        with(EngineReplay(settings(EngineSettings(shuangpin = "MS")), im = InputMethodCatalog.SHUANGPIN)) {
            type(on(NineKeyLetters.G18, "xi"))
            syllable("c")
            assertFalse("$candidates", "西" in candidates)
            assertTrue("${panel.syllables}", panel.syllables.isNotEmpty() && panel.syllables.all { it.startsWith("c") })
            assertTrue("${panel.syllables}", "ci" in panel.syllables || "cuo" in panel.syllables)
            // and the words are c-'s: the first few read ci or cuo, as 词, 次, 错
            assertTrue("$candidates", candidates.take(5).any { it in setOf("词", "次", "此", "错", "刺", "瓷") })
        }
    }

    private companion object {
        // the words typed before where a run needs some: a few months' of a user's
        const val HISTORY = 3000

        // a tap this far of a key's width from its middle: by its edge
        const val EDGE = 0.42f

        const val SENTENCE = "我们今天去吃饭"

        // 紫光, 智能ABC and 国标, which 0.5 dropped (SchemeMigration): libime's tables, as ShuangpinSegmenterTest has them
        val DROPPED = listOf(
            ShuangpinScheme(
                "zh=u ch=a sh=i ai=p an=r ang=s ao=q ei=k en=w eng=t er=j ia=x ian=f iang=g iao=b ie=d in=y ing=; " +
                    "iong=h iu=j ong=h ou=z ua=x uai=y uan=l uang=g ue=n ui=n un=m uo=o ve=n v=v",
                "o",
            ),
            ShuangpinScheme(
                "zh=a ch=e sh=v ai=l an=j ang=h ao=k ei=q en=f eng=g er=r ia=d ian=w iang=t iao=z ie=x in=c ing=y " +
                    "iong=s iu=r ong=s ou=b ua=d uai=c uan=p uang=t ue=m ui=m un=n uo=o ve=m v=v",
                "o",
            ),
            ShuangpinScheme(
                "zh=v ch=i sh=u ai=k an=f ang=g ao=c ei=b en=r eng=h er=l ia=q ian=d iang=n iao=m ie=t in=l ing=j " +
                    "iong=s iu=y ong=s ou=p ua=q uai=y uan=w uang=n ue=x ui=v un=z uo=o ve=x v=v",
                "a",
            ),
        )
    }
}
