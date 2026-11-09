/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.SCREENSHOT_SDK
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.ThemeMakerTestdata
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.engine.MenuCell
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.getOr
import org.fcitx.fcitx5.android.scene.json.EnvJson
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.ui.main.settings.theme.VectorIconJson
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 060-render-p3 W1, the theme maker's library drawing as the app does: some of the library's shots
 * (lib/keyboard-scene's facade/shots.json, the snapshots' asks, built with the fixed font both sides have)
 * drawn by [ScenePainter] on a canvas that writes down what it is asked to draw, in the device's pixels:
 * web/theme-maker/test/testdata/paint.txt. The page's test (engine.test.mjs) paints the same shots through
 * the library's CanvasPainter on a 2D context that writes the same lines, and holds them to these.
 *
 * What a canvas cannot say of itself is written as the op says it: a blur's sigma (Skia's of the op's
 * radius; BlurMaskFilter keeps it to itself), a band less its face (Path.op's outline is not the shapes'),
 * an icon (a vector drawable's own drawing): so those lines hold where and in what, not how. A nine-slice
 * and the picture are in none of the shots (the page's own, not the engine's: the jsTest's).
 *
 * The icons the scenes name (SceneIcons, by the scene's names) go to the page as `youmo-engine-icons`
 * (engine-icons.json), and each shot's picture to build/w1-pixel for web/theme-maker/test/pixel-smoke.mjs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, sdk = [SCREENSHOT_SDK])
class EnginePaintTest {
    private val context = RuntimeEnvironment.getApplication()
    private val look = ThemePreset.PixelLight.look

    @Test
    fun theLibrarysShotsAreDrawnAsWrittenDown() {
        val shots = readShots()
        val out = StringBuilder()
        val pixels = File("build/w1-pixel").apply { mkdirs() }
        for ((name, pressed) in PAINTED) {
            val shot = shots[name.removePrefix(PRESSED_PREFIX)] ?: error("no shot $name in shots.json")
            val scene = scene(shot)
            val bitmap = Bitmap.createBitmap(scene.width.roundToInt(), scene.height.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = RecordingCanvas(bitmap)
            val painter = ScenePainter(View(context), look, BoardImages.NONE, SceneFonts(look))
            val ops = scene.base + scene.slots.flatMap { it.ops } + scene.keys.flatMap { it.ops(it.id == pressed) }
            ops.forEach { op ->
                canvas.op = op
                painter.draw(canvas, listOf(op))
            }
            out.append("== ").append(name).append(' ').append(shot.name).append(" pressed=").append(pressed ?: "-").append('\n')
            canvas.lines.forEach { out.append(it).append('\n') }
            File(pixels, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        }
        ThemeMakerTestdata.check("paint.txt", out.toString())
    }

    @Test
    fun theIconsTheScenesNameAreThePages() {
        val json = SceneIcons.names.sorted().mapNotNull { name ->
            SceneIcons.of(name)?.let { id -> "\"$name\":" + VectorIconJson.of(context.resources.getResourceEntryName(id)) }
        }.joinToString(",\n", "{\n", "\n}")
        ThemeMakerTestdata.check("engine-icons.json", "$json\n")
        ThemeMakerTestdata.block("youmo-engine-icons", json)
    }

    // ------------------------------------------------------------------ the shots

    private class Shot(val name: String, val board: String?, val theme: JsonObject, val env: String, val request: JsonObject?)

    private fun readShots(): Map<String, Shot> {
        val file = listOf("../lib/keyboard-scene", "lib/keyboard-scene").map { File(it, "src/commonTest/data/facade/shots.json") }.first { it.exists() }
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        fun shot(o: JsonObject, popup: Boolean) = Shot(
            o.getValue("name").jsonPrimitive.content,
            if (popup) null else o.getValue("board").jsonPrimitive.content,
            o.getValue("theme").jsonObject,
            o.getValue("env").toString(),
            if (popup) o.getValue("request").jsonObject else null,
        )
        return (root.getValue("shots").jsonArray.map { shot(it.jsonObject, false) } + root.getValue("popups").jsonArray.map { shot(it.jsonObject, true) })
            .associateBy { it.name }
    }

    // the theme as the shots make it (SnapshotTest's ThemeSource): the file, its fonts after its colours, its styles at its end
    private fun theme(t: JsonObject): org.fcitx.fcitx5.android.scene.theme.Theme {
        val dir = listOf("../lib/keyboard-scene", "lib/keyboard-scene").map { File(it, "src/commonTest/data") }.first { it.exists() }
        val text = File(dir, t.getValue("file").jsonPrimitive.content).readText()
        val styles = (t["styles"] as JsonArray?)?.map { it.jsonPrimitive.content }.orEmpty()
        val fonts = (t["fonts"] as JsonPrimitive?)?.content.orEmpty()
        val made = if (styles.isEmpty() && fonts.isEmpty()) {
            text
        } else {
            text.replace("</colors>", "</colors>$fonts").replace("</theme>", styles.joinToString("\n", postfix = "\n</theme>"))
        }
        return ThemeReader.theme(made).getOr { error("$it") }
    }

    private fun scene(shot: Shot): Scene {
        val env = EnvJson.read(shot.env).getOr { error("$it") }
        // the theme's version for the environment, as the library has it
        val b = SceneBuilder(theme(shot.theme).variant(env.dark), env, FixedMeasurer)
        val r = shot.request ?: return requireNotNull(SceneLayouts.of(context, shot.board!!)).let { layout ->
            b.build(layout.layout, layout.fragments).getOr { error("$it") }
        }
        fun ints(name: String) = r.getValue(name).jsonArray.map { it.jsonPrimitive.int }
        fun strings(name: String) = r.getValue(name).jsonArray.map { it.jsonPrimitive.content }
        return when (r.getValue("kind").jsonPrimitive.content) {
            "preview" -> {
                val size = if (r["size"]?.jsonPrimitive?.content == "words") b.previewWordsSize().getOr { error("$it") }.first else null
                b.preview(r.getValue("text").jsonPrimitive.content, r.getValue("keyWidth").jsonPrimitive.content.toDouble(), size)
            }
            "keys" -> {
                val labels = strings("labels")
                val keys = b.popupKeys(labels, r.getValue("columns").jsonPrimitive.int, r.getValue("room").jsonPrimitive.content.toDouble())
                    .getOr { error("$it") }
                b.popup(labels, r.getValue("rows").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.int } }, keys, r.getValue("selected").jsonPrimitive.int)
            }
            else -> {
                val cells = r.getValue("cells").jsonArray.map { c ->
                    val o = c.jsonObject
                    MenuCell(o.getValue("label").jsonPrimitive.content, (o["icon"] as JsonPrimitive?)?.content)
                }
                b.menu(cells, ints("order"), r.getValue("selected").jsonPrimitive.int, ints("disabled").toSet())
            }
        }.getOr { error("$it") }
    }

    // ------------------------------------------------------------------ the canvas that writes down

    /**
     * Writes a line for each thing drawn, in the device's pixels (one decimal; colours `#aarrggbb`): `fill`, `stroke`,
     * `gradient`, `text`, `icon`, `clip` and `unclip` (a restore that undoes a clip). [op] is the op being drawn: what a
     * canvas cannot tell is taken from it (see the class's comment).
     */
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val lines = ArrayList<String>()

        /** The op drawn next; an icon's line written as it is set, where the app has the icon (ScenePainter draws none else). */
        var op: Op? = null
            set(value) {
                field = value
                if (value is Op.Icon && SceneIcons.of(value.name) != null) lines += "icon ${value.name} ${iconBox(value.box)} ${color(value.tint.value)}"
            }

        // whether each save's level clipped (a restore of one writes `unclip`)
        private val clipped = ArrayList<Boolean>()
        private val bounds = RectF()
        private val m = Matrix()

        override fun save(): Int {
            clipped += false
            return super.save()
        }

        override fun restore() {
            if (clipped.removeLastOrNull() == true && !quiet()) lines += "unclip"
            super.restore()
        }

        override fun clipPath(path: Path): Boolean {
            if (!quiet()) {
                lines += "clip ${box(path)}"
                if (clipped.isNotEmpty()) clipped[clipped.size - 1] = true
            }
            return super.clipPath(path)
        }

        override fun drawPath(path: Path, paint: Paint) {
            super.drawPath(path, paint)
            if (quiet()) return
            val o = op
            lines += when {
                o is Op.Fill && o.less != null -> "fill ${box(o.shape.bounds)} ${color(paint.color)} less=${box(o.less!!.bounds)}"
                paint.shader != null -> "gradient ${box(path)}"
                paint.style == Paint.Style.STROKE -> "stroke ${box(path)} ${color(paint.color)} w=${n(paint.strokeWidth * scale())}"
                else -> "fill ${box(path)} ${color(paint.color)}" + blur(paint)
            }
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            super.drawText(text, x, y, paint)
            if (quiet()) return
            @Suppress("DEPRECATION")
            getMatrix(m)
            val p = floatArrayOf(x, y)
            m.mapPoints(p)
            val v = FloatArray(MATRIX).also(m::getValues)
            val rotation = Math.toDegrees(atan2(v[Matrix.MSKEW_Y].toDouble(), v[Matrix.MSCALE_X].toDouble()))
            val stroke = if (paint.style == Paint.Style.FILL_AND_STROKE) " stroke=${n(paint.strokeWidth * scale())}" else ""
            lines += "text \"$text\" ${n(p[0])},${n(p[1])} w=${paint.typeface?.weight ?: NORMAL} s=${n(paint.textSize * scale())} " +
                "sx=${n2(paint.textScaleX.toDouble())} ls=${n2(paint.letterSpacing.toDouble())} ${color(paint.color)}$stroke${blur(paint)}" +
                (if (rotation.roundToInt() != 0) " rot=${rotation.roundToInt()}" else "")
        }

        // an icon's drawing is its vector drawable's own: none of it written, the op's line instead ([op])
        private fun quiet(): Boolean = op is Op.Icon

        // the op's blur radius as Skia's sigma, where the paint has a mask (a radius of none has none)
        private fun blur(paint: Paint): String {
            if (paint.maskFilter == null) return ""
            val radius = when (val o = op) {
                is Op.Shadow -> o.blur
                is Op.InnerShadow -> o.blur
                is Op.Text -> o.shadow?.blur ?: 0.0
                else -> 0.0
            }
            return " blur=${n((SIGMA_SCALE * radius + SIGMA_BIAS) * scale())}"
        }

        private fun scale(): Float {
            @Suppress("DEPRECATION")
            getMatrix(m)
            return m.mapRadius(1f)
        }

        private fun box(path: Path): String {
            path.computeBounds(bounds, true)
            @Suppress("DEPRECATION")
            getMatrix(m)
            m.mapRect(bounds)
            return "${n(bounds.left)},${n(bounds.top)},${n(bounds.right)},${n(bounds.bottom)}"
        }

        private fun box(b: Box): String {
            bounds.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            @Suppress("DEPRECATION")
            getMatrix(m)
            m.mapRect(bounds)
            return "${n(bounds.left)},${n(bounds.top)},${n(bounds.right)},${n(bounds.bottom)}"
        }

        // ScenePainter's: whole pixels from the box's corners, turned about its middle by the op's rotation
        private fun iconBox(b: Box): String {
            val left = b.left.roundToInt()
            val top = b.top.roundToInt()
            val w = b.right.roundToInt() - left
            val h = b.bottom.roundToInt() - top
            val turn = Matrix().apply {
                @Suppress("DEPRECATION")
                getMatrix(this)
                preTranslate(left.toFloat(), top.toFloat())
                (op as Op.Icon).rotation.takeIf { it != 0.0 }?.let { preRotate(it.toFloat(), w / 2f, h / 2f) }
            }
            bounds.set(0f, 0f, w.toFloat(), h.toFloat())
            turn.mapRect(bounds)
            return "${n(bounds.left)},${n(bounds.top)},${n(bounds.right)},${n(bounds.bottom)}"
        }
    }

    private companion object {
        // the shots drawn, and the key held down in each (none: null); a name PRESSED_PREFIX + a shot's is that shot with it
        val PAINTED: List<Pair<String, String?>> = listOf(
            "qwerty-block" to null,
            "${PRESSED_PREFIX}qwerty-block" to "q",
            "qwerty-dark-flat" to null,
            "nine-block" to null,
            "zhuyin-block" to null,
            "number-block" to null,
            "qwerty-floating-landscape" to null,
            "qwerty-stroke-shadow" to null,
            "qwerty-letter-spacing" to null,
            "qwerty-fonts" to null,
            "qwerty-path" to null,
            "qwerty-inner-shadow" to null,
            "qwerty-depth" to null,
            "qwerty-cap" to null,
            "popup-preview-symbol-block" to null,
            "popup-preview-words-floating" to null,
            "popup-preview-bubble-block" to null,
            "popup-keys-selected-border-block" to null,
            "popup-menu-icons-block" to null,
            "popup-menu-words-flat" to null,
        )

        const val PRESSED_PREFIX = "pressed-"
        const val PNG_QUALITY = 100
        const val MATRIX = 9
        const val NORMAL = 400
        const val SIGMA_SCALE = 0.57735
        const val SIGMA_BIAS = 0.5
        const val TENTHS = 10.0
        const val HUNDREDTHS = 100.0

        /** [v] to a tenth, as the page's test writes it: `-0.5`, `12.0`. */
        fun n(v: Float): String = n(v.toDouble())

        fun n(v: Double): String = fixed((v * TENTHS).roundToLong(), 1)

        fun n2(v: Double): String = fixed((v * HUNDREDTHS).roundToLong(), 2)

        private fun fixed(units: Long, places: Int): String {
            val sign = if (units < 0) "-" else ""
            val a = kotlin.math.abs(units).toString().padStart(places + 1, '0')
            return sign + a.dropLast(places) + "." + a.takeLast(places)
        }

        fun color(c: Int): String = "#%08x".format(c)
    }
}
