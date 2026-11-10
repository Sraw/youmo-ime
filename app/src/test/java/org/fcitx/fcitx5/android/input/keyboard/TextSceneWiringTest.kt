/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.fcitx.fcitx5.android.scene.KeyNode
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 26 keys drawn by the scene, wired from qwerty.xml's nodes (060-render-p2 U4 B), do what the
 * views' KeyDef (TextKeyboard.Layout) says, key by key, in each of its forms: pinyin, English, 双拼 with
 * `;`, a table's (五笔 with its roots and 拼, 仓颉 with its `*`), an address field's keys: shortcut, swipe axes,
 * actions, popups, sound, space/backspace and enabled (confirm's sound Return, as an exception). A letter's
 * press and long press are its letter in either case (TextKeyboard sends and shows them as Shift has
 * them); its bubble its letter too, whatever its face (a table's roots: TextKeyboard's Table.bubble).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class TextSceneWiringTest {

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun reset() {
        EngineState.tableKeyboardState.value = null
        EngineState.shuangpinScheme = ShuangpinScheme.XIAOHE
        EngineState.syllablesState.value = EngineState.SyllableState()
    }

    private fun entry(im: String, name: String) = InputMethodEntry(im, name, "", "", name.take(1), "zh_CN", "androidengine", false)

    private fun compare(form: String, im: InputMethodEntry, table: TableKeyboard? = null, inputType: Int = InputType.TYPE_CLASS_TEXT, composing: Boolean = false): Set<String> {
        return KeyboardHarness(im = im).start(inputType).use { h ->
            EngineState.tableKeyboardState.value = table
            if (composing) EngineState.syllablesState.value = EngineState.SyllableState(im.uniqueName, true, listOf("a"), 1)
            h.idle()
            h.relayout()
            val k = KeyboardHarness.all(h.keyboard).filterIsInstance<TextKeyboard>().single()
            // a code typed: a table's keyboard shows 确定 (confirm) in Return's place
            if (composing) {
                k.onReturnDrawableUpdate(ReturnKeyAppearance.Enter.copy(composing = true))
                h.relayout()
            }
            val scene = KeyboardHarness.all(k).filterIsInstance<KeyboardCanvasView>().single().scene!!
            val old = TextKeyboard.Layout.flatten().associateBy { it.name }
            val wasLines = mutableListOf<String>()
            val nowLines = mutableListOf<String>()
            for (node in scene.keys) {
                val was = old[node.id] ?: error("$form ${node.id}: no view's key")
                val now = k.sceneKey(node)!!.def as SceneKeyDef
                val at = "$form ${node.id}"
                val letter = node.id.length == 1 && node.id[0] in 'a'..'z'
                assertEquals(at, was.shortcut, now.shortcut)
                assertEquals(at, was.swipesBothWays, now.swipesBothWays)
                assertEquals(at, actions(was, letter), actions(now, letter))
                assertEquals(at, popups(was, letter), popups(now, letter))
                // confirm (a table's 确认 while composing) sounds as Return does, its view as a letter did
                // its sound, its space's and backspace's sideways swipe, and whether it takes a touch (a blank view
                // took none): as its view's
                assertEquals(at, if (node.id == "confirm") InputFeedbacks.SoundEffect.Return else was.appearance.soundEffect, now.sound)
                assertEquals(at, was is SpaceKey, now.isSpace)
                assertEquals(at, was is BackspaceKey, now.isBackspace)
                assertEquals(at, was !is BlankKey, k.sceneKey(node)!!.keyEnabled)
                val sound = if (node.id == "confirm") InputFeedbacks.SoundEffect.Return else was.appearance.soundEffect
                wasLines += WiringSnapshot.line(node.id, was.shortcut, was.swipesBothWays, actions(was, letter), popups(was, letter), sound, was is SpaceKey, was is BackspaceKey, was !is BlankKey)
                nowLines += WiringSnapshot.line(node.id, now.shortcut, now.swipesBothWays, actions(now, letter), popups(now, letter), now.sound, now.isSpace, now.isBackspace, k.sceneKey(node)!!.keyEnabled)
            }
            WiringSnapshot.check("qwerty-${form.replace(' ', '-')}", wasLines, nowLines)
            EngineState.syllablesState.value = EngineState.SyllableState()
            scene.keys.map { it.id }.toSet()
        }
    }

    @Test
    fun pinyinsKeys() {
        val ids = compare("pinyin", KeyboardHarness.PINYIN)
        assertTrue(ids.toString(), ids.containsAll(listOf("q", "m", "shift", "comma", "period", "enter")))
    }

    @Test
    fun englishsKeys() {
        compare("english", KeyboardHarness.ENGLISH)
    }

    @Test
    fun shuangpinsKeysWithItsSemicolon() {
        EngineState.shuangpinScheme = ShuangpinScheme.MICROSOFT
        val ids = compare("shuangpin", entry(InputMethodCatalog.SHUANGPIN, "双拼"))
        assertTrue(ids.toString(), "semicolon" in ids)
    }

    @Test
    fun wubisKeysWithItsRootsAndLookUp() {
        val ids = compare("wubi", entry(InputMethodCatalog.WUBI, "五笔"), TableKeyboard(InputMethodCatalog.WUBI, emptySet(), false, KeyLabels.NAMES, TableRoots.WUBI86))
        assertTrue(ids.toString(), "lookup" in ids)
        val on = compare("wubi on", entry(InputMethodCatalog.WUBI, "五笔"), TableKeyboard(InputMethodCatalog.WUBI, emptySet(), true, KeyLabels.NAMES, TableRoots.WUBI86), composing = true)
        assertTrue(on.toString(), "lookup-on" in on && "confirm" in on)
    }

    @Test
    fun cangjiesKeysWithItsStar() {
        val ids = compare("cangjie", entry(InputMethodCatalog.CANGJIE, "仓颉"), TableKeyboard(InputMethodCatalog.CANGJIE, setOf('*'), false, KeyLabels.NAMES, TableRoots.CANGJIE))
        assertTrue(ids.toString(), "star" in ids)
    }

    @Test
    fun anAddressFieldsKeys() {
        val url = compare("url", KeyboardHarness.PINYIN, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        assertTrue(url.toString(), url.containsAll(listOf("slash", "dot", "domain")))
        val email = compare("email", KeyboardHarness.ENGLISH, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        assertTrue(email.toString(), "at" in email)
    }

    // the user's long press of q: its swipe types its first (TextKeyboard.swipeText reads the scene key's letter),
    // not the layout's built-in 1
    @Test
    fun aLettersSwipeIsTheUsersFirst() {
        prefs.set(AppPrefs.getInstance().internal.popupOverrides, "q ⒜ 1")
        KeyboardHarness(im = KeyboardHarness.ENGLISH).start().use { h ->
            val q = h.keys().single { it.id == "q" }
            h.swipe(q, 0f, q.bounds.height().toFloat())
            h.idle()
            // FakeHostApi's sendKey of 1 (by char, key name or keysym): the built-in's 1 not typed
            assertTrue(h.api.calls.toString(), h.api.calls.any { "⒜" in it } && h.api.calls.none { it.startsWith("sendKey(char=1,") || it.startsWith("sendKey(key=1,") || it.startsWith("sendKey(sym=49,") })
        }
    }

    // a table's roots on the letters' faces: a letter pressed shows its letter in its bubble, as its view's did, not its roots
    @Test
    fun aLetterUnderItsRootsShowsItsLetter() {
        KeyboardHarness(im = entry(InputMethodCatalog.WUBI, "五笔")).start().use { h ->
            EngineState.tableKeyboardState.value = TableKeyboard(InputMethodCatalog.WUBI, emptySet(), false, KeyLabels.NAMES, TableRoots.WUBI86)
            h.idle()
            h.relayout()
            val g = h.keys().single { it.id == "g" }
            assertTrue(g.label, g.label?.lowercase() != "g")
            h.press(g)
            assertEquals(listOf("g"), h.popupChoices().map { it.text.lowercase() })
            h.release()
        }
    }

    // Shift redraws the letters' faces in capitals: each keeps its key (its bubble's content told it, SceneKeys.nodeKey),
    // not one made anew, and its bubble shows the capital
    @Test
    fun shiftKeepsTheLettersKeys() {
        KeyboardHarness(im = KeyboardHarness.ENGLISH).start().use { h ->
            val k = KeyboardHarness.all(h.keyboard).filterIsInstance<TextKeyboard>().single()
            val canvas = KeyboardHarness.all(k).filterIsInstance<KeyboardCanvasView>().single()
            val before = k.sceneKey(canvas.keyAt("q")!!)!!
            h.press(h.keys().single { it.id == "shift" })
            h.release()
            h.idle()
            val q = h.keys().single { it.id == "q" }
            assertEquals("Q", q.label)
            assertSame(before, k.sceneKey(canvas.keyAt("q")!!))
            h.press(q)
            assertEquals(listOf("Q"), h.popupChoices().map { it.text })
            h.release()
        }
    }

    // SceneKeys alone: two nodes of a key that differ in their bubble's content alone (the face's label) are one key,
    // the new content set on it (TextKeyboard's letters take theirs from Table.bubble, so its Shift does not show this)
    @Test
    fun aNodeDifferingInItsBubbleAloneKeepsItsKeyWithTheNewBubble() {
        KeyboardHarness(im = KeyboardHarness.ENGLISH).start().use { h ->
            val k = KeyboardHarness.all(h.keyboard).filterIsInstance<TextKeyboard>().single()
            val canvas = KeyboardHarness.all(k).filterIsInstance<KeyboardCanvasView>().single()
            val keys = SceneKeys(k) { _, _ -> }
            keys.byNodes(canvas, object : SceneKeyDef.Table {}) { }
            val q = canvas.keyAt("q")!!
            fun labelled(l: String) = with(q) {
                KeyNode(
                    id = id, copy = copy, kind = kind, role = role, shape = shape, bounds = bounds, hit = hit, anchor = anchor, up = up,
                    states = states, faces = faces, a11y = a11y, actions = actions, label = l, alt = alt, altRaw = altRaw, popup = popup,
                    menu = menu, sound = sound, shortcut = shortcut, letters = letters, lean = lean, face = face, margin = margin,
                )
            }
            val first = keys.of(labelled("q"))!!
            val again = keys.of(labelled("Q"))
            assertSame(first, again)
            assertEquals("Q", first.bubble)
        }
    }

    // a glide read where the scene draws the letters: the letters' centres the engine gets, in key widths, as
    // far apart as the keys shown
    @Test
    fun aGlideIsReadOnTheScene() {
        prefs.set(AppPrefs.getInstance().keyboard.keyDrawing, KeyDrawing.Glide)
        KeyboardHarness(im = KeyboardHarness.PINYIN).start().use { h ->
            val keys = "qpzm".map { h.key("$it") }
            val first = keys.first().bounds
            h.drag(keys.first(), first.exactCenterX(), first.exactCenterY(), steps = 0, lift = false)
            for (k in keys.drop(1)) h.move(k.bounds.exactCenterX(), k.bounds.exactCenterY())
            h.move(keys.last().bounds.exactCenterX(), keys.last().bounds.exactCenterY(), steps = 1, lift = true)
            val layout = h.api.glides.single().layout
            val q = layout.centre('q')
            for ((i, c) in "pzm".withIndex()) {
                val k = keys[i + 1].bounds
                val read = layout.centre(c)
                assertEquals("$c x", (k.exactCenterX() - first.exactCenterX()) / first.width(), read.first - q.first, 0.02f)
                assertEquals("$c y", (k.exactCenterY() - first.exactCenterY()) / first.width(), (read.second - q.second) * GlideTouch.unitY(first.height().toFloat()) / first.width(), 0.02f)
            }
        }
    }

    // split, G and V on both halves: a glide reads each of their copies where the scene draws it
    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun aSplitGlideReadsBothCopiesOfG() {
        prefs.set(AppPrefs.getInstance().keyboard.keyDrawing, KeyDrawing.Glide)
        prefs.set(ThemeManager.prefs.splitKeyboard, SplitKeyboardMode.Always)
        KeyboardHarness(im = KeyboardHarness.PINYIN).start().use { h ->
            val q = h.keys().single { it.id == "q" }
            val gs = h.keys().filter { it.id == "g" }
            assertEquals(2, gs.size)
            val first = q.bounds
            h.drag(q, first.exactCenterX(), first.exactCenterY(), steps = 0, lift = false)
            h.move(gs[0].bounds.exactCenterX(), gs[0].bounds.exactCenterY())
            h.move(gs[1].bounds.exactCenterX(), gs[1].bounds.exactCenterY(), steps = 1, lift = true)
            val layout = h.api.glides.single().layout
            val at = layout.centre('q')
            val unitY = GlideTouch.unitY(first.height().toFloat())
            for (g in gs) {
                val x = at.first + (g.bounds.exactCenterX() - first.exactCenterX()) / first.width()
                val y = at.second + (g.bounds.exactCenterY() - first.exactCenterY()) / unitY
                assertEquals("g at ${g.bounds}", 0f, layout.distance('g', x, y), 0.02f)
            }
        }
    }

    // a letter's in either case: TextKeyboard sends it as Shift has it
    private fun actions(def: KeyDef, letter: Boolean) = def.behaviors.map {
        val a = when (it) {
            is KeyDef.Behavior.Press -> it.action
            is KeyDef.Behavior.LongPress -> it.action
            is KeyDef.Behavior.Repeat -> it.action
            is KeyDef.Behavior.Swipe -> it.action
            is KeyDef.Behavior.DoubleTap -> it.action
        }
        "${it::class.simpleName} ${if (letter && a is KeyAction.HostKeyAction) a.copy(act = a.act.lowercase()) else a}"
    }.toSet()

    private fun popups(def: KeyDef, letter: Boolean) = def.popup.orEmpty().map { p ->
        when (p) {
            // a letter's bubble shows its letter as Shift has it (Table.bubble, transformAlphabet)
            is KeyDef.Popup.AltPreview -> "bubble ${if (letter) "letter" else p.content} ${p.alternative}"
            is KeyDef.Popup.Preview -> "bubble ${p.content}"
            is KeyDef.Popup.Keyboard.Explicit -> "keys ${p.items.toList()} ${p.symbolsFrom} ${p.asIs} ${p.inRow}"
            is KeyDef.Popup.Keyboard.Preset -> "preset ${if (letter) p.label.lowercase() else p.label} ${p.transformPunctuation}"
            is KeyDef.Popup.Menu -> "menu " + p.items.map { "${it.label} ${RuntimeEnvironment.getApplication().resources.getResourceEntryName(it.icon)} ${it.action}" }
            else -> "$p"
        }
    }.toSet()
}
