/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.SCREENSHOT_SDK
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.bar.ToolbarButton
import org.fcitx.fcitx5.android.input.bar.ToolbarConfig
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * The scene engine's drawing held to the old drawing's record, `src/test/baseline-060` (P2's S0, the
 * key views; dev/design/060-render-p3.md T0). Every line of its index.tsv drawn again as it was made
 * (theme, keyboard, form, popup, screen) and compared where it lies in the input view: a pixel is
 * different when a channel differs by more than [CHANNEL]; a picture's share of different pixels may
 * not grow past its line in `limits.tsv` + [SLACK] percentage points (a ratchet: what differs today is
 * the limit, a stage that makes a picture worse fails here). Over [THRESHOLD] (zhuyin_full: [THRESHOLD_ZHUYIN_FULL])
 * a line says why: P2's 5.3 entry (the `known` column) or what else it is.
 *
 * `-PthemeBaseline.tighten=true` writes each picture's share as its limit where it is lower (or none
 * yet). Each picture with a difference is written with the different pixels in red to
 * `build/theme-baseline-diff/`, for the eye.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK], application = FcitxApplication::class, qualifiers = ThemeBaselineCompareTest.PORTRAIT)
class ThemeBaselineCompareTest {

    @get:Rule
    val prefs = PrefsRule()

    @get:Rule
    val dir = TemporaryFolder()

    private val tighten = System.getProperty("themeBaseline.tighten") == "true"

    private val realDecoder = ThemeAssets.decoder
    private val realRunner = ThemeAssets.runner
    private val realPoster = ThemeAssets.poster

