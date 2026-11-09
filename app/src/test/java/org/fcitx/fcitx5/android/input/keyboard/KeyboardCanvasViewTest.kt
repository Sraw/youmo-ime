/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.keyboard.canvas.AndroidTextMeasurer
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.input.keyboard.canvas.under
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.look.BoardBase
import org.fcitx.fcitx5.android.scene.look.Crop
import org.fcitx.fcitx5.android.scene.look.KeyFill
import org.fcitx.fcitx5.android.scene.look.KeyLabel
import org.fcitx.fcitx5.android.scene.look.KeyOverride
import org.fcitx.fcitx5.android.scene.look.KeyPressed
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.Picture
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * The 26 keys drawn by the scene engine: one view drawing them, the keys found by the scene's hit
 * areas, a press swapping one key's node, a screen reader's node a key.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class KeyboardCanvasViewTest {

    @get:Rule
    val prefs = PrefsRule()

    private fun harness(im: org.fcitx.fcitx5.android.core.InputMethodEntry = KeyboardHarness.PINYIN): KeyboardHarness =
        KeyboardHarness(im = im).start()

    private fun KeyboardHarness.canvas(): KeyboardCanvasView? =
        KeyboardHarness.boardCanvas(inputView)

    private fun KeyboardHarness.keyView(name: String): ShownKey = keyNamed(name)

    @Test
    fun oneViewDrawsTheKeys() = harness().use { h ->
        val c = h.canvas()!!
        val scene = c.scene!!
        assertEquals(26, scene.keys.count { it.id.length == 1 })
        // a touch finds every key where it is drawn; its cell and a popup's place over it found
        for (k in scene.keys) {
            assertSame(k.id, k, c.keyAt(k.anchor.x.toFloat(), k.anchor.y.toFloat()))
            val ref = KeyRef.scene(k.id, k.copy)
            assertTrue(k.id, c.cell(ref, Rect()))
            assertTrue(k.id, c.popupBounds(ref, Rect()))
        }
        assertEquals("q", c.keyAt("q")?.label)
        assertNull(c.keyAt("nothing"))
    }

    @Test
    fun aPressSwapsTheKeysNodeAlone() = harness().use { h ->
        val c = h.canvas()!!
        val q0 = c.shownNode("q")!!
        val w0 = c.shownNode("w")!!
        assertSame(q0, c.shownNode("q"))
        assertTrue(c.press("q"))
        h.idle()
        assertTrue(c.shownPressed("q"))
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
    fun aScreenReaderReadsAKeyANode() = harness().use { h ->
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
        assertTrue(space.contentDescription.isNotEmpty())
        val shift = nodes[keys.indexOfFirst { it.id == "shift" }]
        assertEquals(c.context.getString(R.string.a11y_key_shift), shift.contentDescription.toString())
        assertEquals(c.resources.getResourceName(R.id.button_caps), shift.viewIdResourceName)
        // a key's own click, through its node
        h.api.calls.clear()
        assertTrue(provider.performAction(keys.indexOfFirst { it.id == "w" }, AccessibilityNodeInfo.ACTION_CLICK, null))
        h.idle()
        assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") })
        // the hidden views are no one's to read
    }

    // a theme the engine refuses (more single keys than a 3.0 file brings, each a pressed colour of its own: more
    // styles than LookTheme.MAX_STYLES; a file's odd name no longer, LookTheme renames it): 060-render-p2 U8, the
    // built-in theme of its lightness drawn, logged; never the views again (they go in S8). Its keys
    // the built-in theme's as that theme draws them, touched and read as ever
    @Test
    fun aThemeNotConvertedTheBuiltInOneOfItsLightness() {
        val logged = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logged += message
            }
        }
        Timber.plant(tree)
        try {
            // the app's own (its look the object the fallback paints in)
            for (builtIn in listOf(ThemeManager.builtin("pixel#light"), ThemeManager.builtin("pixel#dark"))) {
                val look = builtIn.look
                val many = (0..LookTheme.MAX_STYLES / 2).associate { i -> "b$i/k" to KeyOverride(null, KeyPressed.Color(i), KeyLabel.Show) }
                val broken = Theme.Custom(
                    "broken", builtIn.isDark,
                    look.copy(keys = look.keys.copy(overrides = many), text = look.text.copy(keyFont = "/themes/x/bad.ttf")),
                )
                val drawn = KeyboardHarness(builtIn).start().use { h -> h.canvas()!!.scene!!.let { it.base to it.keys.map { k -> k.id to k.faces } } }
                logged.clear()
                KeyboardHarness(broken).start().use { h ->
                    val c = h.canvas()!!
                    val scene = c.scene
                    assertNotNull(scene)
                    assertEquals(builtIn.name, drawn, scene!!.base to scene.keys.map { k -> k.id to k.faces })
                    assertTrue(logged.toString(), logged.any { "broken" in it && builtIn.name in it })
                    // painted in the built-in's look, its fonts: none of the broken theme's
                    assertSame(ThemeManager.shownLook(builtIn), c.paintedIn)
                    // the broken theme read once, logged once: not again each scene asked (Shift, Return)
                    repeat(3) {
                        c.invalidateScene()
                        assertNotNull(c.scene)
                    }
                    assertEquals(logged.toString(), 1, logged.count { it.startsWith("scene theme broken") })
                    assertEquals(logged.toString(), 1, logged.count { "no scene of theme broken" in it })
                    // a frame of the keys in the built-in's fonts lets go of none of the broken theme's
                    // files (its candidates', its picture): its font not read again after it
                    val own = ThemeManager.shownLook(broken)
                    val font = own.text.keyFont!!
                    val real = ThemeAssets.decoder
                    var reads = 0
                    ThemeAssets.decoder = object : ThemeAssets.Decoder by real {
                        override fun typeface(path: String) = real.typeface(path).also { if (path == font) reads++ }
                    }
                    try {
                        ThemeAssets.typeface(own, font, 400)
                        val before = reads
                        c.draw(Canvas(Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)))
                        ThemeAssets.typeface(own, font, 400)
                        assertEquals(before, reads)
                    } finally {
                        ThemeAssets.decoder = real
                    }
                    h.idle()
                    // the canvas kept
                    assertSame(c, h.canvas())
                    h.api.calls.clear()
                    h.tap(h.key("q"))
                    assertTrue(h.api.calls.toString(), h.api.calls.any { it.startsWith("sendKey") })
                    assertNotNull(c.accessibilityNodeProvider!!.createAccessibilityNodeInfo(scene.keys.indexOfFirst { it.id == "q" }))
                }
            }
        } finally {
            Timber.uproot(tree)
        }
    }

    // no scene at all (the built-in theme's refused too, Host.scene null): nothing drawn, nothing for a
    // reader, logged; no other drawing. Asked again once what the keyboard shows changes
    @Test
    fun nothingMadeNothingDrawn() {
        val logged = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logged += message
            }
        }
        Timber.plant(tree)
        try {
            var asked = 0
            val ctx = RuntimeEnvironment.getApplication()
            val c = KeyboardCanvasView(
                ctx, org.fcitx.fcitx5.android.data.theme.BoardImages(),
                object : KeyboardCanvasView.Host {
                    override fun scene(width: Int, height: Int) = null.also { asked++ }
                    override fun key(node: org.fcitx.fcitx5.android.scene.KeyNode) = null
                },
            )
            c.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
            c.layout(0, 0, 400, 200)
            assertNull(c.scene)
            assertTrue(logged.toString(), logged.any { "none drawn" in it })
            val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
            c.draw(Canvas(bitmap))
            assertTrue(IntArray(400 * 200).also { bitmap.getPixels(it, 0, 400, 0, 0, 400, 200) }.all { it == 0 })
            assertNull(c.keyAt(200f, 100f))
            assertEquals(1, asked)
            // not asked again each frame; asked again as the keyboard changes
            c.draw(Canvas(bitmap))
            assertEquals(1, asked)
            c.invalidateScene()
            assertNull(c.scene)
            assertEquals(2, asked)
        } finally {
            Timber.uproot(tree)
        }
    }

    // what the env keeps (TextKeyboard's names, marks' settings, alts: by their inputs) follows each of
    // them: every setting a letter's corner or marks are made of changed in turn, the scene asked again
    // draws q (its corner, its marks, where they are) otherwise; a kept value missing an input would not
    @Test
    fun theKeptCornersAndMarksFollowEverySettingTheyAreMadeOf() {
        // q's texts and icons as its face draws them, and where
        fun q(h: KeyboardHarness): List<String> {
            h.idle()
            val c = h.canvas()!!
            c.invalidateScene()
            return c.scene!!.keys.first { it.id == "q" }.ops(false).mapNotNull {
                when (it) {
                    is Op.Text -> "text ${it.text} ${it.x.roundToInt()} ${it.baseline.roundToInt()} ${it.size}"
                    is Op.Icon -> "icon ${it.name} ${it.box}"
                    else -> null
                }
            }
        }
        // q's texts as [text, x, baseline, size]; its corner the one drawn at the corner's size (the letter's is
        // larger, a table's roots smaller), which the first step's known corner tells
        fun texts(ops: List<String>) = ops.filter { it.startsWith("text ") }.map { it.removePrefix("text ").split(" ") }
        var cornerSize = ""
        fun corner(ops: List<String>) = texts(ops).single { it[3] == cornerSize }
        // é, not a symbol: under Shift the long press's list is upper-cased, É, which a corner kept from before Shift is not
        val overrides = PopupOverrides.Empty.with("q", listOf("é", "1"))
        harness().use { h ->
            val board = h.textKeyboard()
            var was = q(h)
            fun changed(what: String, change: () -> Unit) {
                change()
                val now = q(h)
                assertTrue("$what: q drawn as before, $now", now != was)
                was = now
            }
            val internal = AppPrefs.getInstance().internal
            val keyboard = AppPrefs.getInstance().keyboard
            changed("q's long press the user's") { prefs.set(internal.popupOverrides, overrides.serialize()) }
            cornerSize = texts(was).single { it[0] == "é" }[3]
            changed("the punctuation mapped") { board.onPunctuationUpdate(mapOf("é" to "ω")) }
            assertEquals(was.toString(), "ω", corner(was)[0])
            changed("a shortcut on q") {
                prefs.set(internal.keyShortcuts, KeyShortcuts.Empty.with("full/q", KeyShortcut.Text("你好")).serialize())
            }
            changed("no swipe to shortcuts") { prefs.set(keyboard.swipeLayers, SwipeLayersChoice.SymbolDownOnly) }
            changed("the symbol elsewhere") { prefs.set(keyboard.punctuationPosition, KeyMarkPosition.BottomLeft) }
            // the setting makes the input view anew (its other changes go with it): the corner's place is what
            // tells the marks' kept spots followed it, left of the letter and below it
            val letter = texts(was).single { it[0] == "q" }
            val at = corner(was)
            assertTrue(was.toString(), at[1].toInt() < letter[1].toInt() && at[2].toInt() > letter[2].toInt())
            // Shift: the letter changes whatever the corner does; the corner is what tells the alts kept followed it
            val before = corner(was)[0]
            h.tap(h.keyView("shift"))
            val shifted = corner(q(h))[0]
            assertEquals(overrides.swipeOf("q", "Q"), shifted)
            assertNotEquals(before, shifted)
        }
        // a table's roots over the letters and not: the corner the letter itself, then the swipe again
        harness(WUBI).use { h ->
            try {
                EngineState.tableKeyboardState.value = wubi(KeyLabels.NONE)
                val none = q(h)
                assertNotEquals(none.toString(), "q", corner(none)[0])
                EngineState.tableKeyboardState.value = wubi(KeyLabels.ROOTS)
                val roots = q(h)
                assertEquals(roots.toString(), "q", corner(roots)[0])
                EngineState.tableKeyboardState.value = wubi(KeyLabels.NONE)
                assertEquals(none, q(h))
            } finally {
                EngineState.tableKeyboardState.value = null
            }
        }
    }

    // the keys a finger finds are the keys shown: as each state shows and hides keys, each of the scene's
    // keys found where it is
    @Test
    fun theScenesKeysAreTheShownViewsInEveryState() = harness(WUBI).use { h ->
        val c = h.canvas()!!
        val board = h.textKeyboard()
        fun check(what: String) {
            h.idle()
            h.relayout()
            val scene = c.scene!!
            assertTrue(what, scene.keys.count { it.id.length == 1 } >= 26)
            for (k in scene.keys) assertSame("$what ${k.id}", k, c.keyAt(k.anchor.x.toFloat(), k.anchor.y.toFloat()))
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
            // (a view hidden no longer hides its key: the 26 keys are wired from qwerty.xml's nodes, U4 B, the layout says what is shown)
        } finally {
            told(null)
        }
    }

    // pinyin's first key typed and its word committed in a plain field (Return ⏎ either way): the scene
    // kept, none made in the frame (a table's 确定 is another scene: made)
    @Test
    fun typingKeepsTheScene() = harness().use { h ->
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
        val m = AndroidTextMeasurer(SceneFonts(org.fcitx.fcitx5.android.data.theme.TestThemes.PixelLight.look))
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
    fun aTablesRootsAndKeys() = harness(WUBI).use { h ->
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
    fun aCornerIsTheUsersLongPressFirst() = harness().use { h ->
        val c = h.canvas()!!
        assertEquals("1", c.keyAt("q")?.alt)
        for (letter in 'a'..'z') assertEquals("$letter", h.keyView("$letter").alt, c.keyAt("$letter")?.alt)
    }

    // two fingers down on two keys: both shown pressed, each let go alone
    @Test
    fun twoFingersTwoKeysPressed() = harness().use { h ->
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
    fun shiftsStateToldToAScreenReader() = harness().use { h ->
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

    // the board's picture is the view's under the keyboard (ThemePictureDrawable.board): not laid a second
    // time under the keys, where a picture with alpha would come out darker than theme 3.0 drew it
    @Test
    fun theBoardsPictureIsNotDrawnTwice() {
        val board = pictured().let { Theme.Custom(it.name, it.isDark, it.look.copy(base = BoardBase.Picture)) }
        KeyboardHarness(board).start().use { h ->
            val scene = h.canvas()!!.scene!!
            assertTrue(scene.base.any { it is Op.Picture })
            assertTrue(scene.under(drawsBase = true).none { it is Op.Picture })
            // a blank place's picture face (the number pads'): a key's, not the board's, and stays
            val blank = Op.Picture(Shape.Rect(Box(0.0, 0.0, 40.0, 40.0)), 0.0, 90.0)
            val padded = Scene(scene.width, scene.height, scene.base + blank, scene.keys, scene.slots)
            assertEquals(listOf(blank), padded.under(drawsBase = true).filterIsInstance<Op.Picture>())
        }
    }

    // the view moved over the picture (one-handed to the other side): the keys showing it recorded
    // again where they are now, the others kept
    @Test
    fun aKeyShowingThePictureRecordedAgainWhenTheViewMoves() {
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
    fun theIndentBeforeAIsAs() = harness().use { h ->
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
        val popup = h.popupChoices().map { it.text }
        f.up(KeyboardHarness.Touch(x, a.height / 2f))
        assertTrue(popup.toString(), "@" in popup)
    }

    private fun KeyboardHarness.textKeyboard(): TextKeyboard = KeyboardHarness.all(inputView).filterIsInstance<TextKeyboard>().first()

    // the light theme with a picture (a file that is not there: it never reads) shown through the function keys
    private fun pictured(): Theme.Custom {
        val light = org.fcitx.fcitx5.android.data.theme.TestThemes.PixelLight.look
        return Theme.Custom(
            "pictured", false,
            light.copy(
                picture = Picture("/themes/p/src.jpg", Crop("/themes/p/p.jpg", null, 0), null, 80, 0),
                keys = light.keys.copy(function = light.keys.function.copy(fill = KeyFill.Picture(0, 90))),
            ),
        )
    }

    private fun wubi(labels: KeyLabels, on: Boolean = false) = TableKeyboard(InputMethodCatalog.WUBI, emptySet(), on, labels, TableRoots.WUBI86)

    // a theme's fall back logged once a look, the last 16 kept: a set no theme switching grows past that
    @Test
    fun aFallBackIsLoggedOnceALookTheLastSixteenKept() {
        val look = ThemeManager.shownLook(TestThemes.PixelLight)
        val names = (0 until 17).map { "fell-back-$it" }
        assertTrue(org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard.firstFallBack(names[0], look))
        assertFalse(org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard.firstFallBack(names[0], look))
        for (n in names.drop(1)) assertTrue(n, org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard.firstFallBack(n, look))
        // the first let go of: logged again
        assertTrue(org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard.firstFallBack(names[0], look))
        assertFalse(org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard.firstFallBack(names[16], look))
    }

    // a harness closed lets go of its input method's window: Robolectric keeps every dialog shown till the
    // test ends (ShadowDialog.shownDialogs), and the window holds the whole service (the S4a review's OOM)
    @Test
    fun aHarnessClosedLetsGoOfItsWindow() {
        val windows = (0 until 3).map {
            KeyboardHarness(TestThemes.PixelLight).start().use { h ->
                val w = h.service.window
                assertTrue(org.robolectric.shadows.ShadowDialog.getShownDialogs().any { it === w })
                w
            }
        }
        assertTrue(windows.none { w -> org.robolectric.shadows.ShadowDialog.getShownDialogs().any { it === w } })
    }

    companion object {
        const val PORTRAIT = "w393dp-h851dp-port-440dpi"

        private val WUBI = org.fcitx.fcitx5.android.core.InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "", "五", "zh_CN", "androidengine", false)

    }
}
