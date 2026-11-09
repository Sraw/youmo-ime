/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.graphics.Paint
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.SCREENSHOT_SDK
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeMakerTestdata
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.input.preedit.CodeRowStyle
import org.fcitx.fcitx5.android.input.preedit.FloatingCodeStrip
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.layout.Face
import org.fcitx.fcitx5.android.scene.look.KeyKind
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.Palette
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * contract: data files the theme maker page reads (youmo-layout.json, youmo-icons.json and its blocks).
 * What the theme maker's preview draws, measured on the real keyboard (`youmo-layout`), and the
 * icons it draws with (`youmo-icons`): each phone, orientation, board, mode and state the page
 * offers, every key's cell (read off the canvas's scene, 060-render-p2 S7) (its whole cell, the gap not taken off: the page takes the theme's off),
 * kind and theme name, every text, icon and fill, and which colour of the palette each is drawn in
 * (read off a keyboard made with a probe theme whose colours are all different). Written into
 * `web/theme-maker/test/testdata/youmo-layout.json` and `youmo-icons.json`, and into the page's two
 * blocks of the same id once the page is there; compared, or with -PupdateThemeMaker written.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// the page's testdata is measured at the goldens' SDK, as a screenshot is
@Config(sdk = [SCREENSHOT_SDK], application = FcitxApplication::class, qualifiers = DEFAULT_PHONE)
class ThemeMakerLayoutTest {

    @get:Rule
    val prefs = PrefsRule()

    /**
     * A phone the page offers: its size and density, the short name its select lists it by, and the
     * phones of about its width the page names under the select (kept out of the select: long, they
     * widened the preview on a phone).
     */
    private data class Phone(
        val id: String, val qualifiers: String, val widthPx: Int, val heightPx: Int, val dpi: Int,
        val label: String, val examples: String,
    )

    // by width, as the user picked them (2026-11-04), the examples as given (factory settings, not checked
    // on each phone); the first the
    // default (DEFAULT_PHONE: the page's dp sizes are its). dp is px over density rounded, as Robolectric
    // makes px back from it: 1200 / 2.75 = 436.4, 2670 / 2.75 = 970.9
    private val phones = listOf(
        Phone("1080x2340-440", DEFAULT_PHONE, 1080, 2340, 440, "标准 · 393dp", "1080 宽、440 dpi 的屏"),
        Phone("1080x2340-480", "w360dp-h780dp-port-480dpi", 1080, 2340, 480, "窄屏 · 360dp", "如三星 Galaxy S24"),
        Phone("1080x2400-420", "w411dp-h914dp-port-420dpi", 1080, 2400, 420, "较宽 · 411dp", "如 Pixel 9、小米 14 Pro"),
        Phone("1200x2670-440", "w436dp-h971dp-port-440dpi", 1200, 2670, 440, "宽屏 · 436dp", "如小米 14、Redmi K70"),
    )

    // the page's boards: the prefix of their keys' names, and how the keyboard is put up
    private enum class Board(val id: String, val keyboard: ImKeyboard, val from: String? = null) {
        QWERTY(KeyNames.QWERTY, ImKeyboard.FULL),
        NINE(KeyNames.NINE, ImKeyboard.NINE_KEYS),
        K14(KeyNames.K14, ImKeyboard.KEYS14),
        K18(KeyNames.K18, ImKeyboard.KEYS18),
        ZHUYIN_NINE(KeyNames.ZHUYIN_NINE, ImKeyboard.ZHUYIN_NINE),
        ZHUYIN(KeyNames.ZHUYIN, ImKeyboard.ZHUYIN_FULL),
        NUMBER(KeyNames.NUMBER, ImKeyboard.FULL, from = "numbers"),
        SYMBOLS(KeyNames.SYMBOLS, ImKeyboard.FULL, from = "symbols"),
    }

    private enum class Mode(val id: String) { NORMAL("normal"), ONE_HANDED("one-handed"), FLOATING("floating") }

    private enum class State(val id: String) { IDLE("idle"), TYPING("typing"), LONG_PRESS("longpress"), CLIPBOARD("clipboard") }

    private data class Shot(val phone: Phone, val landscape: Boolean, val board: Board, val mode: Mode, val state: State = State.IDLE)

    // every phone every board and mode both ways round (landscape normal: the letters split, as the
    // app has them; landscape one-handed is not offered), the 26 keys' four states: what the page shows
    // on one phone it shows on each (the user, 2026-11-04)
    private fun shots(): List<Shot> {
        val out = mutableListOf<Shot>()
        for (phone in phones) {
            for (board in Board.entries) for (mode in Mode.entries) out += Shot(phone, false, board, mode)
            for (state in State.entries.drop(1)) out += Shot(phone, false, Board.QWERTY, Mode.NORMAL, state)
            for (board in Board.entries) for (mode in listOf(Mode.NORMAL, Mode.FLOATING)) out += Shot(phone, true, board, mode)
        }
        return out
    }

    @Test
    fun theLayoutsAndIconsThePageDraws() {
        // the bar as every build has it, its microphone too
        val icons = sortedSetOf<String>()
        val views = shots().map { measure(it, icons) }
        val layout = buildString {
            append("{\"format\":").append(FORMAT).append(",\n")
            append("\"names\":").append(names()).append(",\n")
            append("\"palette\":").append(JsonArray(PALETTE.map(::JsonPrimitive))).append(",\n")
            append("\"phones\":").append(JsonArray(phones.map(::phone))).append(",\n")
            append("\"views\":[\n")
            views.forEachIndexed { i, v -> append(v); append(if (i < views.lastIndex) ",\n" else "\n") }
            append("]}\n")
        }
        val iconJson = buildString {
            append("{\n")
            icons.forEachIndexed { i, name ->
                append(JsonPrimitive(name)).append(":").append(icon(name))
                append(if (i < icons.size - 1) ",\n" else "\n")
            }
            append("}\n")
        }
        ThemeMakerTestdata.check("youmo-layout.json", layout)
        ThemeMakerTestdata.check("youmo-icons.json", iconJson)
        ThemeMakerTestdata.block("youmo-layout", layout.trimEnd())
        ThemeMakerTestdata.block("youmo-icons", iconJson.trimEnd())
    }

    private fun names() = buildJsonObject {
        put("all", KeyNames.ALL)
        put("roles", JsonArray(KeyNames.ROLES.map(::JsonPrimitive)))
        put("positions", buildJsonObject { KeyNames.POSITIONS.forEach { (b, keys) -> put(b, JsonArray(keys.map(::JsonPrimitive))) } })
    }

    private fun phone(p: Phone) = buildJsonObject {
        put("id", p.id)
        put("px", buildJsonArray { add(JsonPrimitive(p.widthPx)); add(JsonPrimitive(p.heightPx)) })
        put("dpi", p.dpi)
        put("label", p.label)
        put("examples", p.examples)
    }

    // ------------------------------------------------------------------ measuring

    private fun measure(shot: Shot, icons: MutableSet<String>): String {
        val q = shot.phone.qualifiers.let { if (shot.landscape) it.replace("-port-", "-land-").swapSides() else it }
        RuntimeEnvironment.setQualifiers(q)
        prefs.set(ThemeManager.prefs.oneHanded, if (shot.mode == Mode.ONE_HANDED) OneHanded.Right else OneHanded.Off)
        prefs.set(ThemeManager.prefs.floatingKeyboard, shot.mode == Mode.FLOATING)
        return KeyboardHarness(PROBE, keyboards = ImKeyboards.Chosen(pinyin = shot.board.keyboard), codeRow = CodeRowStyle.Floating).use { h ->
            h.start()
            shot.board.from?.let { role -> h.tap(h.keys().first { it.id == role }) }
            when (shot.state) {
                State.IDLE -> Unit
                State.TYPING -> typing(h)
                State.LONG_PRESS -> h.press(h.key("a")).also { h.after(LONG_PRESS_MS); h.relayout() }
                State.CLIPBOARD -> {
                    // a clip apart in time from the one before: on the same tick the list's order is SQLite's whim
                    CLIPS.forEach { h.copied(it); h.after(CLIP_GAP_MS) }
                    h.clipboard(CLIPS.last())
                }
            }
            h.after(SETTLE_MS)
            h.relayout()
            val out = view(h, shot, icons)
            if (shot.state == State.LONG_PRESS) h.release()
            out
        }
    }

    private fun typing(h: KeyboardHarness) {
        val code = "ni hao"
        h.emit(HostEvent.InputPanel(FormattedText(arrayOf(code), intArrayOf(0), code.length), FormattedText.Empty, FormattedText.Empty))
        h.api.candidates = Array(TYPED.size) { CandidateWord("", TYPED[it], "") }
        h.emit(HostEvent.Candidates(TYPED.size, h.api.candidates.toList()))
    }

    private class Space(val origin: IntArray, val density: Float) {
        fun dp(px: Number) = (px.toFloat() / density * 100).roundToInt() / 100.0
        // the box the view covers as drawn: a turned one (the bar's menu arrow is the expand arrow turned a
        // quarter) by its corners as its matrix puts them, not by where its own top left corner went
        fun rect(v: View): JsonArray {
            val at = IntArray(2).also { v.getLocationInWindow(it) }
            val w = v.width.toFloat()
            val h = v.height.toFloat()
            val c = floatArrayOf(0f, 0f, w, 0f, 0f, h, w, h).also { v.matrix.mapPoints(it) }
            val xs = c.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = c.filterIndexed { i, _ -> i % 2 == 1 }
            return buildJsonArray {
                add(JsonPrimitive(dp(at[0] - origin[0] + xs.min() - c[0])))
                add(JsonPrimitive(dp(at[1] - origin[1] + ys.min() - c[1])))
                add(JsonPrimitive(dp(xs.max() - xs.min())))
                add(JsonPrimitive(dp(ys.max() - ys.min())))
            }
        }
        // a box in px given in the window
        fun box(left: Double, top: Double, w: Double, h: Double) = buildJsonArray {
            add(JsonPrimitive(dp(left - origin[0]))); add(JsonPrimitive(dp(top - origin[1])))
            add(JsonPrimitive(dp(w))); add(JsonPrimitive(dp(h)))
        }
        fun top(v: View) = IntArray(2).also { v.getLocationInWindow(it) }[1] - origin[1]
        fun left(v: View) = IntArray(2).also { v.getLocationInWindow(it) }[0] - origin[0]
    }

    private fun view(h: KeyboardHarness, shot: Shot, icons: MutableSet<String>): String {
        val board = h.keyboard
        val density = h.service.resources.displayMetrics.density
        val origin = IntArray(2).also { board.getLocationInWindow(it) }
        val s = Space(origin, density)
        val dm = h.service.resources.displayMetrics
        val items = mutableListOf<JsonElement>()
        val ctx = Walk(h, s, icons, items, shot.board.id)
        ctx.walk(h.inputView, part = "app")
        return buildJsonObject {
            put("phone", shot.phone.id)
            put("land", shot.landscape)
            put("board", shot.board.id)
            put("mode", shot.mode.id)
            put("state", shot.state.id)
            put("screen", buildJsonArray { add(JsonPrimitive(s.dp(dm.widthPixels))); add(JsonPrimitive(s.dp(dm.heightPixels))) })
            put("keyboard", buildJsonArray {
                add(JsonPrimitive(s.dp(origin[0]))); add(JsonPrimitive(s.dp(origin[1])))
                add(JsonPrimitive(s.dp(board.width))); add(JsonPrimitive(s.dp(board.height)))
            })
            put("items", JsonArray(items))
        }.toString()
    }

    private inner class Walk(
        val h: KeyboardHarness,
        val s: Space,
        val icons: MutableSet<String>,
        val items: MutableList<JsonElement>,
        val board: String,
    ) {
        private val input: InputView = h.inputView
        private val picture: View = (h.keyboard as ViewGroup).getChildAt(0)
        private val bar: View = input.kawaiiBar.view
        // the nine keys' column of punctuation: a function key's face the side's cells (its spacers) hold
        private val column: View? = KeyboardHarness.find<T9Keyboard>(h.inputView)?.let { t9 ->
            T9Keyboard::class.java.getDeclaredField("column").apply { isAccessible = true }.get(t9) as View
        }
        private val popup: View = input.popup.root

        private fun drawn(v: View) = h.shown(v) && v.width > 0 && v.height > 0 && v.alpha > 0f

        fun walk(v: View, part: String) {
            if (!drawn(v)) return
            // scrolled out of its list (the nine keys' column, a panel's tabs): not drawn; cut by it, not drawn whole
            val seen = Rect()
            if (!v.getGlobalVisibleRect(seen)) return
            val whole = seen.width() >= v.width * v.scaleX - 1 && seen.height() >= v.height * v.scaleY - 1
            val p = partOf(v, part)
            when {
                v === picture -> items += buildJsonObject { put("t", "base"); put("r", s.rect(v)) }
                v is KeyboardCanvasView -> v.scene?.keys?.forEach { items += sceneKey(v, it) }
                v is SceneKeyCell -> if (whole) v.keyNode?.let { items += sceneKey(v, it) }
                v is PopupSceneView -> popupScene(v)
                v === column -> {
                    items += buildJsonObject {
                        put("t", "key"); put("r", s.rect(v)); put("k", KeyKind.Function.id); put("n", "$board/side")
                        put("b", "default"); put("m", true)
                    }
                    children(v, "side")
                }
                else -> {
                    if (whole) own(v, p)
                    children(v, p)
                }
            }
        }

        // a long press's popup the canvas draws: its fills and texts, as the views' were
        private fun popupScene(v: PopupSceneView) {
            val sc = v.scene ?: return
            val at = IntArray(2).also(v::getLocationInWindow)
            val x0 = at[0] + v.outset.left.toDouble()
            val y0 = at[1] + v.outset.top.toDouble()
            for (op in sc.base + sc.keys.flatMap { it.ops(false) }) when (op) {
                is Op.Fill -> items += buildJsonObject {
                    val b = op.shape.bounds
                    put("t", "fill"); put("p", "popup")
                    put("r", s.box(x0 + b.left, y0 + b.top, b.right - b.left, b.bottom - b.top))
                    putColor(op.color.value)
                    ((op.shape as? Shape.Rect)?.radii?.firstOrNull() ?: 0.0).takeIf { it > 0 }?.let { put("rad", s.dp(it)) }
                }
                is Op.Text -> items += JsonObject(mapOf("t" to JsonPrimitive("text")) + sceneText(op, x0, y0).withPart("popup"))
                else -> Unit
            }
        }

        private fun children(v: View, part: String) {
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), part)
        }

        private fun own(v: View, part: String) {
            fill(v, part)
            if (v is TextView && !v.text.isNullOrEmpty()) items += text(v, part)
            if (v is ImageView) icon(v)?.let { items += it.withPart(part) }
        }

        private fun partOf(v: View, outer: String) = when {
            v === bar -> "bar"
            v === popup -> "popup"
            v is FloatingHandle -> "handle"
            v is FloatingCodeStrip -> "preedit"
            v.javaClass.name.contains("clipboard", ignoreCase = true) -> "clipboard"
            outer == "app" && v === h.keyboard -> "keyboard"
            else -> outer
        }

        private fun fill(v: View, part: String) {
            // the bar's own colour, the picture's part, or none (a bar of blocks): where the page draws
            // the theme's bar, whatever it is
            if (isBarBackground(v)) {
                items += buildJsonObject { put("t", "bar"); put("r", s.rect(v)) }
                return
            }
            val bg = v.background ?: return
            val (color, radius) = solid(bg) ?: return
            if (color ushr 24 == 0) return
            items += buildJsonObject {
                put("t", "fill")
                put("p", part)
                put("r", s.rect(v))
                putColor(color)
                if (radius > 0f) put("rad", s.dp(radius))
            }
        }

        // the bar's colour under the bar and the code row: a plain view the bar's height, at the keyboard's top
        private fun isBarBackground(v: View) = v.javaClass == View::class.java && v.parent === h.keyboard &&
            s.top(v) == 0 && v.height >= input.kawaiiBar.view.height && v.height <= input.kawaiiBar.view.height * 2

        // a text the scene draws, (x0, y0) its scene's origin in the window
        private fun sceneText(op: Op.Text, x0: Double, y0: Double) = buildJsonObject {
            val paint = Paint().apply {
                textSize = op.size.toFloat()
                textScaleX = op.scaleX.toFloat()
                isFakeBoldText = op.weight >= BOLD
            }
            val fm = paint.fontMetrics
            put("r", s.box(x0 + op.x, y0 + op.baseline + fm.top, paint.measureText(op.text).toDouble(), (fm.bottom - fm.top).toDouble()))
            put("s", op.text)
            put("z", s.dp(op.size))
            putColor(op.color.value)
            if (op.weight >= BOLD) put("w", BOLD)
            put("y", s.dp(y0 + op.baseline - s.origin[1]))
            put("al", "l"); put("x", s.dp(x0 + op.x - s.origin[0]))
        }

        // a key the canvas draws, as its scene has it: its cell, kind, name, face and gap, and the texts
        // and icons it draws now (a text's box by the font's extent and the text's advance, its x where its line starts)
        private fun sceneKey(canvas: View, k: KeyNode): JsonObject {
            val at = IntArray(2).also(canvas::getLocationInWindow)
            val x0 = at[0].toDouble()
            val y0 = at[1].toDouble()
            val labels = mutableListOf<JsonElement>()
            val glyphs = mutableListOf<JsonElement>()
            for (op in k.ops(false)) when (op) {
                is Op.Text -> labels += JsonObject(sceneText(op, x0, y0) + ("role" to JsonPrimitive(when (op.text) { k.label -> "main"; k.alt -> "hint"; else -> "mark" })))
                is Op.Icon -> {
                    val res = SceneIcons.of(op.name) ?: continue
                    val name = canvas.resources.getResourceEntryName(res)
                    icons += name
                    glyphs += buildJsonObject {
                        put("t", "icon")
                        put("r", s.box(x0 + op.box.left, y0 + op.box.top, op.box.right - op.box.left, op.box.bottom - op.box.top))
                        put("i", name)
                        putColor(op.tint.value)
                        op.rotation.roundToInt().takeIf { it % 360 != 0 }?.let { put("rot", it) }
                    }
                }
                else -> Unit
            }
            return buildJsonObject {
                put("t", "key")
                put("r", s.box(x0 + k.bounds.left, y0 + k.bounds.top, k.bounds.right - k.bounds.left, k.bounds.bottom - k.bounds.top))
                put("k", k.kind)
                // a name the page knows (its board's positions, a role): what a theme names a key by
                if (k.id in KeyNames.ROLES || KeyNames.POSITIONS[board]?.contains(k.id) == true) put("n", "$board/${k.id}")
                put("b", when (k.face) { Face.Default -> "default"; Face.Special -> "special"; Face.Keep -> "on"; Face.None -> "off" })
                put("m", k.margin)
                if (labels.isNotEmpty()) put("l", JsonArray(labels))
                if (glyphs.isNotEmpty()) put("i", JsonArray(glyphs))
            }
        }

        private fun text(v: TextView, part: String?) = buildJsonObject {
            put("t", "text")
            part?.let { put("p", it) }
            put("r", s.rect(v))
            put("s", v.text.toString())
            put("z", s.dp(v.textSize))
            putColor(v.currentTextColor)
            if (v.typeface?.isBold == true) put("w", BOLD)
            put("y", s.dp(s.top(v) + v.baseline * v.scaleY))
            // `al`, not `a`: that is a colour's alpha (putColor), and the text has one too
            anchor(v)?.let { (al, x) -> put("al", al); put("x", s.dp(x)) }
        }

        // where the text's line is drawn across: its alignment and the x it keeps (the left, the middle or
        // the right of the line), so a font of other widths on the page lands as the app's would
        private fun anchor(v: TextView): Pair<String, Float>? {
            val layout = v.layout ?: return null
            val left = s.left(v) + (v.totalPaddingLeft - v.scrollX) * v.scaleX
            val l = left + layout.getLineLeft(0) * v.scaleX
            val r = left + layout.getLineRight(0) * v.scaleX
            val inner = (v.width - v.totalPaddingLeft - v.totalPaddingRight).toFloat()
            return when {
                layout.getLineLeft(0) <= 0.5f -> "l" to l
                layout.getLineRight(0) >= inner - 0.5f -> "r" to r
                else -> "c" to (l + r) / 2
            }
        }

        private fun icon(v: ImageView): JsonObject? {
            val res = ImageView::class.java.getDeclaredField("mResource").apply { isAccessible = true }.getInt(v)
            if (res == 0 || v.drawable == null) return null
            val name = v.resources.getResourceEntryName(res)
            icons += name
            return buildJsonObject {
                put("t", "icon")
                put("r", s.rect(v))
                put("i", name)
                tint(v)?.let { putColor(it) }
                // drawn turned this many degrees about its middle
                v.rotation.roundToInt().takeIf { it % 360 != 0 }?.let { put("rot", it) }
            }
        }
    }

    private fun JsonObject.withPart(part: String) = JsonObject(LinkedHashMap(this).apply { put("p", JsonPrimitive(part)) })

    private fun JsonObject.withRole(role: String) = JsonObject(LinkedHashMap(this).apply { remove("t"); put("role", JsonPrimitive(role)) })

    private val KeyKind.id get() = name.lowercase()

    // ------------------------------------------------------------------ colours

    private fun tint(v: ImageView): Int? {
        v.imageTintList?.let { return it.defaultColor }
        (v.colorFilter as? PorterDuffColorFilter)?.let {
            return PorterDuffColorFilter::class.java.getDeclaredMethod("getColor").apply { isAccessible = true }.invoke(it) as Int
        }
        return null
    }

    private fun solid(d: Drawable): Pair<Int, Float>? = when (d) {
        is ColorDrawable -> d.color to 0f
        is GradientDrawable -> d.color?.let { it.defaultColor to d.cornerRadius }
        else -> null
    }

    /**
     * The colour [c] as the palette's field it is (`c`), with its alpha (`a`, 0..1) when the view
     * draws the field less opaque; one no field makes, as it is.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putColor(c: Int) {
        val field = PROBE_COLORS[c or OPAQUE]
        if (field == null) {
            put("c", "#%08X".format(c))
            return
        }
        put("c", field)
        val alpha = c ushr 24
        if (alpha != 0xFF) put("a", (alpha / 255.0 * 1000).roundToInt() / 1000.0)
    }

    // ------------------------------------------------------------------ icons

    /** The vector [name] as the page draws it: its viewport and paths (filled, unless stroked). */
    private fun icon(name: String): String {
        val file = File("src/main/res/drawable/$name.xml").takeIf { it.exists() }
            ?: File("app/src/main/res/drawable/$name.xml")
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val ns = "http://schemas.android.com/apk/res/android"
        val root = doc.documentElement
        fun attr(e: org.w3c.dom.Element, a: String) = e.getAttributeNS(ns, a).takeIf { it.isNotEmpty() }
        val paths = buildJsonArray {
            fun visit(e: org.w3c.dom.Element, transform: String?) {
                val children = e.childNodes
                for (i in 0 until children.length) {
                    val c = children.item(i) as? org.w3c.dom.Element ?: continue
                    when (c.tagName) {
                        "group" -> visit(c, listOfNotNull(transform, groupTransform(c, ::attr)).joinToString(" ").ifEmpty { null })
                        "path" -> add(buildJsonObject {
                            put("d", attr(c, "pathData") ?: "")
                            attr(c, "fillType")?.let { put("rule", if (it == "evenOdd") "evenodd" else "nonzero") }
                            if (attr(c, "strokeColor") != null && attr(c, "fillColor") == null) {
                                put("stroke", attr(c, "strokeWidth")?.toDouble() ?: 1.0)
                            }
                            attr(c, "fillAlpha")?.let { put("alpha", it.toDouble()) }
                            transform?.let { put("transform", it) }
                        })
                    }
                }
            }
            visit(root, null)
        }
        return buildJsonObject {
            put("vw", attr(root, "viewportWidth")!!.toDouble())
            put("vh", attr(root, "viewportHeight")!!.toDouble())
            put("paths", paths)
        }.toString()
    }

    // a group's transform as SVG's: about its pivot, scaled and rotated, then translated
    private fun groupTransform(e: org.w3c.dom.Element, attr: (org.w3c.dom.Element, String) -> String?): String? {
        val px = attr(e, "pivotX")?.toDouble() ?: 0.0
        val py = attr(e, "pivotY")?.toDouble() ?: 0.0
        val tx = attr(e, "translateX")?.toDouble() ?: 0.0
        val ty = attr(e, "translateY")?.toDouble() ?: 0.0
        val sx = attr(e, "scaleX")?.toDouble() ?: 1.0
        val sy = attr(e, "scaleY")?.toDouble() ?: 1.0
        val r = attr(e, "rotation")?.toDouble() ?: 0.0
        if (listOf(tx, ty, sx - 1, sy - 1, r).all { it == 0.0 }) return null
        return "translate(${tx + px} ${ty + py}) rotate($r) scale($sx $sy) translate(${-px} ${-py})"
    }

    private fun String.swapSides(): String {
        val w = Regex("w(\\d+)dp").find(this)!!.groupValues[1]
        val h = Regex("h(\\d+)dp").find(this)!!.groupValues[1]
        return replace("w${w}dp", "w${h}dp").replace("h${h}dp", "h${w}dp")
    }

    private companion object {
        const val FORMAT = 1
        const val OPAQUE = 0xFF000000.toInt()
        const val BOLD = 700
        const val LONG_PRESS_MS = 600L
        const val SETTLE_MS = 1000L
        val TYPED = listOf("你好", "你", "呢", "尼", "拟")
        const val CLIP_GAP_MS = 10L
        val CLIPS = listOf("明天下午三点开会", "https://example.com/a", "收到，谢谢")

        // the palette's fields in the file's order
        val PALETTE = listOf(
            "backgroundColor", "barColor", "keyboardColor", "keyBackgroundColor", "keyTextColor", "candidateTextColor",
            "candidateLabelColor", "candidateCommentColor", "altKeyBackgroundColor", "altKeyTextColor",
            "accentKeyBackgroundColor", "accentKeyTextColor", "keyPressHighlightColor", "keyShadowColor",
            "popupBackgroundColor", "popupTextColor", "spaceBarColor", "dividerColor", "clipboardEntryColor",
            "genericActiveBackgroundColor", "genericActiveForegroundColor", "keyBorderColor", "keyHintColor", "toolbarIconColor",
        )

        // each a colour no theme draws with, all different: what a view is drawn in names its field
        private val probe = PALETTE.indices.map { i -> (0xFF000000.toInt() or (0x31 + i * 7 shl 16) or (0x52 + i * 5 shl 8) or (0x13 + i * 3)) }

        val PROBE_COLORS: Map<Int, String> = probe.zip(PALETTE).toMap()

        val PROBE: Theme = probe.let { c ->
            Theme.Custom(
                "probe", false,
                LookDefaults.look(
                    Palette(
                        c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11],
                        c[12], c[13], c[14], c[15], c[16], c[17], c[18], c[19], c[20], c[21], c[22], c[23],
                    )
                ),
            )
        }
    }
}

private const val DEFAULT_PHONE = "w393dp-h851dp-port-440dpi"
