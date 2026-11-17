/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.InputType
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.BuiltinThemeFiles
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.StatusEntry
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.bar.ui.idle.ClipboardSuggestionUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.candidates.floating.PaginationUi
import org.fcitx.fcitx5.android.input.editing.TextEditingButton
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.floating.FloatingHandleSide
import org.fcitx.fcitx5.android.input.floating.NoResize
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIconDrawable
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.ScenePainter
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry
import org.fcitx.fcitx5.android.input.status.StatusAreaEntryUi
import org.fcitx.fcitx5.android.scene.Icons
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 061-toolbar-icons in the app: a theme's `<icon>` of a bar's or a panel's name drawn there, in the place's colour;
 * a name it does not write the place's own image; the menu key's states; read ahead, never the app's first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class BarIconsThemeTest {

    @get:Rule
    val prefs = PrefsRule()

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val dir get() = ThemeFilesManager.themeDir
    private val runner = ThemeAssets.runner
    private val poster = ThemeAssets.poster
    private var harness: KeyboardHarness? = null

    @Before
    fun atOnce() {
        ThemeAssets.runner = { it.run() }
        ThemeAssets.poster = { it.run() }
        phoneStorage()
        dir.deleteRecursively()
        dir.mkdirs()
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeManager.refreshThemes()
        ScenePainter.forgetIcons()
    }

    @After
    fun putBack() {
        harness?.close()
        ThemeAssets.runner = runner
        ThemeAssets.poster = poster
        ScenePainter.forgetIcons()
        ThemeManager.prefs.followSystemDayNightTheme.setValue(ThemeManager.prefs.followSystemDayNightTheme.defaultValue)
        ThemeManager.setNormalModeTheme(ThemeManager.prefs.normalModeTheme.defaultValue)
        dir.deleteRecursively()
        ThemeManager.refreshThemes()
    }

    // a square filling its box: the icon's pixels its tint (or its own colour)
    private val square = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M0 0h24v24h-24z"/></svg>"""

    // a pack `mine<n>` (the built-in Nord's file under that name) with an icon of each of [names], imported and listed:
    // its dark version, the one the tests show
    private var packs = 0

    private fun pack(vararg names: String, styles: String = "", picture: Boolean = false): Theme.Pack {
        val name = "mine${packs++}"
        val icons = names.joinToString("") { """<icon name="$it" src="$it.svg"/>""" }
        var xml = BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_nord).replace("name=\"nord\"", "name=\"$name\"")
            .replace("</theme>", "$styles$icons</theme>")
        if (picture) {
            val first = xml.indexOf("<style ")
            xml = xml.substring(0, first) + """<picture src="p.png"><cut orientation="portrait" file="p.png"/></picture>""" + xml.substring(first)
        }
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use {
                it.putNextEntry(ZipEntry(ThemePackFiles.THEME_XML))
                it.write(xml.encodeToByteArray())
                for (n in names) {
                    it.putNextEntry(ZipEntry("$n.svg"))
                    it.write(square.encodeToByteArray())
                }
                if (picture) {
                    it.putNextEntry(ZipEntry("p.png"))
                    it.write(ByteArrayOutputStream().also { png -> Bitmap.createBitmap(PICTURE_W, PICTURE_H, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, png) }.toByteArray())
                }
            }
        }.toByteArray()
        val opened = ThemeFilesManager.openTheme(zip.inputStream()).getOrThrow() as ThemeFilesManager.Opened.Pack
        return opened.commit().getOrThrow().also { ThemeManager.packPlaced(it[0].file) }[1]
    }

    private fun shown(theme: Theme): KeyboardHarness {
        ThemeManager.setNormalModeTheme(theme)
        return KeyboardHarness(theme = theme).start().also { harness = it }
    }

    private fun s(id: Int) = ctx.getString(id)

    private fun KeyboardHarness.button(description: Int) = described(s(description)) as ToolButton

    private fun KeyboardHarness.expand() {
        tap(button(R.string.expand_toolbar))
        after(500)
        relayout()
    }

    private val Drawable?.themed get() = this is PackIconDrawable

    // [d]'s middle pixel drawn 24 px square
    private fun middle(d: Drawable): Int {
        val b = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, SIDE, SIDE)
        d.draw(Canvas(b))
        return b.getPixel(SIDE / 2, SIDE / 2)
    }

    @Test
    @Uat("ST-128")
    fun aThemesBarIconsAreDrawnTheOnesItDoesNotWriteTheApps() {
        val mine = pack("toolbar-expand", "toolbar-collapse", "undo", "more", "emoji")
        val h = shown(mine)
        assertTrue(h.button(R.string.expand_toolbar).iconDrawable.themed)
        h.expand()
        assertTrue(h.button(R.string.hide_toolbar).iconDrawable.themed)
        assertTrue(h.button(R.string.undo).iconDrawable.themed)
        assertTrue(h.button(R.string.status_area).iconDrawable.themed)
        // none of its own: the app's, as ever
        val redo = h.button(R.string.redo)
        assertFalse(redo.themed)
        assertNotNull(redo.iconDrawable)
        assertFalse(redo.iconDrawable.themed)
        // the strip of the clipboard's last copy: its `clipboard`, here none
        assertFalse(ClipboardSuggestionUi(ctx, mine).iconDrawable.themed)
        assertTrue(ClipboardSuggestionUi(ctx, pack("clipboard")).iconDrawable.themed)
    }

    @Test
    @Uat("ST-128")
    fun aBuiltInThemeDrawsTheAppsAgain() {
        pack("undo", "toolbar-expand")
        val h = shown(ThemeManager.prefs.normalModeTheme.defaultValue)
        assertFalse(h.button(R.string.expand_toolbar).iconDrawable.themed)
        h.expand()
        assertFalse(h.button(R.string.undo).iconDrawable.themed)
        assertNotNull(h.button(R.string.undo).iconDrawable)
    }

    // one name, one meaning: a 0.6.0 theme's `undo` (written for the keys) is the toolbar's too
    @Test
    @Uat("ST-129")
    fun aThemesUndoIsTheToolbarsAndTheShortcutsAlike() {
        val mine = pack("undo")
        val h = shown(mine)
        h.expand()
        assertTrue(h.button(R.string.undo).iconDrawable.themed)
        // the key's shortcut drawn by the same name, which the theme's icons hold
        assertEquals("undo", SceneIcons.nameOf(KeyShortcutLooks.of(KeyCommand.Undo).icon))
        assertNotNull(PackIcons.of(mine).file("undo"))
    }

    @Test
    @Uat("ST-130")
    fun aThemeOfOneMenuStateDrawsThatOneTheOtherTheAppsTurnedNeitherTurned() {
        val h = shown(pack("toolbar-expand"))
        val menu = h.button(R.string.expand_toolbar)
        assertTrue(menu.iconDrawable.themed)
        assertEquals(0f, menu.iconRotation)
        h.expand()
        assertFalse(menu.iconDrawable.themed)
        // the app's ⌄ turned as the open toolbar's ever was, faded in, not turned to it
        assertEquals(90f, menu.iconRotation)
        assertEquals(1f, menu.iconAlpha)
        h.tap(menu)
        h.after(500)
        assertTrue(menu.iconDrawable.themed)
        assertEquals(0f, menu.iconRotation)
    }

    // the app's ⌄ drawn for the menu key, turned as ever (-90 folded, +90 open), however the theme has none drawn; each
    // row its own pack and bar
    @Test
    @Uat("ST-130")
    fun menuIconsNoneDrawnTurnTheAppsAsEver() {
        fun turnsAsEver(h: KeyboardHarness) {
            val menu = h.button(R.string.expand_toolbar)
            assertFalse(menu.iconDrawable.themed)
            assertEquals(-90f, menu.iconRotation)
            h.expand()
            assertEquals(90f, menu.iconRotation)
            assertFalse(menu.iconDrawable.themed)
        }

        // the two by name, their files broken (a restored backup's pack, no import's checks)
        fun broken(): Theme.Pack {
            val mine = pack("toolbar-expand", "toolbar-collapse")
            val icons = PackIcons.of(mine)
            for (name in listOf("toolbar-expand", "toolbar-collapse")) File(icons.file(name)!!).writeText("<svg><script/></svg>")
            ScenePainter.forgetIcons()
            return mine
        }

        fun row(make: () -> KeyboardHarness): () -> Unit = {
            try {
                turnsAsEver(make())
            } finally {
                harness?.close()
                harness = null
            }
        }
        assertRows(
            "no menu icon of the theme's" to row { shown(pack("undo")) },
            "the two that fail, the two states told apart (R26 F8310)" to row { shown(broken()) },
            // made while they were being read (counted the theme's then, upright), failed once read: turned at once,
            // not upright till the toolbar is first opened (R26 F8451)
            "the two that fail read after the bar is made" to row {
                val mine = broken()
                val queued = ArrayList<Runnable>()
                ThemeAssets.runner = { queued += it }
                try {
                    shown(mine).also {
                        assertEquals(0f, it.button(R.string.expand_toolbar).iconRotation)
                        while (queued.isNotEmpty()) queued.removeAt(0).run()
                        it.idle()
                    }
                } finally {
                    ThemeAssets.runner = { it.run() }
                }
            },
        )
    }

    // hide-keyboard's own images differ (▾ on the bar, the keyboard with ⌄ on a key): each its own till a theme writes it
    @Test
    @Uat("ST-130")
    fun hideKeyboardsImagesAreEachPlacesOwnTillAThemeWritesIt() {
        assertEquals(R.drawable.ic_baseline_arrow_drop_down_24, ToolbarButtonLooks.drawable(BarIcons.of(ToolbarButton.Hide).fallback))
        assertEquals(R.drawable.ic_baseline_keyboard_hide_24, SceneIcons.of("hide-keyboard"))
        assertEquals(R.drawable.ic_baseline_dialpad_24, ToolbarButtonLooks.drawable(BarIcons.of(ToolbarButton.Keyboard).fallback))
        assertEquals(R.drawable.ic_baseline_keyboard_24, SceneIcons.of("choose-keyboard"))
        assertFalse(ToolButton(ctx, BarIcons.HIDE_KEYBOARD, pack("undo")).iconDrawable.themed)
        val mine = pack("hide-keyboard")
        assertTrue(ToolButton(ctx, BarIcons.HIDE_KEYBOARD, mine).iconDrawable.themed)
        assertNotNull(PackIcons.of(mine).file("hide-keyboard"))
    }

    // the app's image drawn at its own size elsewhere first (another bar, a panel, an earlier case): a vector's raster
    // only ever grows, shared by every drawable of the resource, so the button's smaller icon came out sharper than
    // drawn alone (the low landscape bar's emoji, at random in a full run; fixed in 332983c57)
    @Test
    fun aButtonsIconIsDrawnAlikeWhateverDrewTheSameImageBefore() {
        val image = ctx.getDrawable(ToolbarButtonLooks.drawable(BarIcons.EMOJI.fallback))!!
        val side = image.intrinsicWidth * 5 / 6
        fun shot(): Bitmap {
            val b = ToolButton(ctx, BarIcons.EMOJI, ThemeManager.activeTheme)
            b.fixIconSize(side)
            val exactly = View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY)
            b.measure(exactly, exactly)
            b.layout(0, 0, side, side)
            return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { b.draw(Canvas(it)) }
        }
        val first = shot()
        image.setBounds(0, 0, image.intrinsicWidth, image.intrinsicHeight)
        image.draw(Canvas(Bitmap.createBitmap(image.intrinsicWidth, image.intrinsicHeight, Bitmap.Config.ARGB_8888)))
        assertTrue(first.sameAs(shot()))
    }

    @Test
    @Uat("TB-64")
    fun aToggleOnDrawsItsIconInTheSelectedColour() {
        val mine = pack("one-handed", styles = """<style select="toolbar" icon-color="#ff00ff00"/><style select="toolbar:selected" icon-color="#ffff0000"/>""")
        val look = SceneBar.of(ctx, mine).toolbar
        assertTrue(look.icon != look.onIcon)
        val b = ToolButton(ctx, BarIcons.ONE_HANDED, mine)
        assertEquals(look.icon.value, middle(b.iconDrawable!!))
        b.setOn(true)
        assertTrue(b.iconDrawable.themed)
        assertEquals(look.onIcon.value, middle(b.iconDrawable!!))
        b.setOn(false)
        assertEquals(look.icon.value, middle(b.iconDrawable!!))
    }

    @Test
    @Uat("TB-64")
    fun tradSimpsTwoStatesAreTwoNames() {
        prefs.set(AppPrefs.getInstance().internal.toolbarButtons, "trad_simp,|,hide")
        val h = shown(pack("trad-simp-off"))
        val simplified = listOf(StatusEntry(7, "chttrans", "fcitx-chttrans-inactive", "", false))
        h.api.statusAreaCached = simplified
        h.emit(HostEvent.StatusArea(simplified, KeyboardHarness.PINYIN))
        h.expand()
        assertTrue(h.button(R.string.status_simplified).iconDrawable.themed)
        val traditional = listOf(StatusEntry(7, "chttrans", "fcitx-chttrans-active", "", true))
        h.emit(HostEvent.StatusArea(traditional, KeyboardHarness.PINYIN))
        h.relayout()
        assertFalse(h.button(R.string.status_traditional).iconDrawable.themed)
    }

    // T4: a name the theme writes is never the app's image first, then the theme's; one it does not write is there at once
    @Test
    @Uat("TB-64")
    fun aThemesIconNotReadYetIsNothingTillItIsNeverTheAppsFirst() {
        val mine = pack("undo")
        ScenePainter.forgetIcons()
        val queued = ArrayList<Runnable>()
        ThemeAssets.runner = { queued += it }
        val undo = ToolButton(ctx, BarIcons.UNDO, mine)
        val redo = ToolButton(ctx, BarIcons.REDO, mine)
        assertNull(undo.iconDrawable)
        assertNotNull(redo.iconDrawable)
        assertEquals(1, queued.size)
        queued.single().run()
        assertTrue(undo.iconDrawable.themed)
        // read ahead (ThemeManager, the theme picked): a bar made after is drawn the theme's at once
        ScenePainter.forgetIcons()
        queued.clear()
        BarIconDrawables.readAhead(PackIcons.of(mine))
        assertEquals(1, queued.size)
        queued.single().run()
        assertTrue(ToolButton(ctx, BarIcons.UNDO, mine).iconDrawable.themed)
    }

    @Test
    fun aBrokenIconIsTheApps() {
        val mine = pack("undo")
        java.io.File(PackIcons.of(mine).file("undo")!!).writeText("<svg><script/></svg>")
        ScenePainter.forgetIcons()
        val undo = ToolButton(ctx, BarIcons.UNDO, mine)
        assertNotNull(undo.iconDrawable)
        assertFalse(undo.iconDrawable.themed)
    }

    // the status panel's cells, two states of an engine's toggle two names; unikey's and the fallback never the theme's
    @Test
    @Uat("ST-132")
    fun theStatusPanelsCellsAndGearAreTheThemes() {
        val mine = pack("theme", "trad-simp-off", "punctuation", "keyboard-settings", "app-settings", "document-edit")
        val h = shown(mine)
        h.api.statusAreaCached = listOf(
            StatusEntry(1, "chttrans", "fcitx-chttrans-inactive", "", false),
            StatusEntry(2, "punctuation", "fcitx-punc-active", "", true),
            StatusEntry(3, "fullwidth", "fcitx-fullwidth-inactive", "", false),
            StatusEntry(4, "unikey", "document-edit", "U", false),
        )
        h.expand()
        h.tap(h.described(s(R.string.status_area)))
        h.idle()
        fun cell(label: String): Drawable? {
            val text = KeyboardHarness.all(h.inputView).first { h.shown(it) && it is TextView && it.text.toString() == label }
            val root = generateSequence(text) { it.parent as? View }.first { it.hasOnClickListeners() }
            return KeyboardHarness.all(root).filterIsInstance<ImageView>().single { it.visibility == View.VISIBLE }.drawable
        }
        assertTrue(cell(s(R.string.theme)).themed)
        assertTrue(cell(s(R.string.virtual_keyboard)).themed)
        assertTrue(cell(s(R.string.status_simplified)).themed)
        assertTrue(cell(s(R.string.status_punctuation_chinese)).themed)
        // not written: the app's
        assertFalse(cell(s(R.string.input_method_options)).themed)
        assertFalse(cell(s(R.string.status_half_width)).themed)
        assertTrue(h.button(R.string.open_input_method_settings).iconDrawable.themed)
        // unikey's no name of ours, though the theme has an icon so named
        val unikey = StatusAreaEntryUi(ctx, mine).apply { setEntry(StatusAreaEntry.fromEntry(ctx, StatusEntry(4, "unikey", "document-edit", "U", false))) }
        assertFalse(unikey.icon.drawable.themed)
    }

    // every cell of the app's own and every engine icon the app has an image of: a name a theme may write
    @Test
    fun everyCellHasAName() {
        for (t in StatusAreaEntry.Android.Type.entries) assertTrue(t.name, StatusAreaEntry.iconOf(t).name in Icons.ALL)
        for (icon in BarIcons.ENGINE_ICONS) {
            val e = StatusAreaEntry.fromEntry(ctx, StatusEntry(1, "x", icon, "", false))
            assertEquals(ToolbarButtonLooks.drawable(e.named!!.fallback), e.icon)
        }
        assertNull(StatusAreaEntry.fromEntry(ctx, StatusEntry(1, "x", "edit-find", "", false)).named)
    }

    @Test
    @Uat("ST-132", "TB-63")
    fun thePanelsButtonsDoingACommandsJobAreTheThemes() {
        val mine = pack("clipboard", "backspace", "floating-dock", "floating-resize")
        val h = shown(mine)
        h.expand()
        h.tap(h.button(R.string.text_editing))
        h.idle()
        val backspace = KeyboardHarness.all(h.inputView).filterIsInstance<TextEditingButton>().single { h.shown(it) && it.contentDescription == s(R.string.backspace) }
        assertTrue(backspace.imageView.drawable.themed)
        val clipboards = KeyboardHarness.all(h.inputView).filterIsInstance<ToolButton>().filter { h.shown(it) && it.contentDescription == s(R.string.clipboard) }.toList()
        assertTrue(clipboards.isNotEmpty())
        assertTrue(clipboards.all { it.iconDrawable.themed })
        // the arrows not a command's job: the app's
        val up = KeyboardHarness.all(h.inputView).filterIsInstance<TextEditingButton>().single { h.shown(it) && it.contentDescription == s(R.string.move_cursor_up) }
        assertFalse(up.imageView.drawable.themed)
        // the symbol panel's 123 and the emoticons' emoji are keys of the scene's, by the keys' names
        assertEquals("number-pad", SceneIcons.nameOf(R.drawable.ic_number_pad))
        assertEquals("emoji", SceneIcons.nameOf(R.drawable.ic_baseline_tag_faces_24))
        // the floating keyboard's strip
        val handle = FloatingHandle(ctx, mine, FloatingHandleSide.Top, FloatingHandle.Mode.Floating({ _, _ -> }, {}, {}, NoResize))
        assertTrue(handle.dockDrawable.themed)
        assertTrue((handle.resizeButton as ImageView).drawable.themed)
        // growing up and left, the theme's own drawn mirrored as the app's is (⤢ as ⤡)
        handle.showGrowth(down = false)
        assertTrue(handle.resizeButton!!.mirrored && handle.resizeButton!!.drawable.themed)
        // drawn mirrored: the left half of its picture is the right half's turned over
        val b = android.graphics.Bitmap.createBitmap(56, 24, android.graphics.Bitmap.Config.ARGB_8888)
        handle.resizeButton!!.apply { layout(0, 0, 56, 24); background = null; draw(android.graphics.Canvas(b)) }
        val straight = android.graphics.Bitmap.createBitmap(56, 24, android.graphics.Bitmap.Config.ARGB_8888)
        handle.showGrowth(down = true)
        assertFalse(handle.resizeButton!!.mirrored)
        handle.resizeButton!!.draw(android.graphics.Canvas(straight))
        assertTrue("drawn at all", (0 until 56).any { x -> (0 until 24).any { y -> straight.getPixel(x, y) != 0 } })
        assertTrue("mirrored", (0 until 56).all { x -> (0 until 24).all { y -> b.getPixel(x, y) == straight.getPixel(55 - x, y) } })
        assertFalse(FloatingHandle(ctx, pack("undo"), FloatingHandleSide.Top, FloatingHandle.Mode.Floating({ _, _ -> }, {}, {}, NoResize)).dockDrawable.themed)
    }

    // beside the keys: one-handed's way back to the whole width, the floating candidate window's page arrows
    @Test
    @Uat("ST-132")
    fun theButtonsBesideTheKeysAreTheThemes() {
        prefs.setStyle(KeyboardStyle.ONE_HANDED, side = OneHanded.Right)
        val h = shown(pack("one-handed-exit", "candidates-prev"))
        val exits = KeyboardHarness.all(h.inputView).filterIsInstance<ImageView>().filter { it.contentDescription == s(R.string.one_handed_exit) }.toList()
        assertEquals(2, exits.size)
        assertTrue(exits.all { it.drawable.themed && it.colorFilter == null })
        val moves = KeyboardHarness.all(h.inputView).filterIsInstance<ImageView>().filter { it.contentDescription == s(R.string.split_dock_move_left) }.toList()
        assertTrue(moves.isNotEmpty() && moves.none { it.drawable.themed })
        val paging = PaginationUi(ctx, ThemeManager.activeTheme)
        assertTrue(paging.prevIcon.drawable.themed)
        assertFalse(paging.nextIcon.drawable.themed)
        // the app's as ever: its resource in the window's colour
        assertNotNull(paging.nextIcon.colorFilter)
        assertFalse(PaginationUi(ctx, pack("undo")).prevIcon.drawable.themed)
    }

    // a hardware keyboard's floating candidate window: its way to the touch keyboard
    @Test
    @Uat("ST-132")
    @Config(qualifiers = "w393dp-h851dp-port-440dpi-keysexposed-qwerty")
    fun theFloatingCandidateWindowsKeyboardButtonIsTheThemes() {
        val h = shown(pack("touch-keyboard"))
        h.idle()
        val button = KeyboardHarness.all(h.service.window.window!!.decorView).filterIsInstance<ImageView>()
            .first { it.contentDescription == s(R.string.show_touch_keyboard) }
        assertTrue(button.drawable.themed)
    }

    // the hide button's ▾ turned the way a swipe goes (the number row's way) is the app's arrow; a theme's is no arrow
    @Test
    fun aThemesHideKeyboardIsNotTurnedBySwipes() {
        fun turnedBy(theme: Theme): Float {
            harness?.close()
            val h = shown(theme)
            h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            // the number row a password's bar is, put away by a swipe to the right: the toolbar's ▾ then turns to bring it back
            val row = KeyboardHarness.find<NumberRow>(h.bar)!!
            h.swipe(row, h.box(row).height() * 2f, 0f)
            h.relayout()
            val hide = h.button(R.string.hide_keyboard)
            val box = h.box(hide)
            h.drag(hide, box.exactCenterX() - box.width(), box.exactCenterY(), lift = false)
            return hide.iconRotation
        }
        assertTrue(turnedBy(pack("undo")) != 0f)
        assertEquals(0f, turnedBy(pack("hide-keyboard")))
    }

    // a button drawn again in the same name, colour and size keeps the bitmap it has; a new colour is drawn anew
    @Test
    fun theSameIconIsNotDrawnAnew() {
        val b = ToolButton(ctx, BarIcons.ONE_HANDED, pack("one-handed", styles = """<style select="toolbar:selected" icon-color="#ffff0000"/>"""))
        val first = b.iconDrawable
        assertTrue(first.themed)
        b.setOn(false)
        b.setIcon(BarIcons.ONE_HANDED)
        assertTrue(first === b.iconDrawable)
        b.setOn(true)
        assertFalse(first === b.iconDrawable)
        val on = b.iconDrawable
        b.setOn(true, BarIcons.ONE_HANDED)
        assertTrue(on === b.iconDrawable)
    }

    // a read finished for an icon no longer shown is not drawn over the one shown now
    @Test
    fun anEditingButtonsIconReadLateIsNotDrawnOverAnother() {
        val mine = pack("backspace")
        ScenePainter.forgetIcons()
        val queued = ArrayList<Runnable>()
        ThemeAssets.runner = { queued += it }
        val b = TextEditingButton(ctx, mine, rippled = false, bordered = false, radius = 0f)
        b.setIcon(BarIcons.BACKSPACE)
        assertNull(b.imageView.drawable)
        b.setIcon(BarIcons.CLIPBOARD)
        val app = b.imageView.drawable
        assertNotNull(app)
        queued.forEach { it.run() }
        assertTrue(app === b.imageView.drawable)
    }

    // a theme picked: its bar's icons queued for the decode thread before its picture and the picture's blurs
    @Test
    fun aPickedThemesBarIconsAreReadBeforeItsPicture() {
        val mine = pack("undo", picture = true)
        ScenePainter.forgetIcons()
        val queued = ArrayList<Runnable>()
        ThemeAssets.runner = { queued += it }
        ThemeManager.setNormalModeTheme(mine)
        // the icon's read and the picture's decode (its blurs with it)
        assertEquals(2, queued.size)
        // the first queued is the icon's read: run alone, the bar's undo is the theme's
        queued.first().run()
        assertTrue(ToolButton(ctx, BarIcons.UNDO, mine).iconDrawable.themed)
    }

    private companion object {
        const val SIDE = 24
        const val PICTURE_W = 1080
        const val PICTURE_H = 720
    }
}
