/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.SCREENSHOT_SDK
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeMonet
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The old drawing (each key its KeyView, AppPrefs.Internal.canvasKeyboard off) kept as pictures
 * before 060's P2 deletes it: what P3 holds the scene engine's drawing to (dev/design/060-render-p2.md,
 * S0). Every built-in theme upright on the ten keyboards; PixelLight and MaterialDark in every form;
 * ThemeLookSamples' five themes (light and dark) and the theme maker's three files upright only (their
 * colours, pictures, nine-slices and fonts are what P3's conversion must keep; the forms are the
 * engine's, held by the two above); the popups of
 * the 26 keys in every built-in theme. Each picture is the input view drawn, cut to what is not
 * transparent; a line of index.tsv says what it is.
 *
 * Made only with `-PoldRenderBaseline=<dir>` (from the root; `app/src/test/baseline-060` is the one kept), at
 * SDK 35; `-PoldRenderBaselineOnly=<regex>` makes only the files whose name it finds. Without them
 * the kept set is checked: every planned picture there and read, and a few drawn again the same.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK], application = FcitxApplication::class, qualifiers = OldRenderBaselineTest.PORTRAIT)
class OldRenderBaselineTest {

    @get:Rule
    val prefs = PrefsRule()

    @get:Rule
    val dir = TemporaryFolder()

    private val out: File? = System.getProperty("oldRenderBaseline.dir")?.takeIf { it.isNotEmpty() }?.let(::File)
    private val only: Regex? = System.getProperty("oldRenderBaseline.only")?.takeIf { it.isNotEmpty() }?.let(::Regex)

    private val realDecoder = ThemeAssets.decoder
    private val realRunner = ThemeAssets.runner
    private val realPoster = ThemeAssets.poster

