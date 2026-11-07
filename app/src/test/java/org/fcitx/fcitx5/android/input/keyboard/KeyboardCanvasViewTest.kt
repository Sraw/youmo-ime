/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityEventCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.canvas.AndroidTextMeasurer
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.input.theme.Crop
import org.fcitx.fcitx5.android.input.theme.KeyFill
import org.fcitx.fcitx5.android.input.theme.Picture
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The 26 keys drawn by the scene engine (AppPrefs.Internal.canvasKeyboard): one view drawing them,
 * the keys found by the scene's hit areas, a press swapping one key's node, a screen reader's node
 * a key; and every gesture doing what it does on the keys' own views.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class KeyboardCanvasViewTest {

    @get:Rule
    val prefs = PrefsRule()

    private fun harness(on: Boolean, im: org.fcitx.fcitx5.android.core.InputMethodEntry = KeyboardHarness.PINYIN): KeyboardHarness {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, on)
        return KeyboardHarness(im = im).start()
    }

    private fun KeyboardHarness.canvas(): KeyboardCanvasView? =
        KeyboardHarness.all(inputView).filterIsInstance<KeyboardCanvasView>().firstOrNull()

    private fun KeyboardHarness.keyView(name: String): KeyView =
        KeyboardHarness.all(inputView).filterIsInstance<KeyView>().first { it.themeKey == name && it.parent is View && (it.parent as View).parent is TextKeyboard }

    @Test
    fun offTheKeysDrawThemselves() = harness(false).use { h ->
        assertNull(h.canvas())
        assertTrue(h.shown(h.keyView("q")))
    }

    @Test
    fun onOneViewDrawsTheKeysTheirViewsNot() = harness(true).use { h ->
        val c = h.canvas()!!
        val q = h.keyView("q")
        assertFalse(h.shown(q))
        // laid out where they were: a popup and the glide trail still find them there
        assertTrue(q.width > 0)
        val scene = c.scene!!
        assertEquals(26, scene.keys.count { it.id.length == 1 })
        // every key the scene draws has its view, at the same place
        for (k in scene.keys) {
            val v = h.keyView(k.id)
            val box = h.box(v)
            val b = c.bounds(k.id)!!
            assertTrue("${k.id} at $b, its view at $box", box.contains(b.centerX(), b.centerY()))
            assertSame(k.id, v, c.keyViewAt(k.anchor.x.toFloat(), k.anchor.y.toFloat()))
        }
        assertEquals("q", c.keyAt("q")?.label)
        assertNull(c.keyAt("nothing"))
    }

    @Test
    fun aPressSwapsTheKeysNodeAlone() = harness(true).use { h ->
        val c = h.canvas()!!
        val q0 = c.shownNode("q")!!
        val w0 = c.shownNode("w")!!
        assertSame(q0, c.shownNode("q"))
        assertTrue(c.press("q"))
        h.idle()
        assertTrue(h.keyView("q").isPressed)
        val q1 = c.shownNode("q")
        assertNotSame(q0, q1)
        assertSame(w0, c.shownNode("w"))
        c.press("q", down = false)
        h.idle()
        // the face let go is the one it had, still recorded
        assertSame(q0, c.shownNode("q"))
        assertTrue(q0.hasDisplayList())
        // Shift relabels the letters: their nodes anew, the rest kept
        val space = c.shownNode("space")
        h.tap(h.keyView("shift"))
        assertNotSame(q0, c.shownNode("q"))
        assertSame(space, c.shownNode("space"))
        assertEquals("Q", c.keyAt("q")?.label)
    }

    @Test
    fun aScreenReaderReadsAKeyANode() = harness(true).use { h ->
        val c = h.canvas()!!
        val provider = c.accessibilityNodeProvider!!
        val root = provider.createAccessibilityNodeInfo(View.NO_ID)!!
        val keys = c.scene!!.keys
        assertEquals(keys.size, root.childCount)
        val nodes = keys.indices.map { provider.createAccessibilityNodeInfo(it)!! }
        val q = nodes[keys.indexOfFirst { it.id == "q" }]
        assertEquals("q", q.text.toString())
        assertTrue(q.isClickable)
        val space = nodes[keys.indexOfFirst { it.id == "space" }]
        assertEquals(c.resources.getResourceName(R.id.button_space), space.viewIdResourceName)
        assertEquals(h.keyView("space").contentDescription.toString(), space.contentDescription.toString())
        val shift = nodes[keys.indexOfFirst { it.id == "shift" }]
        assertEquals(c.context.getString(R.string.a11y_key_shift), shift.contentDescription.toString())
        assertEquals(c.resources.getResourceName(R.id.button_caps), shift.viewIdResourceName)
        // a key's own click, through its node
        h.api.calls.clear()
        assertTrue(provider.performAction(keys.indexOfFirst { it.id == "w" }, AccessibilityNodeInfo.ACTION_CLICK, null))
        h.idle()
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") })
        // the hidden views are no one's to read
        val own = AccessibilityNodeInfo.obtain()
        h.keyView("q").onInitializeAccessibilityNodeInfo(own)
        assertFalse(own.isVisibleToUser)
    }

    // the same fingers on both: what reaches the engine and the field, and the popups shown, the same
    private fun gestures(on: Boolean, im: org.fcitx.fcitx5.android.core.InputMethodEntry): List<Any> = harness(on, im).use { h ->
        val out = mutableListOf<Any>()
        h.field.setText("hello world")
        h.field.setSelection(5)
        h.idle()
        h.press(h.keyView("q"))
        h.release()
        h.swipe(h.keyView("w"), 0f, -h.box(h.keyView("w")).height().toFloat())
        out.add(h.longPress(h.keyView("e")))
        h.swipe(h.keyView("space"), -h.box(h.keyView("space")).width() / 2f, 0f)
        h.press(h.keyView("backspace"))
        h.release()
        h.swipe(h.keyView("backspace"), -h.box(h.keyView("space")).width() / 2f, 0f)
        out.add(h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") || it.startsWith("reset") })
        out += h.field.text.toString()
        out += h.field.selectionStart
        out
    }

    @Test
    fun everyGestureAsOnTheViewsPinyin() = assertEquals(gestures(false, KeyboardHarness.PINYIN), gestures(true, KeyboardHarness.PINYIN))

    @Test
    fun everyGestureAsOnTheViewsEnglish() = assertEquals(gestures(false, KeyboardHarness.ENGLISH), gestures(true, KeyboardHarness.ENGLISH))

    // the 26 keys, the views' and the scene's side by side in each case: for the eye (060's comparison,
    // build/outputs/canvas-compare), and close enough pixel for pixel (what differs: specs/app.md, the canvas)
    // [set] the case's preferences, giving back what puts each back: the next case starts from the defaults
    private class Case(val name: String, val theme: Theme, val qualifiers: String? = null, val set: () -> List<() -> Unit> = { emptyList() })

    private fun <T : Any> ManagedPreference<T>.during(value: T): () -> Unit {
        val was = getValue()
        setValue(value)
        return { setValue(was) }
    }

    @Test
    fun sideBySide() {
        val keyboard = AppPrefs.getInstance().keyboard
        val cases = listOf(
            Case("pixel-light", ThemePreset.PixelLight),
            Case("material-light", ThemePreset.MaterialLight),
            Case("nord-light", ThemePreset.NordLight),
            Case("pixel-dark", ThemePreset.PixelDark),
            Case("material-dark", ThemePreset.MaterialDark),
            Case("amoled-black", ThemePreset.AMOLEDBlack),
            Case("compact", ThemePreset.PixelLight) {
                val compact = KeyboardHeightLevels.ALL[KeyboardHeightLevels.COMPACT]
                listOf(keyboard.keyboardHeightPercent.during(compact.portrait), keyboard.keyboardHeightPercentLandscape.during(compact.landscape))
            },
            Case("landscape", ThemePreset.PixelLight, LANDSCAPE) { listOf(ThemeManager.prefs.splitKeyboard.during(SplitKeyboardMode.Off)) },
            Case("split", ThemePreset.PixelLight, LANDSCAPE) { listOf(ThemeManager.prefs.splitKeyboard.during(SplitKeyboardMode.Always)) },
        )
        val shares = cases.associate { it.name to differingShare(it) }
        val told = shares.entries.joinToString("\n") { (name, share) -> "$name ${"%.2f".format(share * 100)}%" }
        File("build/outputs/canvas-compare/shares.txt").writeText(told + "\n")
        assertTrue(told, shares.values.all { it < MOST_DIFFERING })
    }

    // the share of the pixels that differ between the views' keys and the scene's: a channel off by more
    // than anti-aliasing's (the letters a pixel over where a row's height is odd: specs/app.md, the
    // canvas); a key missing or misplaced is several percent
    private fun differingShare(case: Case): Double {
        prefs.set(ThemeManager.prefs.floatingKeyboard, false)
        val undo = case.set()
        case.qualifiers?.let(RuntimeEnvironment::setQualifiers)
        val shots = try {
            listOf(false, true).map { on ->
                prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, on)
                KeyboardHarness(case.theme).start().use { h ->
                    // past the keyboard's sliding in
                    h.after(1000)
                    h.relayout()
                    val v = h.keyboard
                    Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
                }
            }
        } finally {
            if (case.qualifiers != null) RuntimeEnvironment.setQualifiers(PORTRAIT)
            undo.asReversed().forEach { it() }
        }
        save(case.name, shots)
        val (old, new) = shots
        assertEquals(case.name, old.width to old.height, new.width to new.height)
        return differing(old, new, CHANNEL_SLACK).toDouble() / (old.width * old.height)
    }

    private fun save(name: String, shots: List<Bitmap>) {
        val title = 64
        val gap = 16
        val w = shots.sumOf { it.width } + gap
        val out = Bitmap.createBitmap(w, shots.maxOf { it.height } + title, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f; color = Color.BLACK }
        var x = 0f
        for ((i, s) in shots.withIndex()) {
            c.drawText(if (i == 0) "旧" else "新", x + s.width / 2f, 48f, paint)
            c.drawBitmap(s, x, title.toFloat(), null)
            x += s.width + gap
        }
        val file = File("build/outputs/canvas-compare/qwerty-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun differing(a: Bitmap, b: Bitmap, slack: Int): Int {
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return pa.indices.count { i ->
            val x = pa[i]
            val y = pb[i]
            (0 until 4).any { ch -> kotlin.math.abs((x ushr (ch * 8) and 0xff) - (y ushr (ch * 8) and 0xff)) > slack }
        }
    }

    // no scene (a theme the engine refuses: a font file's name 4.0 takes no file by): the views draw
    // the keys again and take the touches, nothing left blank
    @Test
    fun noSceneTheViewsDrawTheKeysAgain() {
        val light = org.fcitx.fcitx5.android.data.theme.ThemePreset.PixelLight.look
        val broken = Theme.Custom("broken", false, light.copy(text = light.text.copy(keyFont = "/themes/x/bad:name.ttf")))
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
        KeyboardHarness(broken).start().use { h ->
            val c = h.canvas()!!
            assertNull(c.scene)
            h.idle()
            assertNull(h.canvas())
            val q = h.keyView("q")
            assertTrue(h.shown(q))
            assertNull(q.onPressedChanged)
            h.api.calls.clear()
            h.tap(q)
            assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") })
        }
    }

    // the keys a finger finds are the keys shown: as each state shows and hides keys, the scene has
    // exactly the keys whose views are shown, and a hidden key's place finds no hidden view
    @Test
    fun theScenesKeysAreTheShownViewsInEveryState() = harness(true, WUBI).use { h ->
        val c = h.canvas()!!
        val board = h.textKeyboard()
        fun check(what: String) {
            h.idle()
            h.relayout()
            val scene = c.scene!!
            val shown = KeyboardHarness.all(board).filterIsInstance<KeyView>()
                .filter { it.visibility == View.VISIBLE && it.themeKey != null && it.parent is View && (it.parent as View).parent === board }
                .mapTo(HashSet()) { it.themeKey!! }
            assertEquals(what, shown, scene.keys.mapTo(HashSet()) { it.id })
            for (k in scene.keys) assertEquals("$what ${k.id}", View.VISIBLE, c.keyViewAt(k.anchor.x.toFloat(), k.anchor.y.toFloat())?.visibility)
        }
        fun told(t: TableKeyboard?) {
            EngineState.tableKeyboardState.value = t
        }
        try {
            check("a table, told nothing yet")
            told(wubi(KeyLabels.NONE))
            check("a table")
            told(wubi(KeyLabels.NONE, on = true))
            check("looking up")
            board.onReturnDrawableUpdate(ReturnKeyAppearance.Composing)
            check("a table with a code typed")
            board.onReturnDrawableUpdate(ReturnKeyAppearance.Enter)
            told(TableKeyboard(InputMethodCatalog.WUBI, setOf('*'), false, KeyLabels.ROOTS, TableRoots.WUBI86))
            check("roots, a code key")
            board.address = AddressKeys.URL
            check("an address")
            board.address = AddressKeys.EMAIL
            check("an e-mail address")
            board.address = AddressKeys.NONE
            prefs.set(AppPrefs.getInstance().keyboard.showLangSwitchKey, false)
            check("no language key")
            told(null)
            h.api.inputMethodEntryCached = KeyboardHarness.PINYIN
            h.emit(HostEvent.InputMethodChanged(KeyboardHarness.PINYIN))
            check("pinyin")
            // a key hidden since the scene was drawn: its place finds nothing till the next frame's
            val shift = c.keyAt("shift")!!
            h.keyView("shift").visibility = View.INVISIBLE
            assertNull(c.keyViewAt(shift.anchor.x.toFloat(), shift.anchor.y.toFloat()))
        } finally {
            told(null)
        }
    }

    // pinyin's first key typed and its word committed in a plain field (Return ⏎ either way): the scene
    // kept, none made in the frame (a table's 确定 is another scene: made)
    @Test
    fun typingKeepsTheScene() = harness(true).use { h ->
        val c = h.canvas()!!
        val board = h.textKeyboard()
        board.onReturnDrawableUpdate(ReturnKeyAppearance.Enter)
        val before = c.scene!!
        val made = board.scenesMade
        board.onReturnDrawableUpdate(ReturnKeyAppearance.Composing)
        assertSame(before, c.scene)
        board.onReturnDrawableUpdate(ReturnKeyAppearance.Enter)
        assertSame(before, c.scene)
        assertEquals(made, board.scenesMade)
        // something shown changed: made again
        h.api.inputMethodEntryCached = WUBI
        h.emit(HostEvent.InputMethodChanged(WUBI))
        h.idle()
        assertNotSame(before, c.scene)
        val tables = board.scenesMade
        board.onReturnDrawableUpdate(ReturnKeyAppearance.Composing)
        assertNotNull(c.scene!!.keys.firstOrNull { it.id == "confirm" })
        assertEquals(tables + 1, board.scenesMade)
    }

    // a text measured once at a font and size, then kept; the least recently asked let go past the limit
    @Test
    fun aTextMeasuredOnce() {
        val m = AndroidTextMeasurer(SceneFonts(org.fcitx.fcitx5.android.data.theme.ThemePreset.PixelLight.look))
        val a = m.measure("a", "system", 400, 40.0)
        assertEquals(a, m.measure("a", "system", 400, 40.0))
        assertEquals(1, m.measured)
        m.measure("a", "system", 700, 40.0)
        m.measure("a", "system", 400, 41.0)
        assertEquals(3, m.measured)
        repeat(AndroidTextMeasurer.KEPT) { m.measure("$it", "system", 400, 40.0) }
        m.measure("a", "system", 400, 40.0)
        assertEquals(4 + AndroidTextMeasurer.KEPT, m.measured)
    }

    // a table's roots over the letters, its 拼 key in Shift's place, drawn by the scene as its views show them
    @Test
    fun aTablesRootsAndKeys() = harness(true, WUBI).use { h ->
        val c = h.canvas()!!
        val wubi = wubi(KeyLabels.ROOTS)
        try {
            EngineState.tableKeyboardState.value = wubi
            h.idle()
            h.relayout()
            val g = c.keyAt("g")!!
            assertEquals(wubi.label('g'), g.label)
            assertEquals("g", g.alt)
            val two = ('a'..'z').first { '\n' in wubi.label(it).orEmpty() }
            val lines = c.keyAt("$two")!!.ops(false).filterIsInstance<Op.Text>().map { it.text }
            assertTrue(lines.toString(), wubi.label(two)!!.split('\n').all { it in lines })
            assertNotNull(c.keyAt("lookup"))
            assertNull(c.keyAt("shift"))
            // its names: one each, the letter in the corner still
            EngineState.tableKeyboardState.value = wubi.copy(labels = KeyLabels.NAMES)
            h.idle()
            assertEquals(wubi.copy(labels = KeyLabels.NAMES).label('g'), c.keyAt("g")?.label)
            // none: the letters, their corners their swipes again
            EngineState.tableKeyboardState.value = wubi.copy(labels = KeyLabels.NONE)
            h.idle()
            assertEquals("g", c.keyAt("g")?.label)
            assertEquals("=", c.keyAt("g")?.alt)
        } finally {
            EngineState.tableKeyboardState.value = null
        }
    }

    // the user's own long press first on a letter: its corner as the views show it, Shift's list under Shift
    @Test
    fun aCornerIsTheUsersLongPressFirst() = harness(true).use { h ->
        val c = h.canvas()!!
        assertEquals("1", c.keyAt("q")?.alt)
        assertEquals((h.keyView("q") as AltTextKeyView).altText.text.toString(), c.keyAt("q")?.alt)
        for (letter in 'a'..'z') {
            assertEquals("$letter", (h.keyView("$letter") as AltTextKeyView).altText.text.toString().ifEmpty { null }, c.keyAt("$letter")?.alt)
        }
    }

    // two fingers down on two keys: both shown pressed, each let go alone
    @Test
    fun twoFingersTwoKeysPressed() = harness(true).use { h ->
        val c = h.canvas()!!
        val q = c.bounds("q")!!
        val p = c.bounds("p")!!
        val target = (h.inputView.parent as? ViewGroup) ?: h.inputView
        val down = SystemClock.uptimeMillis()
        val props = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val at = arrayOf(
            MotionEvent.PointerCoords().apply { x = q.exactCenterX(); y = q.exactCenterY() },
            MotionEvent.PointerCoords().apply { x = p.exactCenterX(); y = p.exactCenterY() },
        )
        fun send(action: Int, n: Int) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, n, props, at, 0, 0, 1f, 1f, 0, 0, 0, 0)
            target.dispatchTouchEvent(e)
            e.recycle()
            h.idle()
        }
        send(MotionEvent.ACTION_DOWN, 1)
        assertTrue(c.shownPressed("q"))
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        assertTrue(c.shownPressed("q") && c.shownPressed("p"))
        // each its own node: a press swaps that key's alone
        assertNotSame(c.shownNode("q"), c.shownNode("p"))
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        assertTrue(c.shownPressed("q"))
        assertFalse(c.shownPressed("p"))
        send(MotionEvent.ACTION_UP, 1)
        assertFalse(c.shownPressed("q"))
    }

    // Shift's state told to a screen reader on its node, as its view's was
    @Test
    fun shiftsStateToldToAScreenReader() = harness(true).use { h ->
        val c = h.canvas()!!
        val events = mutableListOf<AccessibilityEvent>()
        ViewCompat.setAccessibilityDelegate(h.textKeyboard(), object : AccessibilityDelegateCompat() {
            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                if (child === c) events += AccessibilityEvent.obtain(event)
                return false
            }
        })
        shadowOf(c.context.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        val shift = c.scene!!.keys.indexOfFirst { it.id == "shift" }
        h.tap(h.keyView("shift"))
        c.scene
        val told = events.filter { it.contentChangeTypes and AccessibilityEventCompat.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0 }
        assertEquals(events.toString(), 1, told.size)
        val node = c.accessibilityNodeProvider!!.createAccessibilityNodeInfo(shift)!!
        // the compat's: a node's own stateDescription is API 30's, not Android 10's
        assertEquals(c.context.getString(R.string.a11y_state_shift_once), AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString())
    }

    // the view moved over the picture (one-handed to the other side): the keys showing it recorded
    // again where they are now, the others kept
    @Test
    fun aKeyShowingThePictureRecordedAgainWhenTheViewMoves() {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
        KeyboardHarness(pictured()).start().use { h ->
            val c = h.canvas()!!
            assertTrue(c.keyAt("shift")!!.ops(false).any { it is Op.Picture })
            c.beforeFrame()
            val shift = c.shownNode("shift")
            val q = c.shownNode("q")
            c.beforeFrame()
            assertSame(shift, c.shownNode("shift"))
            h.textKeyboard().offsetLeftAndRight(40)
            c.beforeFrame()
            assertNotSame(shift, c.shownNode("shift"))
            assertSame(q, c.shownNode("q"))
        }
    }

    // a frame drawn while the picture is still being decoded missed it (drawn again once in); one
    // drawn after it did not read (no such file) misses nothing: it will never come, no frame waits on it
    @Test
    fun missedOnlyWhileThePictureIsComing() {
        val held = ArrayList<Runnable>()
        val runner = ThemeAssets.runner
        ThemeAssets.runner = { held += it }
        try {
            prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
            KeyboardHarness(pictured()).start().use { h ->
                val c = h.canvas()!!
                fun frame() = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888).also { c.draw(Canvas(it)) }
                frame()
                assertTrue(c.missed)
                while (held.isNotEmpty()) held.removeAt(0).run()
                h.idle()
                frame()
                assertFalse(c.missed)
            }
        } finally {
            ThemeAssets.runner = runner
        }
    }

    // a finger down in the room before A (the row's indent), outside A's view: A's, its swipe and its long press as on A
    @Test
    fun theIndentBeforeAIsAs() = harness(true).use { h ->
        val a = h.keyView("a")
        val dp = h.service.resources.displayMetrics.density
        val board = h.textKeyboard()
        val aBox = h.box(a)
        // the indent: left of A's view, inside the board
        assertTrue(aBox.left - h.box(board).left > 4 * dp)
        val x = -2 * dp
        h.api.calls.clear()
        h.touch(a, KeyboardHarness.Touch(x, a.height / 2f), KeyboardHarness.Touch(x, a.height / 2f + 30 * dp, 16), KeyboardHarness.Touch(x, a.height / 2f + 60 * dp, 16))
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey(key=@,") })
        // held there: A's long press
        val f = h.finger(a, KeyboardHarness.Touch(x, a.height / 2f))
        h.after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
        val popup = h.popupKeys().map { it.text.toString() }
        f.up(KeyboardHarness.Touch(x, a.height / 2f))
        assertTrue(popup.toString(), "@" in popup)
    }

    private fun KeyboardHarness.textKeyboard(): TextKeyboard = KeyboardHarness.all(inputView).filterIsInstance<TextKeyboard>().first()

    // the light theme with a picture (a file that is not there: it never reads) shown through the function keys
    private fun pictured(): Theme.Custom {
        val light = org.fcitx.fcitx5.android.data.theme.ThemePreset.PixelLight.look
        return Theme.Custom(
            "pictured", false,
            light.copy(
                picture = Picture("/themes/p/src.jpg", Crop("/themes/p/p.jpg", null, 0), null, 80, 0),
                keys = light.keys.copy(function = light.keys.function.copy(fill = KeyFill.Picture(0, 90))),
            ),
        )
    }

    private fun wubi(labels: KeyLabels, on: Boolean = false) = TableKeyboard(InputMethodCatalog.WUBI, emptySet(), on, labels, TableRoots.WUBI86)

    companion object {
        const val PORTRAIT = "w393dp-h851dp-port-440dpi"

        // the same phone on its side: 851 dp wide, wider than the 600 split starts from
        private const val LANDSCAPE = "w851dp-h393dp-land-440dpi"

        private val WUBI = org.fcitx.fcitx5.android.core.InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "", "五", "zh_CN", "androidengine", false)

        // a channel off by this little is anti-aliasing; this share of the pixels so, at most
        private const val CHANNEL_SLACK = 24
        private const val MOST_DIFFERING = 0.015
    }
}