    @Before
    fun setUp() {
        // the 注音 keyboards' 简繁 hint not said; no clipboard entry in the bar
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        prefs.set(AppPrefs.getInstance().clipboard.clipboardSuggestion, false)
        // drawn as the text build was when the record was made: no microphone on the bar, none on the space
        prefs.set(AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior, SpaceLongPressBehavior.None)
        val noVoice = ToolbarConfig.DEFAULT.items.map {
            if (it is ToolbarConfig.Item.Button && it.button == ToolbarButton.Voice) ToolbarConfig.Item.Button(it.button, shown = false) else it
        }
        prefs.set(AppPrefs.getInstance().internal.toolbarButtons, ToolbarConfig(noVoice).serialize())
        org.fcitx.fcitx5.android.data.clipboard.ClipboardManager.lastEntry = null
        ThemeAssets.clear()
        // decoded at once on this thread: each picture has its images in
        ThemeAssets.runner = { it.run() }
        ThemeAssets.poster = { it.run() }
        // the font sample names a font file that is none: monospace, as the record drew it
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

    // ---- what is drawn (as P2's S0 made the record) ----

    private class Board(
        val name: String,
        val im: InputMethodEntry = KeyboardHarness.PINYIN,
        val keyboards: ImKeyboards.Chosen = ImKeyboards.Chosen(),
        val inputType: Int = InputType.TYPE_CLASS_TEXT,
    )

    /** A form: its screen, and the preferences it sets (each giving back what puts it back). */
    private class Form(val name: String, val qualifiers: String = PORTRAIT, val set: () -> List<() -> Unit> = { emptyList() })

    private class Look(val id: String, val make: () -> Theme)

    // [group]: the test that draws it (a few hundred harnesses in one test run out of the heap)
    private class Shot(val group: String, val look: Look, val board: Board, val form: Form, val popup: String? = null) {
        val file get() = "${look.id}/${board.name}-${popup ?: form.name}.png"
    }

    private val looks by lazy { ThemeLookSamples(dir.root) }

    // the built-in themes by their 0.5 names, the record's (Transparent's two test data)
    private fun builtinLooks() = listOf(
        "MaterialLight" to TestThemes.MaterialLight, "MaterialDark" to TestThemes.MaterialDark,
        "PixelLight" to TestThemes.PixelLight, "PixelDark" to TestThemes.PixelDark,
        "NordLight" to TestThemes.NordLight, "NordDark" to TestThemes.NordDark, "DeepBlue" to TestThemes.DeepBlue,
        "Monokai" to TestThemes.Monokai, "AMOLEDBlack" to TestThemes.AMOLEDBlack,
        "TransparentLight" to TestThemes.TransparentLight, "TransparentDark" to TestThemes.TransparentDark,
    ).map { (id, t) -> Look(id) { t } } + listOf(
        // Android's own colours here (Robolectric's)
        Look("MonetLight") { ThemeManager.builtin("monet#light") },
        Look("MonetDark") { ThemeManager.builtin("monet#dark") },
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

    // ---- drawn ----

    private class Drawn(val bitmap: Bitmap, val at: Rect)

    private fun draw(s: Shot): Drawn {
        val undo = s.form.set()
        if (s.form.qualifiers != PORTRAIT) RuntimeEnvironment.setQualifiers(s.form.qualifiers)
        try {
            return KeyboardHarness(s.look.make(), im = s.board.im, keyboards = s.board.keyboards).use { h ->
                if (s.board.im === HANDWRITING) {
                    h.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, HANDWRITING)
                    h.api.enabledIme = h.api.availableIme
                }
                h.start(s.board.inputType)
                if (s.board.name == "nine") chinesePunctuation(h)
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
        } finally {
            if (s.form.qualifiers != PORTRAIT) RuntimeEnvironment.setQualifiers(PORTRAIT)
            undo.asReversed().forEach { it() }
        }
    }

    // the record's 1 shows ，。？！ (the View's own): the scene's as the punctuation maps it (KB-76), Chinese's here
    private fun chinesePunctuation(h: KeyboardHarness) = h.chinesePunctuation()

    private fun frame(h: KeyboardHarness): Bitmap {
        val v = h.inputView
        return Bitmap.createBitmap(maxOf(1, v.width), maxOf(1, v.height), Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }

    // the popup [name] up on the 26 keys, drawn while the finger is still down
    private fun popup(h: KeyboardHarness, name: String): Drawn {
        val longPress = AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L
        fun held(k: ShownKey, move: (KeyboardHarness.Finger) -> Unit): Drawn {
            val finger = h.finger(k, h.at(k))
            move(finger)
            val d = cut(frame(h))
            finger.up(h.at(k))
            h.idle()
            return d
        }
        return when (name) {
            // the bubble over a key pressed
            "bubble_q" -> held(h.key("q")) { h.after(50) }
            // a long press's choices
            "long_press_e" -> held(h.key("e")) { h.after(longPress) }
            // Return's menu (its long press)
            "return_menu" -> held(h.keyNamed("enter")) { h.after(longPress) }
            // the space swiped up: the edit menu
            "edit_menu" -> {
                val space = h.keyNamed("space")
                held(space) { f -> for (i in 1..6) f.move(h.at(space, dy = -10f * i, ms = 16)) }
            }
            else -> error(name)
        }
    }

    // [b] cut to what is not transparent (the room above a keyboard, beside a floating one), as the record was
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
        val rect = Rect(l, t, r + 1, bottom + 1)
        return Drawn(Bitmap.createBitmap(b, rect.left, rect.top, rect.width(), rect.height()), rect)
    }

    // ---- compared ----

    private val kept = File("src/test/baseline-060")
    private val limitsFile = File(kept, LIMITS)
    private val diffs = File("build/theme-baseline-diff")

    private fun index(): Map<String, List<String>> = File(kept, INDEX).readLines()
        .filter { !it.startsWith("#") && it.isNotBlank() }
        .associate { line -> line.split('\t').let { it[0] to it } }

    private class Limit(val percent: Double, val note: String)

    private fun limits(): Map<String, Limit> = if (!limitsFile.isFile) emptyMap() else limitsFile.readLines()
        .filter { !it.startsWith("#") && it.isNotBlank() }
        .associate { line -> line.split('\t').let { it[0] to Limit(it[1].toDouble(), it.getOrElse(2) { "" }) } }

    /** The share (percent) of the union of both pictures' places that differs; the picture of it into [diffs]. */
    private fun measure(s: Shot, row: List<String>): Double {
        val drawn = draw(s)
        val stored = ImageIO.read(File(kept, s.file)) ?: error("${s.file} does not read")
        val (sx, sy) = row[7].split(',').map { it.toInt() }
        val a = drawn.at
        val b = Rect(sx, sy, sx + stored.width, sy + stored.height)
        val u = Rect(a).apply { union(b) }
        val apx = IntArray(drawn.bitmap.width * drawn.bitmap.height).also { drawn.bitmap.getPixels(it, 0, drawn.bitmap.width, 0, 0, drawn.bitmap.width, drawn.bitmap.height) }
        val bpx = IntArray(stored.width * stored.height).also { stored.getRGB(0, 0, stored.width, stored.height, it, 0, stored.width) }
        val out = BufferedImage(u.width(), u.height(), BufferedImage.TYPE_INT_RGB)
        var differing = 0
        for (y in u.top until u.bottom) for (x in u.left until u.right) {
            val p = if (a.contains(x, y)) apx[(y - a.top) * drawn.bitmap.width + (x - a.left)] else 0
            val q = if (b.contains(x, y)) bpx[(y - b.top) * stored.width + (x - b.left)] else 0
            val differs = differs(p, q)
            if (differs) differing++
            // the record in grey, what differs in red
            val g = ((q shr 16 and 0xff) + (q shr 8 and 0xff) + (q and 0xff)) / 6 + 64
            out.setRGB(x - u.left, y - u.top, if (differs) 0xff0000 else (g shl 16) or (g shl 8) or g)
        }
        val percent = 100.0 * differing / (u.width() * u.height())
        val f = File(diffs, s.file)
        if (differing > 0) {
            f.parentFile!!.mkdirs()
            ImageIO.write(out, "png", f)
            // and what is drawn now beside it
            File(f.path.removeSuffix(".png") + ".now.png").outputStream().use { drawn.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            f.delete()
            File(f.path.removeSuffix(".png") + ".now.png").delete()
        }
        return percent
    }

    private fun differs(p: Int, q: Int): Boolean {
        for (shift in intArrayOf(24, 16, 8, 0)) if (abs((p ushr shift and 0xff) - (q ushr shift and 0xff)) > CHANNEL) return true
        return false
    }

    private fun threshold(s: Shot) = if (s.board.name == "zhuyin_full") THRESHOLD_ZHUYIN_FULL else THRESHOLD

    // what P2 drew otherwise on purpose (060-render-p2.md §5.3), the record's `known` column
    private fun note(s: Shot, row: List<String>, percent: Double): String = when {
        row[12] != "-" -> "5.3: ${row[12]}"
        percent > threshold(s) -> "over ${threshold(s)}%: see the diff"
        else -> ""
    }

    @Test fun builtins() = compare("builtins")

    @Test fun forms() = compare("forms")

    @Test fun color() = compare("color")

    @Test fun glass() = compare("glass")

    @Test fun cutout() = compare("cutout")

    @Test fun nineSliceFont() = compare("nine_slice_font")

    @Test fun eachKey() = compare("each_key")

    @Test fun makers() = compare("makers")

    @Test fun popups() = compare("popups")

    private fun compare(group: String) {
        val index = index()
        // while tightening another group's JVM may be writing the file: read under the same lock
        val limits = if (tighten) locked { limits() } else limits()
        val shots = plan().filter { it.group == group }
        val measured = shots.associate { s -> s.file to measure(s, index.getValue(s.file)) }
        // every picture of the group compared
        assertEquals(group, shots.map { it.file }.toSet(), measured.keys)
        if (tighten) write(shots.associate { s -> s.file to Limit(measured.getValue(s.file), limits[s.file]?.note ?: note(s, index.getValue(s.file), measured.getValue(s.file))) })
        val worse = shots.mapNotNull { s ->
            val p = measured.getValue(s.file)
            val limit = limits[s.file] ?: return@mapNotNull "${s.file}: ${fmt(p)}%, no limit (-PthemeBaseline.tighten=true)"
            if (p > limit.percent + SLACK) "${s.file}: ${fmt(p)}% differs, limit ${fmt(limit.percent)}% (diff in $diffs)" else null
        }
        // tightening never fails on them (nothing is raised), but says them
        if (tighten && worse.isNotEmpty()) println("$group, worse than the record allows (left as they were):\n" + worse.joinToString("\n"))
        assertTrue("worse than the record allows:\n" + worse.joinToString("\n"), tighten || worse.isEmpty())
    }

    // [new] merged into limits.tsv, each limit lowered (never raised: that is an edit, with its reason); the
    // groups run in more than one JVM at once: the file locked across them
    private fun write(new: Map<String, Limit>) = locked {
        val rows = limits().toMutableMap()
        for ((f, l) in new) {
            val was = rows[f]
            rows[f] = if (was == null || l.percent < was.percent) Limit(ceil3(l.percent), was?.note ?: l.note) else was
        }
        limitsFile.writeText(
            LIMITS_HEADER + rows.toSortedMap().entries.joinToString("\n", postfix = "\n") { (f, l) -> "$f\t${fmt(l.percent)}\t${l.note}" },
        )
    }

    private fun <T> locked(block: () -> T): T {
        File("build").mkdirs()
        return FileChannel.open(File("build/theme-baseline.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            ch.lock().use { block() }
        }
    }

    private fun ceil3(p: Double) = kotlin.math.ceil(p * 1000) / 1000

    private fun fmt(p: Double) = String.format(Locale.ROOT, "%.3f", p)

    // contract: the committed record's files (index.tsv, limits.tsv, the pictures), which mutation cannot see.
    // the record whole: every picture there, read, indexed once, planned here; every one with a limit
    @Test
    fun theRecordIsWholeAndEveryPictureHasALimit() {
        val index = index()
        val planned = plan().map { it.file }
        assertEquals(RECORD_SIZE, index.size)
        assertEquals("planned and indexed", planned.sorted(), index.keys.sorted())
        val files = kept.walkTopDown().filter { it.isFile && it.extension == "png" }.map { it.relativeTo(kept).path }.toSet()
        assertEquals("indexed and kept", index.keys, files)
        if (tighten) return
        val limits = limits()
        assertEquals("a limit for every picture", index.keys, limits.keys)
        for (s in plan()) {
            val l = limits.getValue(s.file)
            assertTrue("${s.file}: over ${threshold(s)}% with no reason", l.percent <= threshold(s) || l.note.isNotBlank())
        }
    }

    companion object {
        const val PORTRAIT = "w393dp-h851dp-port-440dpi"
        private const val LANDSCAPE = "w851dp-h393dp-land-440dpi"
        private const val INDEX = "index.tsv"
        private const val LIMITS = "limits.tsv"
        private const val RECORD_SIZE = 432

        /** A channel's difference that makes a pixel different (P2's). */
        private const val CHANNEL = 24

        /** Percentage points a picture may differ more than its limit. */
        private const val SLACK = 0.2

        /** Percent different P2 accepted; over it a limit says why. */
        private const val THRESHOLD = 1.5
        private const val THRESHOLD_ZHUYIN_FULL = 2.0

        private val HANDWRITING = InputMethodEntry(InputMethodCatalog.HANDWRITING, "手写", "", "", "写", "zh_CN", "androidengine", false)

        private val FORMS_THEMES = setOf("PixelLight", "MaterialDark")
        private val SAMPLES = listOf("color", "glass", "cutout", "nine_slice_font", "each_key")
        private val MAKER_FILES = listOf("from-app.theme", "from-app-2.1.theme", "from-web.theme")
        private val POPUPS = listOf("bubble_q", "long_press_e", "return_menu", "edit_menu")

        private val LIMITS_HEADER = """
            |# 060 P3 T0: how much of each picture of index.tsv the scene engine draws otherwise (ThemeBaselineCompareTest).
            |# file, percent of pixels different (a channel off by more than 24), why. A run may not exceed a line by more
            |# than 0.2 points; -PthemeBaseline.tighten=true lowers a line to what is drawn now. Over 1.5% (zhuyin_full 2%)
            |# a line says why: "5.3: <known>" is what 060-render-p2.md 5.3 accepted.
            |#file	percent	why
            |
        """.trimMargin()
    }
}
