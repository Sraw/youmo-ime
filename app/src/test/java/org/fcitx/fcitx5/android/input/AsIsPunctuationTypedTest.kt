/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.tinyEngineSide
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.input.keys.First
import org.fcitx.fcitx5.android.input.keys.ItemMark
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** A long press's `.` marked 不转中文标点, through the host to a tiny real engine; the candidate goes first while pinyin is typed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class AsIsPunctuationTypedTest {

    @get:Rule
    val host = HostKeyboardRule("as-is") {
        internal.keyLists.setValue(listsWere)
        internal.keySwipes.setValue(swipesWere)
    }
    private val h get() = host.h
    private val fcitx get() = host.fcitx
    private val internal get() = AppPrefs.getInstance().internal
    private var listsWere = ""
    private var swipesWere = ""

    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .entry("你", intArrayOf(Syllables.id("ni")))
        .build()
        .toByteArray()

    private val english = EnglishDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram(EnglishText.START, -9f, -0.5f)
        .unigram("hello", -1.3f, 0f)
        .build()
        .toByteArray()

    private fun side(home: File) = tinyEngineSide(home, { path ->
        when (path) {
            InputMethodCatalog.PINYIN_DATA -> ByteBuffer.wrap(pinyin)
            InputMethodCatalog.ENGLISH_DATA -> ByteBuffer.wrap(english)
            else -> throw FileNotFoundException(path)
        }
    })

    @Before
    fun keep() {
        listsWere = internal.keyLists.getValue()
        swipesWere = internal.keySwipes.getValue()
    }

    private fun commaList(marked: Boolean, first: String? = null) {
        val list = UserList(false, listOf(".", ",", "?"), first?.let(First::Literal) ?: First.Default, if (marked) mapOf("." to ItemMark.AsIs) else emptyMap())
        internal.keyLists.setValue(KeyLists.of(mapOf(KeyId("fn/comma") to list)).serialize())
    }

    // the host answers before the job can wait, as on a loaded machine: the job once put its text ahead of the engine's commit (.你)
    private fun answeringAtOnce(host: KotlinHost): HostApi = object : HostApi by host {
        override suspend fun select(idx: Int, text: String?) = runBlocking { host.select(idx, text) }
        override suspend fun reset(drop: Boolean) = runBlocking { host.reset(drop) }
    }

    private fun on(im: String, atOnce: Boolean = false) {
        host.up({ home ->
            // unit tests have no assets: the marks it converts as the user's table
            File(home, "data/punctuation/punc.mb.zh_CN").apply { parentFile!!.mkdirs() }.writeText(". 。\n, ，\n? ？\n( （\n) ）\n")
            side(home)
        }) { if (atOnce) answeringAtOnce(it) else it }
        h.start()
        h.service.postFcitxJob {
            setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH))
            activateIme(im)
        }
        host.until(im) { fcitx.inputMethodEntryCached.uniqueName == im }
        host.until("its keys") {
            h.relayout()
            comma() != null
        }
    }

    private fun comma(): SceneShownKey? = h.keys().filterIsInstance<SceneShownKey>()
        .firstOrNull { it.node.copy == 0 && it.node.shortcut?.let { s -> KeyId.of(s).name } == "fn/comma" }

    private fun typeLetters(word: String) {
        for (c in word) h.tap(h.keys().first { it.label.equals(c.toString(), ignoreCase = true) })
        // all of it, the host past the last key (once only the first letter showed when the long press came)
        host.until("$word typed", { "preedit [${fcitx.inputPanelCached.preedit}] field [${h.field.text}]" }) {
            fcitx.inputPanelCached.preedit.toString() == word || h.field.text.toString() == word
        }
    }

    @Test
    @Uat("KB-98")
    fun aMarkedDotIsTypedAsItIsUnderChinesePunctuation() {
        commaList(marked = true)
        on(InputMethodCatalog.PINYIN)
        val shown = h.longPress(comma()!!, pick = ".")
        assertEquals(setOf(".", "，", "？"), shown.toSet())
        host.until("typed", { h.field.text.toString() }) { h.field.text.isNotEmpty() }
        assertEquals(".", h.field.text.toString())
    }

    @Test
    @Uat("KB-99")
    fun whilePinyinIsTypedTheCandidateGoesFirst() {
        commaList(marked = true)
        on(InputMethodCatalog.PINYIN)
        typeLetters("ni")
        h.longPress(comma()!!, pick = ".")
        host.until("你. typed", { h.field.text.toString() }) { h.field.text.toString() == "你." }
    }

    @Test
    fun theCandidateGoesFirstWhenTheHostAnswersAtOnce() {
        commaList(marked = true)
        on(InputMethodCatalog.PINYIN, atOnce = true)
        typeLetters("ni")
        h.longPress(comma()!!, pick = ".")
        host.until("both typed", { h.field.text.toString() }) { h.field.text.length == 2 }
        assertEquals("你.", h.field.text.toString())
    }

    @Test
    @Uat("KB-99")
    fun aMarkedFirstIsTypedBySwipingUp() {
        commaList(marked = true, first = ".")
        internal.keySwipes.setValue(SwipeSlots.Default.with("fn/comma", up = true, SwipeSlot.First).serialize())
        on(InputMethodCatalog.PINYIN)
        typeLetters("ni")
        h.swipe(comma()!!, 0f, -comma()!!.height * 1.3f)
        host.until("你. typed", { h.field.text.toString() }) { h.field.text.toString() == "你." }
    }

    @Test
    @Uat("KB-100")
    fun notMarkedItIsChinesePunctuationAsEver() {
        commaList(marked = false)
        on(InputMethodCatalog.PINYIN)
        typeLetters("ni")
        h.longPress(comma()!!, pick = "。")
        host.until("你。 typed", { h.field.text.toString() }) { h.field.text.toString() == "你。" }
    }

    @Test
    @Uat("KB-100")
    fun englishTypesTheSameMarkedOrNot() {
        fun typed(marked: Boolean, word: Boolean): String {
            commaList(marked)
            on(InputMethodCatalog.ENGLISH)
            if (word) typeLetters("hello")
            h.longPress(comma()!!, pick = ".")
            h.idle()
            host.until("typed", { h.field.text.toString() }) { "." in h.field.text && fcitx.inputPanelCached.preedit.isEmpty() }
            h.after(200)
            return h.field.text.toString().also { host.down() }
        }
        for (word in listOf(false, true)) assertEquals("a word typed: $word", typed(marked = false, word), typed(marked = true, word))
    }

    private fun bracketList(marked: Boolean) {
        val list = UserList(false, listOf("(", ",", "?"), First.Default, if (marked) mapOf("(" to ItemMark.AsIs) else emptyMap())
        internal.keyLists.setValue(KeyLists.of(mapOf(KeyId("fn/comma") to list)).serialize())
    }

    private fun shown() = h.field.text.toString().let { t -> t.substring(0, h.field.selectionStart) + "|" + t.substring(h.field.selectionStart) }

    // 原样 goes in paired as one typed through the engine, after the candidate; Backspace in the empty pair takes both
    @Test
    @Uat("KB-110")
    fun aMarkedBracketGoesInPairedAfterTheCandidate() {
        bracketList(marked = true)
        on(InputMethodCatalog.PINYIN)
        // nothing typed: committed by the service itself, not through the engine
        h.longPress(comma()!!, pick = "(")
        host.until("() typed", { shown() }) { shown() == "(|)" }
        h.tap(h.keyWithId(R.id.button_backspace)!!)
        host.until("the pair gone", { shown() }) { shown() == "|" }
        typeLetters("ni")
        h.longPress(comma()!!, pick = "(")
        host.until("你() typed", { "${shown()} preedit [${host.preedit}]" }) { shown() == "你(|)" }
        h.tap(h.keyWithId(R.id.button_backspace)!!)
        host.until("the pair gone", { shown() }) { shown() == "你|" }
    }

    @Test
    @Uat("KB-111")
    fun englishPairsTheBracketMarkedOrNot() {
        fun typed(marked: Boolean): String {
            bracketList(marked)
            on(InputMethodCatalog.ENGLISH)
            h.longPress(comma()!!, pick = "(")
            host.until("typed", { shown() }) { "(" in h.field.text }
            h.after(200)
            return shown().also { host.down() }
        }
        assertEquals("(|)", typed(marked = false))
        assertEquals("(|)", typed(marked = true))
    }
}
