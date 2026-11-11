/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.theme.SoundKey
import kotlin.math.roundToInt

/**
 * A Sogou phone skin (`.ssf`, a plain ZIP; dev/design/060-skin-import.md §2.2): `phoneTheme.ini` (name, author),
 * `time.ini` (periods of the day, each a folder), `<width>/[<period>/]phoneSkin.ini` (the background under bar and
 * keys, the bar's, key faces by class: `KeyBg_Default`, `KeyBg_Function_Qwerty`, `KeyBg_Space_Qwerty`; single keys
 * `Key_<X>`; text colours `TextStyle_*`), an image section's `NORMAL=file.png,left,right,top,bottom` (the stretchable
 * band) and `default/` (sounds, fonts). The keys' places are not in the skin (they go by class), so a Sogou skin
 * fits the app's layouts as it is. The day's period (the one holding noon) is the light version, another the dark.
 *
 * Written from the format's notes and the skins inside Sogou's own app, checked on skins made for the tests: no
 * real downloaded one has been read yet.
 */
class SogouSkin(private val f: SkinFiles) : SkinSource {
    private val res: String
    private val roots = LinkedHashMap<Variant, String>()
    private var periods = 0
    override val name: String
    override val author: String
    override val preview: ByteArray?

    init {
        val sizes = f.names.mapNotNull { SKIN_AT.matchEntire(it)?.groupValues?.get(1) }.distinct()
        if (sizes.isEmpty()) throw SkinError(SkinError.Reason.NO_KEYBOARD)
        val time = f.ini("time.ini")
        val supported = time["General"]?.get("SUPPORT_SCREEN").orEmpty().split(',', ';', ' ').map { it.trim() }.filter { it in sizes }
        res = (supported.ifEmpty { sizes }).maxBy { it.toIntOrNull() ?: 0 }
        val folders = f.names.mapNotNull { n -> n.removePrefix("$res/").takeIf { n.startsWith("$res/") }?.let(PERIOD::matchEntire)?.groupValues?.get(1) }
            .distinct().sorted()
        if (folders.isEmpty()) {
            roots[Variant.LIGHT] = "$res/"
        } else {
            val listed = time[res]?.get("STATUS_LIST").orEmpty().split(',').map { it.trim() }.filter { it in folders }
            val order = listed.ifEmpty { folders }
            periods = order.size
            fun hours(key: String, period: String) = time[period]?.get(key).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
            fun noon(period: String): Boolean {
                val ends = hours("END", period)
                return hours("START", period).withIndex().any { (i, start) -> start <= NOON && NOON <= (ends.getOrNull(i) ?: -1) }
            }
            val day = order.firstOrNull(::noon) ?: order[0]
            roots[Variant.LIGHT] = "$res/$day/"
            order.firstOrNull { it != day }?.let { roots[Variant.DARK] = "$res/$it/" }
        }
        val general = f.ini("phoneTheme.ini")["General"].orEmpty()
        name = general["skin_name"].orEmpty()
        author = general["skin_author"].orEmpty()
        preview = f.names.sorted().firstOrNull { PREVIEW.containsMatchIn(it) }?.let(f::bytes)
    }

    override val variants: Set<Variant> get() = roots.keys

    override fun look(variant: Variant): SkinLook = Reader(roots.getValue(variant)).look().also {
        if (periods > 2) it.notes += SkinNote.MORE_PERIODS
    }

