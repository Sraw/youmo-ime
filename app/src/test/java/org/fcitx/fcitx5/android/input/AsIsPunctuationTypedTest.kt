/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.HostConfig
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.keyboardOn
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishDataBuilder
import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.EnginesHost
import org.fcitx.fcitx5.android.host.EngineHost
import org.fcitx.fcitx5.android.input.keys.First
import org.fcitx.fcitx5.android.input.keys.ItemMark
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.until as waitUntil
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * A long press's `.` marked 不转中文标点 (061-no-cn-punct, uat KB-98–100) typed through the Kotlin host to a real engine
 * (a tiny dictionary): `.` under Chinese punctuation, the candidate first while pinyin is typed; English's the same
 * marked or not.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class AsIsPunctuationTypedTest {

    private lateinit var home: File
    private lateinit var fcitx: KotlinHost
    private lateinit var h: KeyboardHarness
    private val internal get() = AppPrefs.getInstance().internal
    private var listsWere = ""
    private var swipesWere = ""

    // a host and keyboard up, to close
    private var up = false

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

    private fun side(home: File) = object : KotlinHost.EngineSide {
        val engines = Engines(
            blobs = { path ->
                when (path) {
                    InputMethodCatalog.PINYIN_DATA -> ByteBuffer.wrap(pinyin)
                    InputMethodCatalog.ENGLISH_DATA -> ByteBuffer.wrap(english)
                    else -> throw FileNotFoundException(path)
                }
            },
            userDir = null,
            warmUps = Executor(Runnable::run),
        )
        override val host: EngineHost = EnginesHost(engines)
        override fun data() = HostConfig(File(home, "data"), writable = true)
        override fun config() = HostConfig(File(home, "config"), writable = true)
        override fun start() = Unit
        override fun close() = Unit
    }

    @Before
    fun keep() {
        listsWere = internal.keyLists.getValue()
        swipesWere = internal.keySwipes.getValue()
    }

    @After
    fun tearDown() {
        if (up) {
            h.close()
            fcitx.stop()
            home.deleteRecursively()
            up = false
        }
        internal.keyLists.setValue(listsWere)
        internal.keySwipes.setValue(swipesWere)
    }

    private fun until(what: String, state: () -> String = { "" }, done: () -> Boolean) =
        waitUntil(what, state, step = { h.idle() }, done = done)

    // the comma key's list `.` `,` `?`, `.` marked where [marked], its first where [first]
    private fun commaList(marked: Boolean, first: String? = null) {
        val list = UserList(false, listOf(".", ",", "?"), first?.let(First::Literal) ?: First.Default, if (marked) mapOf("." to ItemMark.AsIs) else emptyMap())
        internal.keyLists.setValue(KeyLists.of(mapOf(KeyId("fn/comma") to list)).serialize())
    }

    private fun on(im: String) {
        home = File(appContext.filesDir, "as-is").apply { deleteRecursively() }
        // the unit tests have no assets, the built-in table with them: the marks it converts as the user's table
        File(home, "data/punctuation/punc.mb.zh_CN").apply { parentFile!!.mkdirs() }.writeText(". 。\n, ，\n? ？\n( （\n) ）\n")
        fcitx = startedHost(side(home))
        h = keyboardOn(fcitx)
        up = true
        h.start()
        h.service.postFcitxJob {
            setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodCatalog.ENGLISH))
            activateIme(im)
        }
        until(im) { fcitx.inputMethodEntryCached.uniqueName == im }
        until("its keys") {
            h.relayout()
            comma() != null
        }
    }

    private fun comma(): SceneShownKey? = h.keys().filterIsInstance<SceneShownKey>()
        .firstOrNull { it.node.copy == 0 && it.node.shortcut?.let { s -> KeyId.of(s).name } == "fn/comma" }

    private fun typeLetters(word: String) {
        for (c in word) h.tap(h.keys().first { it.label.equals(c.toString(), ignoreCase = true) })
        // pinyin's in the preedit, English's in the field as it is composed: all of it, the host past the last key
        // (its first letter alone shown, the next was still on its way as the case went on to the long press)
        until("$word typed", { "preedit [${fcitx.inputPanelCached.preedit}] field [${h.field.text}]" }) {
            fcitx.inputPanelCached.preedit.toString() == word || h.field.text.toString() == word
        }
    }

    @Test
    @Uat("KB-98")
    fun aMarkedDotIsTypedAsItIsUnderChinesePunctuation() {
        commaList(marked = true)
        on(InputMethodCatalog.PINYIN)
        val shown = h.longPress(comma()!!, pick = ".")
        // drawn as typed: the marked one as it is, the others as the punctuation
        assertEquals(setOf(".", "，", "？"), shown.toSet())
        until("typed", { h.field.text.toString() }) { h.field.text.isNotEmpty() }
        assertEquals(".", h.field.text.toString())
    }

    @Test
    @Uat("KB-99")
    fun whilePinyinIsTypedTheCandidateGoesFirst() {
        commaList(marked = true)
        on(InputMethodCatalog.PINYIN)
        typeLetters("ni")
        h.longPress(comma()!!, pick = ".")
        until("你. typed", { h.field.text.toString() }) { h.field.text.toString() == "你." }
    }

    @Test
    @Uat("KB-99")
    fun aMarkedFirstIsTypedBySwipingUp() {
        commaList(marked = true, first = ".")
        internal.keySwipes.setValue(SwipeSlots.Default.with("fn/comma", up = true, SwipeSlot.First).serialize())
        on(InputMethodCatalog.PINYIN)
        typeLetters("ni")
        h.swipe(comma()!!, 0f, -comma()!!.height * 1.3f)
        until("你. typed", { h.field.text.toString() }) { h.field.text.toString() == "你." }
    }

    @Test
    @Uat("KB-100")
    fun notMarkedItIsChinesePunctuationAsEver() {
        commaList(marked = false)
        on(InputMethodCatalog.PINYIN)
        typeLetters("ni")
        h.longPress(comma()!!, pick = "。")
        until("你。 typed", { h.field.text.toString() }) { h.field.text.toString() == "你。" }
    }

    // review S4: English types the same, a word just typed or not, marked or not
    @Test
    @Uat("KB-100")
    fun englishTypesTheSameMarkedOrNot() {
        fun typed(marked: Boolean, word: Boolean): String {
            commaList(marked)
            on(InputMethodCatalog.ENGLISH)
            if (word) typeLetters("hello")
            h.longPress(comma()!!, pick = ".")
            h.idle()
            until("typed", { h.field.text.toString() }) { "." in h.field.text && fcitx.inputPanelCached.preedit.isEmpty() }
            h.after(200)
            return h.field.text.toString().also { tearDown() }
        }
        for (word in listOf(false, true)) assertEquals("a word typed: $word", typed(marked = false, word), typed(marked = true, word))
    }

    // the comma key's list `(` `,` `?`, `(` marked 原样 where [marked]
    private fun bracketList(marked: Boolean) {
        val list = UserList(false, listOf("(", ",", "?"), First.Default, if (marked) mapOf("(" to ItemMark.AsIs) else emptyMap())
        internal.keyLists.setValue(KeyLists.of(mapOf(KeyId("fn/comma") to list)).serialize())
    }

    // the field with | at the cursor
    private fun shown() = h.field.text.toString().let { t -> t.substring(0, h.field.selectionStart) + "|" + t.substring(h.field.selectionStart) }

    // FB9: an item typed as it is (原样) goes in paired as one typed through the engine does, after the candidate;
    // Backspace in the empty pair takes both
    @Test
    @Uat("KB-110")
    fun aMarkedBracketGoesInPairedAfterTheCandidate() {
        bracketList(marked = true)
        on(InputMethodCatalog.PINYIN)
        // nothing typed: committed by the service itself, not through the engine
        h.longPress(comma()!!, pick = "(")
        until("() typed", { shown() }) { shown() == "(|)" }
        h.tap(h.keyWithId(R.id.button_backspace)!!)
        until("the pair gone", { shown() }) { shown() == "|" }
        typeLetters("ni")
        h.longPress(comma()!!, pick = "(")
        until("你() typed", { "${shown()} preedit [${fcitx.inputPanelCached.preedit}]" }) { shown() == "你(|)" }
        h.tap(h.keyWithId(R.id.button_backspace)!!)
        until("the pair gone", { shown() }) { shown() == "你|" }
    }

    // English types it the same, marked (committed as it is) or not (through the engine)
    @Test
    @Uat("KB-111")
    fun englishPairsTheBracketMarkedOrNot() {
        fun typed(marked: Boolean): String {
            bracketList(marked)
            on(InputMethodCatalog.ENGLISH)
            h.longPress(comma()!!, pick = "(")
            until("typed", { shown() }) { "(" in h.field.text }
            h.after(200)
            return shown().also { tearDown() }
        }
        assertEquals("(|)", typed(marked = false))
        assertEquals("(|)", typed(marked = true))
    }
}
