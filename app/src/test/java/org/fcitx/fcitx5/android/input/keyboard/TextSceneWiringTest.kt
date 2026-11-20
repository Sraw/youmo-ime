/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.LayoutSource
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
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
 * The 26 keys wired from qwerty.xml do what the committed wiring says ([WiringSnapshot]) in each form. A letter's
 * press and long press are its letter in either case (TextKeyboard applies Shift), its bubble too, whatever its face.
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

    private fun entry(im: String, name: String) = InputMethodEntry(im, name, "", name.take(1), "zh_CN")

    private fun compare(form: String, im: InputMethodEntry, table: TableKeyboard? = null, inputType: Int = InputType.TYPE_CLASS_TEXT, composing: Boolean = false): Set<String> {
        return KeyboardHarness(im = im).start(inputType).use { h ->
            EngineState.tableKeyboardState.value = table
            if (composing) EngineState.syllablesState.value = EngineState.SyllableState(im.uniqueName, true, listOf("a"), 1)
            h.idle()
            h.relayout()
            val k = KeyboardHarness.all(h.keyboard).filterIsInstance<TextKeyboard>().single()
            // a table's keyboard shows 确定 in Return's place
            if (composing) {
                k.onReturnDrawableUpdate(ReturnKeyAppearance.Enter.copy(composing = true))
                h.relayout()
            }
            val scene = KeyboardHarness.all(k).filterIsInstance<KeyboardCanvasView>().single().scene!!
            val lines = scene.keys.map { node ->
                val now = k.sceneKey(node)!!.def as SceneKeyDef
                val letter = node.id.length == 1 && node.id[0] in 'a'..'z'
                WiringSnapshot.line(node.id, now.shortcut, now.swipesBothWays, actions(now, letter), popups(now, letter), WiringSnapshot.layoutSound(now.systemSound), now.isSpace, now.isBackspace, k.sceneKey(node)!!.keyEnabled)
            }
            WiringSnapshot.check("qwerty-${form.replace(' ', '-')}", lines)
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

    @Test
    fun aLettersSwipeIsTheUsersFirst() {
        prefs.setList("full/q", listOf("⒜", "1"), first = "⒜")
        KeyboardHarness(im = KeyboardHarness.ENGLISH).start().use { h ->
            val q = h.keys().single { it.id == "q" }
            h.swipe(q, 0f, -q.bounds.height().toFloat())
            h.idle()
            // FakeHostApi's sendKey of 1 (by char, key name or keysym)
            assertTrue(h.api.calls.toString(), h.api.calls.any { "⒜" in it } && h.api.calls.none { it.startsWith("sendKey(char=1,") || it.startsWith("sendKey(key=1,") || it.startsWith("sendKey(sym=49,") })
        }
    }

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

    // each letter keeps its key (SceneKeys.nodeKey), not one made anew, and its bubble shows the capital
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

    // two nodes differing in their bubble's content alone are one key with the new content
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

    // read where the scene draws the letters: the centres as far apart as the keys shown
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

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun aSplitGlideReadsBothCopiesOfG() {
        prefs.set(AppPrefs.getInstance().keyboard.keyDrawing, KeyDrawing.Glide)
        prefs.setStyle(KeyboardStyle.SPLIT)
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

    // nothing holds the key of id q to sending q (PackLayouts.judge asks none): with ids not what keys send, the glide read
    // another letter than a tap types. Every letter's id another's and P's key sending q: each letter is read where the key
    // sending it is drawn, q on Q's key alone, the first in the layout's order
    @Test
    @Uat("KB-33")
    fun aGlideReadsEachLetterWhereTheKeySendingItIsWhateverItsId() {
        prefs.set(AppPrefs.getInstance().keyboard.keyDrawing, KeyDrawing.Glide)
        val sourceWas = SceneLayouts.source
        SceneLayouts.source = { ctx ->
            val app = BuiltInLayouts(ctx)
            object : LayoutSource {
                override val key = "qwerty-ids-not-letters"

                override fun text(id: String) = if (id != "qwerty") app.text(id) else app.text(id)!!
                    .replace(Regex("<key id=\"([a-z])\" ")) { "<key id=\"${'a' + ('z' - it.groupValues[1].single())}\" " }
                    .replace("send=\"p\"", "send=\"q\"")
            }
        }
        try {
            KeyboardHarness(im = KeyboardHarness.PINYIN).start().use { h ->
                assertEquals("j", h.key("q").id)
                assertTrue(h.keys().map { it.label to it.id }.toString(), h.keys().none { k -> k.id?.singleOrNull()?.let { it in 'a'..'z' } == true && k.id == k.label })
                val keys = "qzm".map { h.key("$it") }
                val first = keys.first().bounds
                h.drag(keys.first(), first.exactCenterX(), first.exactCenterY(), steps = 0, lift = false)
                for (k in keys.drop(1)) h.move(k.bounds.exactCenterX(), k.bounds.exactCenterY())
                h.move(keys.last().bounds.exactCenterX(), keys.last().bounds.exactCenterY(), steps = 1, lift = true)
                val layout = h.api.glides.single().layout
                val q = layout.centre('q')
                for ((i, c) in "zm".withIndex()) {
                    val k = keys[i + 1].bounds
                    val read = layout.centre(c)
                    assertEquals("$c x", (k.exactCenterX() - first.exactCenterX()) / first.width(), read.first - q.first, 0.02f)
                }
                assertTrue("p read", 'p' !in layout)
                val p = h.key("p").bounds
                val across = (p.exactCenterX() - first.exactCenterX()) / first.width()
                assertEquals("q's nearest key from P's", across, layout.distance('q', q.first + across, q.second), 0.02f)
                // the bubble too is the letter the key sends, not its id
                prefs.set(AppPrefs.getInstance().keyboard.keyDrawing, KeyDrawing.Off)
                h.press(h.key("q"))
                assertEquals(listOf("q"), h.popupChoices().map { it.text.lowercase() })
                h.release()
            }
        } finally {
            SceneLayouts.source = sourceWas
        }
    }

    // a letter's in either case (TextKeyboard applies Shift); a named key's swipe is the resolver's: left out
    private fun actions(def: KeyDef, letter: Boolean) = def.behaviors.filterNot { def.shortcut != null && it is KeyDef.Behavior.Swipe }.map {
        val a = when (it) {
            is KeyDef.Behavior.Press -> it.action
            is KeyDef.Behavior.LongPress -> it.action
            is KeyDef.Behavior.Repeat -> it.action
            is KeyDef.Behavior.Swipe -> it.action
            is KeyDef.Behavior.DoubleTap -> it.action
        }
        "${it::class.simpleName} ${if (letter && a is KeyAction.HostKeyAction) a.copy(act = a.act.lowercase()) else a}"
    }.toSet()

    // a named key's long press is the resolver's (KeyResolving.popup): left out
    private fun popups(def: KeyDef, letter: Boolean) = def.popup.orEmpty().filterNot { def.shortcut != null && it is KeyDef.Popup.Keyboard }.map { p ->
        when (p) {
            // a letter's bubble its letter as Shift has it (Table.bubble); a named key's swipe the resolver's: left out
            is KeyDef.Popup.AltPreview -> if (p.alternative.isEmpty() || def.shortcut != null) "bubble ${if (letter) "letter" else p.content}" else "bubble ${if (letter) "letter" else p.content} ${p.alternative}"
            is KeyDef.Popup.Preview -> "bubble ${p.content}"
            is KeyDef.Popup.Keyboard.Explicit -> "keys ${p.items.toList()} ${p.symbolsFrom} ${p.asIs} ${p.inRow}"
            is KeyDef.Popup.Keyboard.Preset -> "preset ${if (letter) p.label.lowercase() else p.label} ${p.transformPunctuation}"
            is KeyDef.Popup.Menu -> "menu " + p.items.map { "${it.label} ${RuntimeEnvironment.getApplication().resources.getResourceEntryName(it.icon)} ${it.action}" }
            else -> "$p"
        }
    }.toSet()
}
