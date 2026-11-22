/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.BaiduNames.Kind
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.fcitx.fcitx5.android.scene.theme.Values
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A Baidu skin of the classic engine (dev/design/060-skin-import.md §2.1): `.bds` for Android, `.bdi` for iOS. A
 * ZIP of one version at its top, or `light/` and `dark/` (`.bdi`: `light/skin/`, `dark/skin/`), each with
 * `port/gen.ini` (the panel, the candidate bar), the boards' INI files (`port/py_26.ini`: a key a `[KEYn]` of
 * styles, rectangles and actions), `port/res/default.css` (styles: an image of an atlas, colours, a sound) and the
 * atlases (`res/<name>.png` cut by `<name>.til`: a source rectangle and a stretchable inner one, a nine-patch).
 *
 * The 26-key pinyin board says how each kind of key looks: the style most of its letters have, and the function
 * keys', space's, enter's, backspace's and shift's by their action codes ([BaiduNames]). Keys without an action are
 * decoration, not taken. The pinyin boards' key positions are [SkinLook.layouts], which the theme fits the app's on.
 */
class BaiduSkin(private val f: SkinFiles) : SkinSource {
    private val roots: Map<Variant, String>
    override val name: String
    override val author: String
    override val preview: ByteArray?

    init {
        val found = f.names.mapNotNull { n -> n.takeIf { it.endsWith(GEN) }?.removeSuffix(GEN) }.sortedBy { it.length }
        if (found.isEmpty()) throw SkinError(SkinError.Reason.UNKNOWN)
        roots = LinkedHashMap<Variant, String>().apply {
            for (r in found) getOrPut(if (r.split('/').contains("dark")) Variant.DARK else Variant.LIGHT) { r }
        }
        val info = f.first("Info.txt", found[0] + "Info.txt")
        val text = f.text(info)
        // Info.txt is key=value lines, under a section or none
        val meta = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq > 0) meta.getOrPut(line.substring(0, eq).trim()) { line.substring(eq + 1).trim() }
        }
        name = meta["Name"].orEmpty()
        author = meta["Author"].orEmpty()
        preview = f.first("demo.png", found[0] + "demo.png", found[0] + "port/demo.png")?.let(f::bytes)
    }

    override val variants: Set<Variant> get() = roots.keys

    // icons and letters' pictures only the first version's are written
    override fun look(variant: Variant): SkinLook = Reader(roots.getValue(variant), variant == roots.keys.minBy { it.ordinal }).look()

    private inner class Reader(private val r: String, private val primary: Boolean) {
        private val css = f.ini(f.first(r + "port/res/default.css", r + "res/default.css"))
        private val anims = f.ini(f.first(r + "port/res/anim.ini", r + "res/anim.ini"))
        private val tiles = HashMap<String, SkinFill.Image?>()
        private var letterImages = false
        private var textSize = false

        // the press animations carried over as a key's press-scale or press-sink
        private val pressUsed = HashSet<String>()

        private fun style(id: String?): Map<String, String> = id?.trim()?.takeIf { it.isNotEmpty() }?.let { css["STYLE$it"] }.orEmpty()

        private fun styles(list: String?): List<String> = list?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        // 'atlas,n': the n-th image of res/atlas.png as its .til cuts it
        private fun tile(ref: String?): SkinFill.Image? {
            val parts = ref?.split(',')?.map { it.trim() } ?: return null
            if (parts.size < 2 || parts[0].isEmpty()) return null
            val key = parts[0] + "," + parts[1]
            if (key in tiles) return tiles[key]
            val atlas = f.image(f.first(r + "res/" + parts[0] + ".png", r + "port/res/" + parts[0] + ".png"))
            val til = f.ini(f.first(r + "res/" + parts[0] + ".til", r + "port/res/" + parts[0] + ".til"))["IMG" + parts[1]].orEmpty()
            val src = SkinText.ints(til["SOURCE_RECT"], RECT)?.takeIf { it[2] > 0 && it[3] > 0 }
            val out = if (atlas == null || src == null) {
                null
            } else {
                val x = src[0].coerceIn(0, atlas.width - 1)
                val y = src[1].coerceIn(0, atlas.height - 1)
                val w = minOf(src[2], atlas.width - x)
                val h = minOf(src[3], atlas.height - y)
                val inner = SkinText.ints(til["INNER_RECT"], RECT)
                val insets = inner?.let {
                    Pixels.Box(maxOf(0, it[0] - x), maxOf(0, it[1] - y), maxOf(0, x + w - it[0] - it[2]), maxOf(0, y + h - it[1] - it[3]))
                }
                SkinFill.Image(atlas.pixels(x, y, w, h), insets)
            }
            tiles[key] = out
            return out
        }

        // a back style: its face and pressed face, an image or a colour
        private fun face(id: String?): Pair<SkinFill?, SkinFill?> {
            val s = style(id)
            val nm = tile(s["NM_IMG"]) ?: SkinText.color(s["NM_COLOR"])?.let(SkinFill::Color)
            val hl = tile(s["HL_IMG"]) ?: SkinText.color(s["HL_COLOR"])?.let(SkinFill::Color)
            return nm to hl
        }

        // a fore style list's first: the label's colour and pressed colour (of the glyph where it is a picture)
        private fun label(fore: String?): Pair<Int?, Int?> {
            val s = style(fore?.split(',')?.firstOrNull())
            if ("NM_COLOR" in s && "NM_IMG" !in s) return SkinText.color(s["NM_COLOR"]) to SkinText.color(s["HL_COLOR"])
            val nm = tile(s["NM_IMG"])
            return nm?.pixels?.glyphColor() to tile(s["HL_IMG"])?.pixels?.glyphColor()
        }

        // a fore style list's second, where it is a colour: the mark's, at rest and pressed
        private fun mark(fore: String?): Pair<Int?, Int?> {
            val s = style(styles(fore).getOrNull(1))
            if ("NM_COLOR" !in s || "NM_IMG" in s) return null to null
            return SkinText.color(s["NM_COLOR"]) to SkinText.color(s["HL_COLOR"])
        }

        // a fore style list's first, where it is a picture: at rest and pressed
        private fun picture(fore: String?): SkinIcon? {
            val s = style(styles(fore).firstOrNull())
            val nm = tile(s["NM_IMG"]) ?: return null
            return SkinIcon(nm.pixels, tile(s["HL_IMG"])?.pixels)
        }

        fun look(): SkinLook {
            val gen = f.ini(r + GEN)
            val size = SkinText.ints(gen["PANEL"]?.get("SIZE"), 2)
            val pw = size?.get(0)?.takeIf { it in MIN_WIDTH..MAX_SIDE } ?: DEFAULT_WIDTH
            val ph = size?.get(1)?.takeIf { it in 1..MAX_SIDE } ?: (pw * PANEL_RATIO).roundToInt()
            val look = SkinLook(pw)
            val cand = f.ini(r + "port/" + (gen["CAND"]?.get("LAYOUT_NAME") ?: "cand1") + ".cnd")
            val ch = SkinText.ints(gen["CAND"]?.get("VIEW_RECT"), RECT)?.get(3)?.takeIf { it in 1..MAX_SIDE / 2 } ?: (pw * BAR_RATIO).roundToInt()
            lay(look, face(gen["PANEL"]?.get("BACK_STYLE")?.split(',')?.firstOrNull()).first, face(cand["CAND"]?.get("BACK_STYLE")).first, pw, ph, ch)
            look.candidate = label(cand["CAND"]?.get("FORE_STYLE")).first
            look.first = label(cand["CAND"]?.get("FIRST_FORE")).first ?: look.candidate
            look.preedit = label(gen["INPUT"]?.get("FORE_STYLE")).first
            keys(look, pw)
            if (primary) for (name in BOARDS) board(name, pw)?.let { look.layouts += it }
            if (primary) barIcons(look, cand)
            val hint = f.ini(r + "port/" + (gen["HINT"]?.get("LAYOUT_NAME") ?: "hint1") + ".pop")
            val icon = hint["ICON" + (hint["HINT"]?.get("BACK_ICON") ?: "1")].orEmpty()
            look.hintFill = face(icon["BACK_STYLE"]).first
            look.hintText = label(icon["FORE_STYLE"]).first
            sound(look, gen)
            // the theme takes it back where it fits the boards on the app's
            look.notes += SkinNote.LAYOUT
            animations(look)
            if (textSize) look.notes += SkinNote.TEXT_SIZE
            if (letterImages) look.notes += SkinNote.LETTER_IMAGES
            if (f.imageFailed) look.notes += SkinNote.IMAGES
            return look
        }

        private fun keys(look: SkinLook, pw: Int) {
            val ini = f.ini(board() ?: throw SkinError(SkinError.Reason.NO_KEYBOARD))
            val keys = ini.mapNotNull { (section, key) -> keyed(look, section, key) }
            if (primary) for (k in keys) BaiduNames.icon(k.action)?.let { icon(look, it, k, ini) }
            val letters = keys.filter { it.kind == Kind.LETTER }
            letterPictures(look, letters.mapNotNull { k -> BaiduNames.letter(k.action)?.let { it to styles(k.fore).firstOrNull() } }.distinctBy { it.first }.toMap())
            styleKinds(look, keys.groupBy { it.kind }, pw)
            val base = look.keys[KEY] ?: throw SkinError(SkinError.Reason.NO_KEYBOARD)
            if (LETTER in look.keys) look.keys[KEY] = without(base)
            look.inset = inset(letters.mapNotNull { SkinText.ints(it.key["VIEW_RECT"], RECT) }, base.fill, pw)
        }

        // a board's key of a kind styled; none for another (one with no action drawn over the board: a sticker, a shadow)
        private fun keyed(look: SkinLook, section: String, key: Map<String, String>): Keyed? {
            if (!section.startsWith("KEY")) return null
            val back = key["BACK_STYLE"]?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val action = key["CENTER"]?.trim().orEmpty()
            if (action.isEmpty() && style(back)["NM_IMG"] != null) look.notes += SkinNote.DECORATION
            val kind = BaiduNames.kind(action) ?: return null
            return Keyed(back, key["FORE_STYLE"].orEmpty(), key, action, kind)
        }

        // each kind's most common face as its style
        private fun styleKinds(look: SkinLook, kinds: Map<Kind, List<Keyed>>, pw: Int) {
            var function: Pair<String, SkinKey>? = null
            for ((kind, list) in Kind.entries.mapNotNull { k -> kinds[k]?.let { k to it } }) {
                val back = list.groupingBy { it.back }.eachCount().maxBy { it.value }.key
                val k = list.first { it.back == back }
                val key = key(k, kind, if (kind == Kind.LETTER) list else listOf(k), pw)
                if (kind == Kind.FUNCTION) function = back to key
                if (asFunction(kind, back, key, function)) continue
                look.keys[kind.selector] = key
                // the letters' size and weight on letters alone, not on the function keys under `key`
                if (kind == Kind.LETTER && (key.scale != null || key.weight != null)) look.keys[LETTER] = key
            }
        }

        private fun key(k: Keyed, kind: Kind, sized: List<Keyed>, pw: Int): SkinKey {
            val (nm, hl) = face(k.back)
            val (color, pressedColor) = label(k.fore)
            val (mark, pressedMark) = mark(k.fore)
            val scale = BaiduNames.scale(sized.mapNotNull(::ratio))
            if (scale?.noted == true) textSize = true
            val press = press(k.key, pw / SkinTheme.DP_ACROSS)
            return SkinKey(
                nm, hl, color, pressedColor, icons = kind != Kind.LETTER,
                markColor = mark, pressedMarkColor = pressedMark,
                scale = scale?.percent, weight = BaiduNames.weight(style(styles(k.fore).firstOrNull())["FONT_WEIGHT"]?.trim()?.toIntOrNull()),
                pressScale = press.scale, transitionMs = press.ms, pressSink = press.sink,
            )
        }

        // a letter's text size over its key's height
        private fun ratio(k: Keyed): Double? {
            val size = style(styles(k.fore).firstOrNull())["FONT_SIZE"]?.trim()?.toIntOrNull()?.takeIf { it in 1..MAX_SIDE } ?: return null
            val h = SkinText.ints(k.key["VIEW_RECT"], RECT)?.get(3)?.takeIf { it > 0 } ?: return null
            return size.toDouble() / h
        }

        // a function code's picture as the app's icon of it, the first key's that has one; shift's states by STAT_STYLE
        private fun icon(look: SkinLook, name: String, k: Keyed, ini: Ini) {
            if (name !in look.icons) picture(k.fore)?.let { look.icon(name, it.normal, it.pressed) }
            if (name != "shift") return
            for (m in k.key["STAT_STYLE"].orEmpty().split('|').mapNotNull { STATE.matchEntire(it.trim()) }) {
                val state = SHIFT_STATES[m.groupValues[1].toIntOrNull()]?.takeIf { it !in look.icons }
                state?.let { picture(ini["TIP" + m.groupValues[2]]?.get("FORE_STYLE")) }?.let { look.icon(state, it.normal, it.pressed) }
            }
        }

        // 26 letters each its own picture: stickers; a picture letters share is a glyph's (its colour taken), not a letter's
        private fun letterPictures(look: SkinLook, pictures: Map<Char, String?>) {
            val refs = pictures.values.map { style(it)["NM_IMG"] }
            if (refs.all { it == null }) return
            val own = pictures.size == LETTERS && refs.all { it != null } && refs.toSet().size == refs.size
            if (!own) {
                letterImages = true
                return
            }
            if (!primary) return
            val mine = pictures.mapValues { picture(it.value)?.normal }
            // stickers are one for all versions: another's letters in pictures of their own (light on dark) would be lost under them
            if (roots.values.any { it != r && !Reader(it, false).drawsLetters(mine) }) {
                letterImages = true
                return
            }
            for ((c, id) in pictures) picture(id)?.let { look.letterImage(c, it.normal, it.pressed) }
        }

        private fun board(): String? = f.first(r + "port/py_26.ini", r + "port/def_26.ini", r + "port/en_26.ini")

        // whether this version's letters are [pictures], pixel for pixel
        fun drawsLetters(pictures: Map<Char, Pixels?>): Boolean {
            val keys = f.ini(board()).filterKeys { it.startsWith("KEY") }.values
            val mine = keys.mapNotNull { k -> BaiduNames.letter(k["CENTER"]?.trim().orEmpty())?.let { it to k["FORE_STYLE"] } }.distinctBy { it.first }
            return mine.size == pictures.size && mine.all { (c, fore) ->
                val a = picture(fore)?.normal
                val b = pictures[c]
                a != null && b != null && a.width == b.width && a.height == b.height && a.argb.contentEquals(b.argb)
            }
        }

        // a key's press animation as far as a theme has one: a scale, a sink (dp, [px] skin pixels each)
        private fun press(key: Map<String, String>, px: Double): Press {
            var scale: Int? = null
            var sink: Double? = null
            var ms: Int? = null
            for (id in ANIM_KEYS.flatMap { styles(key[it]) }.distinct()) {
                val anim = style(id)["PRESS_ANIM"]?.trim() ?: continue
                when (val t = tween(anim, px)) {
                    is Tween.Scale -> if (scale == null) { scale = t.percent; ms = t.ms ?: ms; pressUsed += anim }
                    is Tween.Sink -> if (sink == null) { sink = t.dp; ms = ms ?: t.ms; pressUsed += anim }
                    else -> Unit
                }
            }
            return Press(scale, ms, sink)
        }

        // an animation by its fields (its TYPE numbers differ from skin to skin): a BUILD_LIST's steps, one that moves
        private fun tween(id: String, px: Double): Tween {
            val a = anims["ANIM$id"] ?: return Tween.Other
            val steps = a["BUILD_LIST"]?.let { list -> styles(list).take(MAX_STEPS).map { anims["ANIM$it"] ?: return Tween.Other } } ?: listOf(a)
            val moving = steps.map { step(it, px) }.filter { it != Tween.None }
            return moving.singleOrNull() ?: if (moving.isEmpty()) Tween.None else Tween.Other
        }

        private fun step(a: Map<String, String>, px: Double): Tween {
            if ("CATEGORY" in a) return Tween.Other
            val ms = a["DURATION"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
            SkinText.ints(a["FROM_PX"], 2)?.let { from ->
                val to = SkinText.ints(a["TO_PX"], 2) ?: return Tween.Other
                val dx = to[0] - from[0]
                val dy = to[1] - from[1]
                return when {
                    dx == 0 && dy == 0 -> Tween.None
                    dx == 0 && dy > 0 && dy / px <= Values.MAX_PRESS_SINK_DP -> Tween.Sink(dy / px, ms)
                    else -> Tween.Other
                }
            }
            val from = SkinText.ints(a["FROM"], 2)
            val to = SkinText.ints(a["TO"], 2)
            if (from != null && to != null) {
                return when {
                    from.contentEquals(to) -> Tween.None
                    from[0] == PERCENT && from[1] == PERCENT && to[0] == to[1] -> Tween.Scale(to[0], ms)
                    else -> Tween.Other
                }
            }
            // one number each: its alpha, not a theme's
            val one = a["FROM"]?.trim()
            return if (one != null && one == a["TO"]?.trim()) Tween.None else Tween.Other
        }

        // what moves and is not a key's press-scale or press-sink: the keyboard's showing, the rest of a press
        private fun animations(look: SkinLook) {
            val px = look.width / SkinTheme.DP_ACROSS
            for (s in css.values) {
                s["SHOW_ANIM"]?.trim()?.let { if (tween(it, px) != Tween.None) look.notes += SkinNote.KEYBOARD_ANIMATION }
                s["PRESS_ANIM"]?.trim()?.let { if (it !in pressUsed && tween(it, px) != Tween.None) look.notes += SkinNote.ANIMATION }
            }
        }

        // a pinyin board's keys where they are, panel pixels ([pw] across), by the app's layout of its letters; none where it is no such board
        private fun board(name: String, pw: Int): SkinBoard? {
            val file = r + "port/" + name
            if (file !in f) return null
            val keys = f.ini(file).filterKeys { it.startsWith("KEY") }.values
            val actions = keys.map { it["CENTER"]?.trim().orEmpty() }
            val id = BaiduNames.boardId(actions.mapNotNull(BaiduNames::letter).toSet(), nine = name.contains("_9")) ?: return null
            // a nine or grouped board's letters drawn as pictures: not a sticker's one letter
            if (id != "qwerty" && keys.any { BaiduNames.letter(it["CENTER"]?.trim().orEmpty()) != null && style(styles(it["FORE_STYLE"]).firstOrNull())["NM_IMG"] != null }) {
                letterImages = true
            }
            val boxes = keys.zip(actions).mapNotNull { (k, action) ->
                val at = rect(k["TOUCH_RECT"]) ?: rect(k["VIEW_RECT"]) ?: return@mapNotNull null
                SkinKeyBox(at[0].toDouble(), at[1].toDouble(), at[2].toDouble(), at[3].toDouble(), BaiduNames.action(action, id == "nine"))
            }
            if (boxes.isEmpty()) return null
            return SkinBoard(id, boxes.groupBy { it.y }.entries.sortedBy { it.key }.map { it.value }, pw.toDouble())
        }

        private fun rect(v: String?): IntArray? = SkinText.ints(v, RECT)?.takeIf { it[2] > 0 && it[3] > 0 }

        // the toolbar's pictures (`.bdi`: res/logo/<version>/toolbarMenuItem_<name>_Normal…) and the candidate bar's
        // expand button as the app's icons; how many there are of neither said
        private fun barIcons(look: SkinLook, cand: Ini) {
            val named = f.names.filter { it.startsWith(r + "res/logo/") }.sorted().mapNotNull { n -> TOOLBAR.matchEntire(n.substringAfterLast('/'))?.let { it.groupValues to n } }
            val (pressed, normal) = named.partition { it.first[2] == "Select" }
            val names = normal.distinctBy { it.first[1] }
            // the shortest name of an icon's: Emoji, not EmojiTranslation
            val found = names.mapNotNull { (g, n) -> SkinIcons.byToolbarName(g[1])?.let { Triple(it, g[1], n) } }
                .groupBy { it.first }.mapValues { (_, v) -> v.minBy { it.second.length }.third }
            for ((icon, at) in found) if (icon !in look.icons) f.pixels(at)?.let { look.icon(icon, it, null) }
            val missed = names.count { SkinIcons.byToolbarName(it.first[1]) == null } + candIcons(look, cand)
            // a toolbar button's pressed picture: the toolbar has no pressed icon of its own
            val select = pressed.any { SkinIcons.byToolbarName(it.first[1]) != null }
            if (missed > 0) look.notes[SkinNote.TOOLBAR_ICONS] = listOf(missed.toString()) else if (select) look.notes += SkinNote.TOOLBAR_ICONS
        }

        // the candidate bar's buttons: the expand one's picture the app's icon of it; how many others there are
        private fun candIcons(look: SkinLook, cand: Ini): Int {
            var others = 0
            for (s in cand.filterKeys { it.startsWith("ICON") }.values) {
                val p = picture(s["FORE_STYLE"]) ?: continue
                if (s["KEY"]?.trim() != EXPAND) others++ else if (EXPAND_ICON !in look.icons) look.icon(EXPAND_ICON, p.normal, p.pressed)
            }
            return others
        }

        // the gap around a letter, dp: the row's pitch less the key's drawn body. A view rect wider than the pitch
        // (iOS .bdi: they overlap, the image drawn at its own size, not stretched) shows the image's opaque part;
        // a narrower one stretches the image to it, its transparent margins (in the corners) kept
        private fun inset(rects: List<IntArray>, fill: SkinFill?, pw: Int): Pair<Int, Int>? {
            if (rects.size < 3) return null
            fun median(xs: List<Int>) = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            val rows = rects.groupBy { it[1] }
            val pitchX = median(rows.values.flatMap { row -> row.sortedBy { it[0] }.zipWithNext { a, b -> b[0] - a[0] } })
            val pitchY = median(rows.keys.sorted().zipWithNext { a, b -> b - a })
            val vw = median(rects.map { it[2] })!!
            val vh = median(rects.map { it[3] })!!
            val img = (fill as? SkinFill.Image)?.pixels
            val box = img?.opaqueBounds() ?: Pixels.Box(0, 0, img?.width ?: vw, img?.height ?: vh)
            val iw = img?.width ?: vw
            val ih = img?.height ?: vh
            fun gap(pitch: Int?, view: Int, size: Int, body: Int): Double {
                if (pitch == null || pitch <= 0) return 0.0
                val drawn = if (view > pitch * OVERLAP) body else view - (size - body)
                return (pitch - drawn) / 2.0
            }
            val px = pw / SkinTheme.DP_ACROSS
            fun dp(v: Double) = (v / px).roundToInt().coerceIn(0, MAX_INSET_DP)
            return dp(gap(pitchY, vh, ih, box.height)) to dp(gap(pitchX, vw, iw, box.width))
        }

        private fun sound(look: SkinLook, gen: Ini) {
            val path = style(gen["PANEL"]?.get("SOUND_STYLE"))["PRESS_SOUND_PATH"]?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val at = f.first(r + "res/" + path, r + path, path)
            val got = at?.let { f.bytes(it) }?.let { SkinSound.convert(path, it) }
            if (got != null) look.sounds[SoundKey.LETTER] = got else look.notes += SkinNote.SOUND
        }
    }

    private class Keyed(val back: String, val fore: String, val key: Map<String, String>, val action: String, val kind: Kind)

    private class Press(val scale: Int?, ms: Int?, val sink: Double?) {
        val ms = ms?.coerceAtMost(MAX_PRESS_MS)
    }

    private sealed interface Tween {
        data object None : Tween
        data object Other : Tween
        class Scale(val percent: Int, val ms: Int?) : Tween
        class Sink(val dp: Double, val ms: Int?) : Tween
    }

    companion object {
        private const val GEN = "port/gen.ini"
        private const val RECT = 4
        private const val KEY = "key"
        private const val LETTER = "key.letter"
        private const val LETTERS = 26
        private const val PERCENT = 100
        private const val MAX_STEPS = 16
        private const val EXPAND = "F9"
        private const val EXPAND_ICON = "candidates-expand"

        // a press animation's time at most, ms: a longer one would lag behind typing
        private const val MAX_PRESS_MS = 80
        private const val MAX_INSET_DP = 24
        private const val OVERLAP = 1.05
        private const val DEFAULT_WIDTH = 1080
        private const val MIN_WIDTH = 100
        private const val MAX_SIDE = 4096
        private const val PANEL_RATIO = 0.56
        private const val BAR_RATIO = 0.11
        private const val GREY = 0xFF808080.toInt()
        private val BOARDS = listOf("py_26.ini", "py_9.ini", "py_18.ini", "py_14.ini")
        private val ANIM_KEYS = listOf("ANIM_STYLE", "BACK_ANIM_STYLE", "FORE_ANIM_STYLE")
        private val TOOLBAR = Regex("toolbarMenuItem_(.+)_(Normal|Select)(_.*)?\\.png")

        // `S<state>_<tip>`: the key in a state looks as its board's [TIP<tip>]
        private val STATE = Regex("S(\\d{1,4})_(\\d{1,4})")
        private val SHIFT_STATES = mapOf(1 to "shift-once", 2 to "shift-locked")

        // backspace and shift as the other function keys: one style for them all
        private fun asFunction(kind: Kind, back: String, key: SkinKey, fn: Pair<String, SkinKey>?) =
            (kind == Kind.BACKSPACE || kind == Kind.SHIFT) && fn != null && fn.first == back && same(fn.second, key)

        // a kind's style that is the function keys' but for what only it says
        internal fun same(a: SkinKey, b: SkinKey) = a.color == b.color && a.markColor == b.markColor && a.pressedMarkColor == b.pressedMarkColor &&
            a.scale == b.scale && a.weight == b.weight && a.pressScale == b.pressScale && a.transitionMs == b.transitionMs && a.pressSink == b.pressSink

        private fun without(k: SkinKey) = SkinKey(
            k.fill, k.pressed, k.color, k.pressedColor, k.icons, k.drawn, k.pressedIconColor, k.markColor, k.pressedMarkColor,
            pressScale = k.pressScale, transitionMs = k.transitionMs, pressSink = k.pressSink,
        )

        /** The bar's background ([ch] high, none at 0) over the keyboard's, one picture; translucent (iOS blur skins) laid on its own mean. */
        internal fun lay(look: SkinLook, panel: SkinFill?, bar: SkinFill?, pw: Int, ph: Int, ch: Int) {
            if (panel !is SkinFill.Image && bar !is SkinFill.Image) {
                look.boardColor = (panel as? SkinFill.Color)?.argb
                look.barColor = (bar as? SkinFill.Color)?.argb ?: look.boardColor
                return
            }
            // the picture made within MAX_PICTURE_SIDE and MAX_IMAGE_PIXELS (gen.ini's sizes are the skin's word, a
            // few bytes for 4096×6144): never put together larger than the theme keeps it
            val total = ch.toLong() + ph
            val s = minOf(
                1.0, SkinTheme.MAX_PICTURE_SIDE.toDouble() / pw, SkinTheme.MAX_PICTURE_SIDE.toDouble() / total,
                sqrt(SkinLimits.MAX_IMAGE_PIXELS.toDouble() / (pw * total)),
            )
            if (s < 1) {
                drawBoard(look, panel, bar, maxOf(1, (pw * s).roundToInt()), maxOf(1, (ph * s).roundToInt()), if (ch > 0) maxOf(1, (ch * s).roundToInt()) else 0)
            } else {
                drawBoard(look, panel, bar, pw, ph, ch)
            }
        }

        private fun drawBoard(look: SkinLook, panel: SkinFill?, bar: SkinFill?, pw: Int, ph: Int, ch: Int) {
            val board = Pixels(pw, ch + ph)
            when (panel) {
                is SkinFill.Image -> {
                    board.paste(panel.pixels.nine(panel.insets, pw, ph), 0, ch)
                    look.boardColor = panel.pixels.average(keepAlpha = false)
                }
                is SkinFill.Color -> { board.paste(Pixels.filled(pw, ph, panel.argb), 0, ch); look.boardColor = panel.argb }
                null -> Unit
            }
            when (val b = bar.takeIf { ch > 0 }) {
                is SkinFill.Image -> {
                    val strip = b.pixels.nine(b.insets, pw, ch)
                    board.over(strip, 0, 0)
                    look.barColor = strip.average(keepAlpha = false)
                }
                is SkinFill.Color -> { board.paste(Pixels.filled(pw, ch, b.argb), 0, 0); look.barColor = b.argb }
                // no bar background: the keyboard's shows through it in Baidu too (panel is an image here: checked above)
                null -> {
                    if (ch > 0) board.paste((panel as SkinFill.Image).pixels.nine(panel.insets, pw, ch), 0, 0)
                    look.barColor = look.boardColor
                }
            }
            if (board.translucent()) {
                // laid on its own mean, in place: no second picture of its size
                val mean = board.average(keepAlpha = false) ?: GREY
                for (y in 0 until board.height) {
                    val laid = Pixels.filled(pw, 1, mean).also { it.over(board.crop(0, y, pw, 1)!!, 0, 0) }
                    board.paste(laid, 0, y)
                }
            }
            look.board = board
        }

        /** Whether [names] are a Baidu classic skin's. */
        fun accepts(names: Set<String>) = names.any { it.endsWith(GEN) }
    }
}
