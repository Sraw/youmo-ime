/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationStartKeys
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.ChinesePunctuation
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.picker.EmojiSearchUi
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a key shows is what it types (060-keys-unify §3.3, 用户 2026-11-11: 标点转换不仅作用于打出的字符，选择时的也要
 * 一起变): on every keyboard and panel, Chinese punctuation on and off, each long press's items, the swipe's
 * bubble and the corner as the item or the swipe types them; changed as the punctuation is toggled with the
 * keyboard up. PickerLongPress (the symbol grid's own) is no named key's and as is for both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class DisplayIsTypedUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var made: KeyboardHarness? = null

    @After
    fun close() {
        made?.close()
    }

    private val handwriting = InputMethodEntry(InputMethodCatalog.HANDWRITING, "手写", "", "", "写", "zh_CN", "androidengine", false)

    private val zh = ChinesePunctuation.map

    private fun harness(im: InputMethodEntry, chosen: ImKeyboards.Chosen?, punctuation: Map<String, String>, inputType: Int): KeyboardHarness {
        made?.close()
        return KeyboardHarness(TestThemes.PixelLight, im, keyboards = chosen).apply {
            api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, handwriting)
            api.enabledIme = api.availableIme
        }.start(inputType).also { h ->
            made = h
            // as fcitx's punctuation addon tells it, cached too: an input view made anew replays it
            h.chinesePunctuation(im, punctuation)
            h.relayout()
        }
    }

    // what [a] types with [punctuation]: a key the engine maps, a text as it is, a keypad's digit; null for an action
    private fun typed(a: KeyAction, punctuation: Map<String, String>): String? = when (a) {
        is KeyAction.HostKeyAction -> punctuation[a.act] ?: a.act
        is KeyAction.CommitAction -> a.text
        is KeyAction.SymAction -> (a.sym.sym - FcitxKeyMapping.FcitxKey_KP_0).takeIf { it in 0..9 }?.toString()
        else -> null
    }

    private class Board(val name: String, val im: InputMethodEntry, val chosen: ImKeyboards.Chosen? = null, val inputType: Int = InputType.TYPE_CLASS_TEXT, val open: KeyboardHarness.() -> Unit = {})

    private fun KeyboardHarness.emoji() = tap(described(service.getString(R.string.a11y_key_emoji)))

    private val boards = listOf(
        Board("full", KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.FULL)),
        Board("14", KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14)),
        Board("18", KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS18)),
        Board("nine", KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS),
        Board("zhuyin", KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_FULL)),
        Board("zhuyin nine", KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_NINE)),
        Board("English full", KeyboardHarness.ENGLISH),
        Board("English nine", KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)),
        Board("handwriting", handwriting),
        Board("number", KeyboardHarness.PINYIN, inputType = InputType.TYPE_CLASS_NUMBER),
        Board("symbols", KeyboardHarness.PINYIN) { tap(key("符")) },
        Board("emoji", KeyboardHarness.PINYIN) { emoji() },
        // the panel opens on the page last shown: the emoticons' already, or its tab tapped
        Board("emoticons", KeyboardHarness.PINYIN) { emoji(); runCatching { keyNamed("emoticon") }.getOrNull()?.let(::tap) },
        Board("expanded", KeyboardHarness.PINYIN) {
            api.candidates = Array(60) { CandidateWord("", "字$it", "") }
            emit(HostEvent.Candidates(60, api.candidates.toList()))
            tap(described(service.getString(R.string.expand_candidates_list)))
        },
        Board("emoji search", KeyboardHarness.PINYIN) {
            emoji()
            // from the emoticons' page (shown last) to the emoji's, which has the search
            tap(runCatching { keyNamed("search") }.getOrElse { tap(keyNamed("emoji")); keyNamed("search") })
            check(KeyboardHarness.find<EmojiSearchUi>(inputView) != null)
        },
    )

    // the keyboards shown now and their named keys
    private fun KeyboardHarness.shownBoards(): List<BaseKeyboard> =
        KeyboardHarness.all(inputView).filterIsInstance<BaseKeyboard>().filter { it.isShown }.toList()

    // every named key of every board shown: its long press's labels, its swipe's bubble and its corner as they type
    private fun KeyboardHarness.checkShownAsTyped(where: String, punctuation: Map<String, String>): Int {
        val boards = shownBoards()
        assertTrue("$where: no keyboard shown", boards.isNotEmpty())
        return boards.sumOf { board ->
            keys(board).filterIsInstance<SceneShownKey>().filter { it.copy == 0 }.sumOf { checkKey(where, board, it, punctuation) }
        }
    }

    // [k]'s, as [checkShownAsTyped]: how many of its texts were compared
    private fun checkKey(where: String, board: BaseKeyboard, k: SceneShownKey, punctuation: Map<String, String>): Int {
        val name = k.node.shortcut ?: return 0
        val ref = board.sceneKey(k.node)?.ref?.id ?: return 0
        val at = "$where ${k.id} ($name)"
        var checked = 0
        board.resolvedKeys.popup(ref)?.keyboard?.let { p ->
            p.actions.zip(p.labels).forEach { (a, label) -> typed(a, punctuation)?.let { assertEquals("$at: $a", it, label); checked++ } }
        }
        val swipe = board.resolvedKeys.swipe(name) { null } as? BaseKeyboard.SwipeChoice.Output ?: return checked
        val swiped = typed(swipe.action, punctuation) ?: return checked
        assertEquals("$at: the swipe's bubble", swiped, swipe.shown)
        // its corner, where its up is its first (a full keyboard's letter may show its letter instead)
        val id = KeyId.of(name)
        val letter = id.form == "full" && id.key.singleOrNull()?.isLetter() == true
        if (!letter && SwipeSlots.parse(AppPrefs.getInstance().internal.keySwipes.getValue()).resolve(name, true) == SwipeSlot.First) {
            assertTrue("$at: corner ${k.drawnTexts}, typed $swiped", swiped in k.drawnTexts)
        }
        return checked + 1
    }

    @Test
    @Uat("KB-83")
    fun everyKeyShowsWhatItTypes() {
        prefs.setWays(up = true, down = true)
        for (board in boards) {
            for ((mode, punctuation) in listOf("中文标点" to zh, "英文标点" to emptyMap())) {
                val h = harness(board.im, board.chosen, punctuation, board.inputType)
                board.open(h)
                h.idle()
                val checked = h.checkShownAsTyped("${board.name} $mode", punctuation)
                // the number pad's, the symbol panel's and the expanded candidates' keys type no character of a list
                // (no ，. there)
                if (board.name !in setOf("number", "symbols", "expanded")) assertTrue("${board.name} $mode: nothing checked", checked > 0)
            }
        }
    }

    // what pops up is what the keyboard resolved: on each board, its first named key with a long press, held for real
    @Test
    @Uat("KB-83")
    fun aHeldKeysPopupIsTheResolvers() {
        var compared = 0
        for (board in boards) {
            val h = harness(board.im, board.chosen, zh, board.inputType)
            board.open(h)
            h.idle()
            // the board laid over the rest (emoji search's covers the panel's bottom row, still shown under it)
            val b = h.shownBoards().last()
            val found = h.keys(b).asSequence().filterIsInstance<SceneShownKey>().filter { it.copy == 0 }.mapNotNull { k ->
                val ref = b.sceneKey(k.node)?.ref?.id ?: return@mapNotNull null
                b.resolvedKeys.popup(ref)?.keyboard?.labels?.takeIf { it.isNotEmpty() }?.let { k to it }
            }.firstOrNull() ?: continue
            val (k, labels) = found
            assertEquals("${board.name} ${k.id}", labels.sorted(), h.longPress(k).sorted())
            compared++
        }
        // the number pad, the symbol panel, the expanded candidates and emoji search's letters have none
        assertTrue("$compared", compared >= boards.size - 4)
    }

    // toggled with the keyboard up: the corner, the bubble and the long press as it now types
    @Test
    @Uat("KB-83")
    fun toggledWithTheKeyboardUp() {
        prefs.setWays(up = true, down = true)
        // ，'s and 。's corners their firsts' (. and ,)
        val slots = SwipeSlots.Default.with("fn/comma", up = true, SwipeSlot.First).with("fn/period", up = true, SwipeSlot.First)
        prefs.set(AppPrefs.getInstance().internal.keySwipes, slots.serialize())
        // the nine keys' 1 has no punctuation first: its long press's . alone
        val firsts = mapOf("fn/comma" to ".", "fn/period" to ",", "t9/1" to ".")
        for (board in boards.filter { it.name in setOf("full", "nine", "handwriting", "emoji") }) {
            val h = harness(board.im, board.chosen, zh, board.inputType)
            board.open(h)
            h.idle()
            h.checkShownAsTyped("${board.name} on", zh)
            fun key() = h.keys().filterIsInstance<SceneShownKey>().firstOrNull { it.copy == 0 && it.node.shortcut in firsts }
                ?: error("${board.name}: ${h.keys().filterIsInstance<SceneShownKey>().map { it.node.shortcut }}")
            val first = firsts.getValue(key().node.shortcut!!)
            val mapped = zh.getValue(first)
            val corner = key().node.shortcut != "t9/1"
            if (corner) assertTrue("${board.name}: ${key().drawnTexts}", mapped in key().drawnTexts)
            assertTrue(board.name, mapped in h.longPress(key()))
            // 九键's 1 and its column as they type, through the punctuation: ，。？！ on
            if (board.name == "nine") h.nineAsTyped("，。？！", "，")
            h.api.punctuationCached = emptyMap()
            h.emit(HostEvent.StatusArea(emptyList(), board.im, emptyMap()))
            h.idle()
            h.checkShownAsTyped("${board.name} off", emptyMap())
            if (corner) assertTrue("${board.name}: ${key().drawnTexts}", first in key().drawnTexts && mapped !in key().drawnTexts)
            val held = h.longPress(key())
            assertTrue("${board.name}: $held", first in held && mapped !in held)
            // and , . ? ! off
            if (board.name == "nine") h.nineAsTyped(",.?!", ",")
        }
    }

    // 九键's 1 its [face], its column's first [first], each typing what it shows
    private fun KeyboardHarness.nineAsTyped(face: String, first: String) {
        assertEquals(face, nineKey("1").label)
        val column = nineColumn()
        assertEquals(face.map { it.toString() } + "…", column.take(5).map { it.text.toString() })
        val before = field.text.toString()
        after(BUBBLE_GONE_MS)
        tap(nineKey("1"))
        after(BUBBLE_GONE_MS)
        tap(column.first())
        assertEquals(before + first + first, field.text.toString())
    }

    private fun KeyboardHarness.nineColumn(): List<TextView> = KeyboardHarness.all(keyboard).filterIsInstance<ScrollView>().first { shown(it) }
        .let { it.getChildAt(0) as ViewGroup }.children.filterIsInstance<TextView>().filter { it.isVisible }.toList()

    // 九键's 1 and its column before the marks are known (a first install, the host not up): held back as the ，。 keys
    // are, nothing typed that its face might not show; known, each types its mark
    @Test
    @Uat("KB-83")
    fun ninesOneAndColumnAreHeldBackTillTheMarksAreKnown() {
        val h = harness(KeyboardHarness.PINYIN, KeyboardHarness.NINE_KEYS, zh, InputType.TYPE_CLASS_TEXT)
        // not known: the harness's host tells its marks as the view is made, so the component is set back by hand
        val punctuation = h.inputView.punctuation
        PunctuationStartKeys.forget(h.service)
        punctuation.forget()
        h.after(BUBBLE_GONE_MS)
        h.tap(h.nineKey("1"))
        h.after(BUBBLE_GONE_MS)
        h.tap(h.nineColumn().first())
        assertEquals(h.api.calls.toString(), "", h.field.text.toString())
        punctuation.update(zh)
        h.idle()
        h.nineAsTyped("，。？！", "，")
    }

    private companion object {
        const val BUBBLE_GONE_MS = 1000L
    }
}