    @Before
    fun setUp() {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, false)
        // the 注音 keyboards' 简繁 hint not said; no clipboard entry in the bar
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        prefs.set(AppPrefs.getInstance().clipboard.clipboardSuggestion, false)
        org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastEntry = null
        ThemeAssets.clear()
        // decoded at once on this thread: each picture has its images in
        ThemeAssets.runner = { it.run() }
        ThemeAssets.poster = { it.run() }
        // the font sample names a font file that is none: monospace, as ThemeLookScreenshotTest draws it
        ThemeAssets.decoder = object : ThemeAssets.Decoder by realDecoder {
            override fun typeface(path: String): Typeface? =
                if (File(path).isFile) realDecoder.typeface(path) else Typeface.MONOSPACE
        }
    }

    @After
    fun tearDown() {
        ThemeAssets.decoder = realDecoder
        ThemeAssets.runner = realRunner
        ThemeAssets.poster = realPoster
        ThemeAssets.clear()
        RuntimeEnvironment.setQualifiers(PORTRAIT)
    }

    // ---- what is drawn ----

    private class Board(
        val name: String,
        val im: InputMethodEntry = KeyboardHarness.PINYIN,
        val keyboards: ImKeyboards.Chosen = ImKeyboards.Chosen(),
        val inputType: Int = InputType.TYPE_CLASS_TEXT,
    )

    /** A form: its screen, and the preferences it sets (each giving back what puts it back). */
    private class Form(val name: String, val qualifiers: String = PORTRAIT, val set: () -> List<() -> Unit> = { emptyList() })

    private class Look(val id: String, val make: () -> Theme)

    // [group]: the test that makes it (a few hundred harnesses in one test run out of the heap)
    private class Shot(val group: String, val look: Look, val board: Board, val form: Form, val popup: String? = null) {
        val file get() = "${look.id}/${board.name}-${popup ?: form.name}.png"
    }

    private val looks by lazy { ThemeLookSamples(dir.root) }

    private fun builtinLooks() = listOf(
        ThemePreset.MaterialLight, ThemePreset.MaterialDark, ThemePreset.PixelLight, ThemePreset.PixelDark,
        ThemePreset.NordLight, ThemePreset.NordDark, ThemePreset.DeepBlue, ThemePreset.Monokai, ThemePreset.AMOLEDBlack,
        ThemePreset.TransparentLight, ThemePreset.TransparentDark,
    ).map { t -> Look(t.name) { t } } + listOf(
        // Android's own colours here (Robolectric's): what a phone's wallpaper makes of them differs
        Look("MonetLight") { ThemeMonet.getLight() },
        Look("MonetDark") { ThemeMonet.getDark() },
    )

    private fun sampleLooks() = SAMPLES.flatMap { s ->
        listOf(false, true).map { dark -> Look("${s}_${if (dark) "dark" else "light"}") { looks.theme(looks.samples.single { it.name == s }, dark) } }
    }

    // the theme maker's shared files (web/theme-maker/test/testdata), imported as a user would
    private fun makerLooks() = MAKER_FILES.map { f ->
        Look(f.removeSuffix(".theme")) {
            phoneStorage()
            val file = File(System.getProperty("themeMaker.testdata") ?: "../web/theme-maker/test/testdata", f)
            ThemeFilesManager.importTheme(file.inputStream()).getOrThrow().theme
        }
    }

    private val boards = listOf(
        Board("pinyin"),
        Board("english", im = KeyboardHarness.ENGLISH),
        Board("k14", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14)),
        Board("k18", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS18)),
        Board("nine", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS)),
        Board("zhuyin_full", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_FULL)),
        Board("zhuyin_nine", keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_NINE)),
        Board("number", inputType = InputType.TYPE_CLASS_NUMBER),
        Board("symbols"),
        Board("handwriting", im = HANDWRITING),
    )

    private fun <T : Any> ManagedPreference<T>.during(value: T): () -> Unit {
        val was = getValue()
        setValue(value)
        return { setValue(was) }
    }

    private val theme get() = ThemeManager.prefs

    private val upright = Form("port")

    private val forms = listOf(
        Form("land", LANDSCAPE) { listOf(theme.splitKeyboard.during(SplitKeyboardMode.Off)) },
        Form("compact") {
            val compact = KeyboardHeightLevels.ALL[KeyboardHeightLevels.COMPACT]
            val keyboard = AppPrefs.getInstance().keyboard
            listOf(keyboard.keyboardHeightPercent.during(compact.portrait), keyboard.keyboardHeightPercentLandscape.during(compact.landscape))
        },
        Form("floating") { listOf(theme.floatingKeyboard.during(true)) },
        Form("one_handed_left") { listOf(theme.oneHanded.during(OneHanded.Left)) },
        Form("split_land", LANDSCAPE) { listOf(theme.splitKeyboard.during(SplitKeyboardMode.Always)) },
        Form("flat") { listOf(theme.keyBorderChoice.during(ThemePrefs.KeyBorderChoice.Off)) },
    )

    private fun plan(): List<Shot> {
        val builtins = builtinLooks()
        val pinyin = boards.first()
        return builtins.flatMap { t -> boards.map { b -> Shot("builtins", t, b, upright) } } +
            builtins.filter { it.id in FORMS_THEMES }.flatMap { t -> forms.flatMap { f -> boards.map { b -> Shot("forms", t, b, f) } } } +
            sampleLooks().flatMap { t -> boards.map { b -> Shot(t.id.substringBeforeLast('_'), t, b, upright) } } +
            makerLooks().flatMap { t -> boards.map { b -> Shot("makers", t, b, upright) } } +
            builtins.flatMap { t -> POPUPS.map { p -> Shot("popups", t, pinyin, upright, p) } }
    }

    // ---- made ----

    @Test fun builtins() = make("builtins")

    @Test fun forms() = make("forms")

    @Test fun color() = make("color")

    @Test fun glass() = make("glass")

    @Test fun cutout() = make("cutout")

    @Test fun nineSliceFont() = make("nine_slice_font")

    @Test fun eachKey() = make("each_key")

    @Test fun makers() = make("makers")

    @Test fun popups() = make("popups")

    // [group]'s pictures made anew, its lines of the index with them (the other groups' kept)
    private fun make(group: String) {
        val root = out
        assumeTrue("made with -PoldRenderBaseline=<dir>", root != null)
        root!!
        val shots = plan().filter { it.group == group && only?.containsMatchIn(it.file) ?: true }
        root.mkdirs()
        val commit = commit()
        val index = File(root, INDEX)
        val rows = (if (index.isFile) index.readLines() else emptyList())
            .filter { !it.startsWith("#") && it.isNotBlank() }.associateBy { it.substringBefore('\t') }.toMutableMap()
        for (s in shots) rows[s.file] = shoot(s, root, commit)
        index.writeText(HEADER + rows.toSortedMap().values.joinToString("\n", postfix = "\n"))
    }

    private fun commit(): String = runCatching {
        val p = ProcessBuilder("git", "rev-parse", "--short=9", "HEAD").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    }.getOrDefault("?")

    // the shot drawn into [root]; its index line
    private fun shoot(s: Shot, root: File, commit: String): String {
        val undo = s.form.set()
        if (s.form.qualifiers != PORTRAIT) RuntimeEnvironment.setQualifiers(s.form.qualifiers)
        try {
            val drawn = draw(s)
            val f = File(root, s.file)
            f.parentFile!!.mkdirs()
            writePng(drawn.bitmap, f)
            val dm = RuntimeEnvironment.getApplication().resources.displayMetrics
            val c = drawn.crop
            return listOf(
                s.file, s.look.id, s.board.name, s.form.name, s.popup ?: "-",
                "${drawn.bitmap.width}", "${drawn.bitmap.height}", "${c.left},${c.top}",
                "${dm.widthPixels}x${dm.heightPixels}", "${dm.densityDpi}", s.form.qualifiers, commit, known(s).ifEmpty { "-" },
            ).joinToString("\t")
        } finally {
            if (s.form.qualifiers != PORTRAIT) RuntimeEnvironment.setQualifiers(PORTRAIT)
            undo.asReversed().forEach { it() }
        }
    }

    // what the scene engine draws otherwise on purpose (060-render-p2.md §5.3), where a picture shows it
    private fun known(s: Shot): String = buildList {
        if (s.board.name == "handwriting") add("handwriting-fixed-fullwidth-punct")
        if (s.board.name == "zhuyin_nine") add("zhuyin-nine-shown-not-typed")
        if (s.board.name == "nine") add("nine-syllables-sp")
        if (s.form.name == "floating") add("floating-height-cap")
        if (s.popup != null) add("popup-sp-and-radius")
    }.joinToString(",")

    private class Drawn(val bitmap: Bitmap, val crop: android.graphics.Rect)

    private fun draw(s: Shot): Drawn {
        val theme = s.look.make()
        return KeyboardHarness(theme, im = s.board.im, keyboards = s.board.keyboards).use { h ->
            if (s.board.im === HANDWRITING) {
                h.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, HANDWRITING)
                h.api.enabledIme = h.api.availableIme
            }
            h.start(s.board.inputType)
            if (s.board.name == "symbols") h.tap(h.key("符"))
            // past the keyboard's sliding in; a picture not made ahead (a floating keyboard's blur) asked
            // for at the first draw comes in after it
            h.after(1000)
            h.relayout()
            frame(h)
            h.idle()
            h.relayout()
            when (s.popup) {
                null -> cut(frame(h))
                else -> popup(h, s.popup)
            }
        }
    }

    private fun frame(h: KeyboardHarness): Bitmap {
        val v = h.inputView
        return Bitmap.createBitmap(maxOf(1, v.width), maxOf(1, v.height), Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }

    // the popup [name] up on the 26 keys, drawn while the finger is still down
    private fun popup(h: KeyboardHarness, name: String): Drawn {
        val longPress = AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L
        fun held(v: View, move: (KeyboardHarness.Finger) -> Unit): Drawn {
            val finger = h.finger(v, h.at(v))
            move(finger)
            val d = cut(frame(h))
            finger.up(h.at(v))
            h.idle()
            return d
        }
        return when (name) {
            // the bubble over a key pressed
            "bubble_q" -> held(h.key("q")) { h.after(50) }
            // a long press's choices
            "long_press_e" -> held(h.key("e")) { h.after(longPress) }
            // Return's menu (its long press)
            "return_menu" -> held(h.inputView.findViewById(R.id.button_return)) { h.after(longPress) }
            // the space swiped up: the edit menu
            "edit_menu" -> {
                val space = h.inputView.findViewById<View>(R.id.button_space)
                held(space) { f -> for (i in 1..6) f.move(h.at(space, dy = -10f * i, ms = 16)) }
            }
            else -> error(name)
        }
    }

    // [b] cut to what is not transparent (the room above a keyboard, beside a floating one)
    private fun cut(b: Bitmap): Drawn {
        val w = b.width
        val px = IntArray(w * b.height).also { b.getPixels(it, 0, w, 0, 0, w, b.height) }
        var l = w
        var t = b.height
        var r = -1
        var bottom = -1
        for (y in 0 until b.height) for (x in 0 until w) {
            if (px[y * w + x] ushr 24 == 0) continue
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > bottom) bottom = y
        }
        check(r >= 0) { "nothing drawn" }
        val rect = android.graphics.Rect(l, t, r + 1, bottom + 1)
        return Drawn(Bitmap.createBitmap(b, rect.left, rect.top, rect.width(), rect.height()), rect)
    }

    // lossless, as small as the JDK's encoder makes it: deflate at 9, its filter chosen a row at a time;
    // no alpha channel where every pixel is opaque
    private fun writePng(b: Bitmap, f: File) {
        val px = pixels(b)
        val opaque = px.all { it ushr 24 == 0xff }
        val img = BufferedImage(b.width, b.height, if (opaque) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, b.width, b.height, px, 0, b.width)
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0f
        }
        f.delete()
        ImageIO.createImageOutputStream(f).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    // ---- kept ----

    private val kept = File("src/test/baseline-060")

    private fun index(): Map<String, List<String>> = File(kept, INDEX).readLines()
        .filter { !it.startsWith("#") && it.isNotBlank() }
        .associate { line -> line.split('\t').let { it[0] to it } }

    @Test
    fun theBaselineIsThereWholeAndReads() {
        assumeTrue("being made", out == null)
        assertTrue("$kept is missing: made with -PoldRenderBaseline=src/test/baseline-060", File(kept, INDEX).isFile)
        val index = index()
        val planned = plan().map { it.file }
        assertEquals("planned and indexed", planned.sorted(), index.keys.sorted())
        val files = kept.walkTopDown().filter { it.isFile && it.extension == "png" }.map { it.relativeTo(kept).path }.toSet()
        assertEquals("indexed and kept", index.keys, files)
        for ((name, row) in index) {
            val img = ImageIO.read(File(kept, name)) ?: error("$name does not read")
            assertEquals(name, row[5].toInt() to row[6].toInt(), img.width to img.height)
        }
    }

    // the old drawing still drawn as kept: a change to it before P2 deletes it is seen here (a few of
    // the pictures, each kind once; all of them drawn again take minutes)
    @Test
    fun aFewDrawnAgainAsKept() {
        assumeTrue("being made", out == null)
        val index = index()
        val again = plan().filter { it.file in AGAIN }
        assertEquals(AGAIN.toSet(), again.map { it.file }.toSet())
        for (s in again) {
            val undo = s.form.set()
            if (s.form.qualifiers != PORTRAIT) RuntimeEnvironment.setQualifiers(s.form.qualifiers)
            val drawn = try {
                draw(s)
            } finally {
                if (s.form.qualifiers != PORTRAIT) RuntimeEnvironment.setQualifiers(PORTRAIT)
                undo.asReversed().forEach { it() }
            }
            val row = index.getValue(s.file)
            assertEquals(s.file, listOf(row[5], row[6], row[7]), listOf("${drawn.bitmap.width}", "${drawn.bitmap.height}", "${drawn.crop.left},${drawn.crop.top}"))
            val stored = ImageIO.read(File(kept, s.file))
            val a = pixels(drawn.bitmap)
            val b = IntArray(stored.width * stored.height).also { stored.getRGB(0, 0, stored.width, stored.height, it, 0, stored.width) }
            val differing = a.indices.count { i -> a[i] != b[i] }
            assertEquals("${s.file}: pixels not as kept", 0, differing)
        }
    }

    companion object {
        const val PORTRAIT = "w393dp-h851dp-port-440dpi"
        private const val LANDSCAPE = "w851dp-h393dp-land-440dpi"
        private const val INDEX = "index.tsv"

        private val HANDWRITING = InputMethodEntry(
            org.fcitx.fcitx5.android.engine.InputMethodCatalog.HANDWRITING, "手写", "", "", "写", "zh_CN", "androidengine", false,
        )

        private val FORMS_THEMES = setOf("PixelLight", "MaterialDark")
        private val SAMPLES = listOf("color", "glass", "cutout", "nine_slice_font", "each_key")
        private val MAKER_FILES = listOf("from-app.theme", "from-app-2.1.theme", "from-web.theme")
        private val POPUPS = listOf("bubble_q", "long_press_e", "return_menu", "edit_menu")

        private val AGAIN = listOf(
            "PixelLight/pinyin-port.png",
            "MaterialDark/nine-split_land.png",
            "glass_light/k18-port.png",
            "from-web/zhuyin_full-port.png",
            "PixelDark/pinyin-long_press_e.png",
        )

        private val HEADER = """
            |# 060 P2 S0: the old drawing (KeyView), kept for P3 (OldRenderBaselineTest, dev/design/060-render-p2.md S0).
            |# file, theme, keyboard, form, popup, width, height, cut at (x,y in the input view), screen px, dpi, qualifiers, commit, known.
            |# known: what the scene engine draws otherwise on purpose (060-render-p2.md 5.3), not a regression in P3:
            |#   handwriting-fixed-fullwidth-punct ，。 shown full-width whatever is typed; zhuyin-nine-shown-not-typed ?!~ shown
            |#   half-width, typed mapped; nine-syllables-sp the syllable column in sp; floating-height-cap the 70% cap;
            |#   popup-sp-and-radius popups in sp, their corners not the keys'. Everywhere: text on a row of odd height a pixel
            |#   off centre, the backspace mark's icon (U10, fixed in P2).
            |
        """.trimMargin() + listOf("#file", "theme", "keyboard", "form", "popup", "width", "height", "cut", "screen", "dpi", "qualifiers", "commit", "known")
            .joinToString("\t", postfix = "\n")
    }
}