    private inner class Reader(private val r: String) {
        private val skin = f.ini(r + "phoneSkin.ini")

        private fun file(name: String?): String? {
            val n = name?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return f.first(r + n, "$res/$n", "default/$n", "default/res/$n", n)
        }

        // 'file.png,left,right,top,bottom' (the stretchable band as pixel ranges): the image and its insets
        private fun image(value: String?): SkinFill.Image? {
            val parts = value?.split(',')?.map { it.trim() } ?: return null
            if (!IMAGE.matches(parts[0]) || parts[0].equals("transparent.png", ignoreCase = true)) return null
            val px = f.pixels(file(parts[0])) ?: return null
            val band = SkinText.ints(parts.drop(1).joinToString(","), BAND)
            val insets = band?.takeIf { parts.size == BAND + 1 && it[0] < it[1] && it[2] < it[3] }?.let {
                Pixels.Box(it[0], it[2], maxOf(0, px.width - it[1]), maxOf(0, px.height - it[3]))
            }
            return SkinFill.Image(px, insets)
        }

        private fun bgOf(section: String) = skin[section]?.get("BG_IMAGES")?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        private fun pair(section: String?): Pair<SkinFill.Image?, SkinFill.Image?> {
            val s = skin[section ?: return null to null].orEmpty()
            return image(s["NORMAL"]) to image(s["PRESSED"])
        }

        private fun textStyle(vararg names: String): Pair<Int?, Int?> {
            val s = names.firstNotNullOfOrNull { skin[it]?.takeIf { s -> "TEXT_COLOR" in s } } ?: return null to null
            return SkinText.color(s["TEXT_COLOR"]) to SkinText.color(s["TEXT_COLOR_PRESSED"])
        }

        fun look(): SkinLook {
            val look = SkinLook(res.toIntOrNull()?.takeIf { it in MIN_WIDTH..MAX_WIDTH } ?: DEFAULT_WIDTH)
            board(look)
            keys(look)
            look.candidate = textStyle("TextStyle_Candidate").first
            look.first = textStyle("TextStyle_Candidate_Highlight").first
            look.preedit = textStyle("TextStyle_Composing").first
            look.hintText = textStyle("TextStyle_Popup_Qwerty", "TextStyle_Popup").first
            skin["TextStyle_Qwerty"]?.get("TYPEFACE")?.let(::file)?.takeIf { it.lowercase().endsWith(".ttf") || it.lowercase().endsWith(".otf") }?.let {
                look.font = it.substringAfterLast('/').lowercase() to f.bytes(it)!!
            }
            sounds(look)
            if (f.names.any { it.endsWith("anim.ini") || it.lowercase().endsWith(".webp") }) look.notes += SkinNote.ANIMATION
            if (skin.values.any { "TEXT_SIZE" in it }) look.notes += SkinNote.TEXT_SIZE
            if (f.imageFailed) look.notes += SkinNote.IMAGES
            return look
        }

        // one picture under the bar and the keys (RootView), the bar's own image laid over its top
        private fun board(look: SkinLook) {
            val root = skin["RootView"].orEmpty()
            val picture = if (root["BG_STYLE"]?.trim() == "2") null else listOf("RootView", "WALLPAPER").firstNotNullOfOrNull { image(skin[it]?.get("BG_IMAGE")) }
            if (picture == null) look.boardColor = SkinText.color(root["BG_COLOR"])
            val barRef = skin["CandidateView"]?.get("BG_IMAGE")?.trim().orEmpty()
            val bar = if (barRef in skin) image(skin[barRef]?.get("NORMAL")) else image(barRef)
            if (picture != null) {
                val board = Pixels(picture.pixels.width, picture.pixels.height).also { it.paste(picture.pixels, 0, 0) }
                look.boardColor = board.average(keepAlpha = false)
                val h = if (bar != null) maxOf(1, (bar.pixels.height.toDouble() * board.width / look.width).roundToInt()) else (board.width * BAR_RATIO).roundToInt()
                look.barHeight = minOf(h, board.height)
                if (bar != null) {
                    board.over(bar.pixels.nine(bar.insets, board.width, look.barHeight), 0, 0)
                    look.barColor = board.crop(0, 0, board.width, look.barHeight)?.average(keepAlpha = false)
                }
                look.board = board
            } else if (bar != null) {
                look.barColor = bar.pixels.average(keepAlpha = false)
            }
            look.barColor = look.barColor ?: look.boardColor
        }

        private fun keys(look: SkinLook) {
            val letter = textStyle("TextStyle_Qwerty", "TextStyle")
            val function = textStyle("TextStyle_FuncKey_26char", "TextStyle")
            val space = textStyle("TextStyle_Space_Label", "TextStyle")
            for (kind in listOf(
                Kind("key", listOf("KeyBg_Default_Latin", "KeyBg_Default"), emptyList(), letter),
                Kind("key.function", listOf("KeyBg_Function_Qwerty", "KeyBg_Function"), emptyList(), function),
                Kind("key.space", listOf("KeyBg_Space_Qwerty", "KeyBg_Space"), listOf("Key_Space"), space),
                Kind("key.enter", emptyList(), listOf("Key_Enter_Qwerty", "Key_Enter"), function),
                Kind("key[role=backspace]", emptyList(), listOf("Key_Backspace_Qwerty", "Key_Backspace"), function),
                Kind("key[role=shift]", emptyList(), listOf("Key_Shift_Qwerty", "Key_Shift"), function),
            )) {
                val section = kind.owners.firstNotNullOfOrNull(::bgOf) ?: kind.defaults.firstOrNull { it in skin }
                val (nm, hl) = pair(section)
                if (nm == null) continue
                val icon = kind.owners.firstNotNullOfOrNull { image(skin[it]?.get("ICON")) }
                val color = kind.colors.first ?: icon?.pixels?.glyphColor()
                look.keys[kind.selector] = SkinKey(nm, hl, color, kind.colors.second, icons = kind.selector != "key")
            }
            // one letter styled on its own (Key_Q, Key_H_PY): by its name on the 26 keys
            for (section in skin.keys) {
                val m = SINGLE.matchEntire(section) ?: continue
                val (nm, hl) = pair(bgOf(section))
                if (nm != null) look.keys["qwerty/" + m.groupValues[1].lowercase()] = SkinKey(nm, hl, null, null, icons = false)
            }
            val key = look.keys["key"] ?: throw SkinError(SkinError.Reason.NO_KEYBOARD)
            // letters drawn as pictures (keys.ini FG_IMAGES): their colour is all that carries over
            if (key.color == null) {
                val keys = f.ini(r + "keys.ini")
                val glyph = keys.entries.firstOrNull { (s, d) -> LETTER.matches(s) && d["FG_IMAGES"] != null }?.let { (_, d) ->
                    image(keys[d.getValue("FG_IMAGES").split(',')[0].trim()]?.get("NORMAL"))
                }
                if (glyph != null) {
                    key.color = glyph.pixels.glyphColor()
                    look.notes += SkinNote.LETTER_IMAGES
                }
            }
            // Sogou's keys fill their cell: the gap between them is the face's transparent margin, cut off as it is
            // written (SkinTheme), so the inset, half of it on each side
            (key.fill as? SkinFill.Image)?.pixels?.let { px ->
                val box = px.opaqueBounds() ?: return@let
                val scale = look.width / BaiduSkin.DP_ACROSS
                fun dp(v: Int) = (v / 2.0 / scale).roundToInt().coerceIn(0, MAX_INSET_DP)
                look.inset = dp(box.top + px.height - box.bottom) to dp(box.left + px.width - box.right)
            }
        }

        private fun sounds(look: SkinLook) {
            val ini = f.ini("default/sound_android.ini")
            for ((key, sections) in SOUNDS) {
                val name = sections.firstNotNullOfOrNull { ini[it]?.get("sound")?.trim()?.takeIf { s -> s.isNotEmpty() } }
                val at = name?.let { SOUND_EXTENSIONS.firstNotNullOfOrNull { ext -> file(name + ext) ?: f.first("default/$name$ext") } } ?: continue
                val got = SkinSound.convert(at, f.bytes(at)!!)
                if (got != null) look.sounds[key] = got else look.notes += SkinNote.SOUND
            }
        }
    }

    private data class Kind(val selector: String, val defaults: List<String>, val owners: List<String>, val colors: Pair<Int?, Int?>)

    companion object {
        private val SKIN_AT = Regex("(\\d+)/(?:[^/]+/)?phoneSkin\\.ini")
        private val PERIOD = Regex("([^/]+)/phoneSkin\\.ini")
        private val PREVIEW = Regex("preview[^/]*\\.(png|jpe?g)$", RegexOption.IGNORE_CASE)
        private val IMAGE = Regex(".+\\.(png|webp|jpe?g)", RegexOption.IGNORE_CASE)
        private val SINGLE = Regex("Key_([A-Z])(?:_PY)?")
        private val LETTER = Regex("Key_[A-Z]")
        private const val BAND = 4
        private const val NOON = 12
        private const val BAR_RATIO = 0.115
        private const val MIN_WIDTH = 240
        private const val MAX_WIDTH = 4096
        private const val DEFAULT_WIDTH = 1080
        private const val MAX_INSET_DP = 24
        private val SOUND_EXTENSIONS = listOf("", ".wav", ".ogg", ".mp3", ".aiff")
        private val SOUNDS = listOf(
            SoundKey.LETTER to listOf("Key"), SoundKey.SPACE to listOf("Key_Space"),
            SoundKey.ENTER to listOf("Key_Enter_Qwerty", "Key_Enter"), SoundKey.BACKSPACE to listOf("Key_Backspace_Qwerty", "Key_Backspace"),
        )

        /** Whether [names] are a Sogou phone skin's; a computer skin's (`skin.ini`, no `phoneTheme.ini`) is said so. */
        fun accepts(names: Set<String>): Boolean {
            if ("phoneTheme.ini" in names) return true
            if (names.any { it.substringAfterLast('/').equals("skin.ini", ignoreCase = true) }) throw SkinError(SkinError.Reason.SOGOU_PC)
            return false
        }
    }
}
