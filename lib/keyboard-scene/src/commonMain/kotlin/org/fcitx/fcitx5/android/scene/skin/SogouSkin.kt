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
 * band) and `default/` (sounds, fonts). Key faces go by class; where the keys are is `<width>/port/26.ini` and `9.ini`
 * where a skin has them. The day's period (the one holding noon) is the light version, the night's (midnight) the dark.
 *
 * Checked on eight real skins from Sogou's own shop (dev/design/060-skin-formats.md §1): a picture under the keys
 * may be `[Keyboard] BG_IMAGE` (a file's name or a section's), a band of six numbers as well as four, a key face
 * `transparent.png` (the picture shows through; only the pressed face drawn), a key's `ICON=transparent.png` (its
 * symbol drawn in its face: none of the app's drawn over it), letter pictures in `keys.ini` under suffixed names
 * (`Key_G_L`). The preview is `phoneTheme.ini`'s `preview_square`.
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
            fun holds(period: String, hour: Int): Boolean {
                val ends = hours("END", period)
                return hours("START", period).withIndex().any { (i, start) -> start <= hour && hour <= (ends.getOrNull(i) ?: -1) }
            }
            val day = order.firstOrNull { holds(it, NOON) } ?: order[0]
            roots[Variant.LIGHT] = "$res/$day/"
            (order.firstOrNull { it != day && holds(it, 0) } ?: order.firstOrNull { it != day })?.let { roots[Variant.DARK] = "$res/$it/" }
        }
        val general = f.ini("phoneTheme.ini")["General"].orEmpty()
        name = general["skin_name"].orEmpty()
        author = general["skin_author"].orEmpty()
        val square = general["preview_square"]?.trim()?.takeIf { it.isNotEmpty() && it in f }
        preview = (square ?: f.names.sorted().firstOrNull { PREVIEW.containsMatchIn(it) })?.let(f::bytes)
    }

    override val variants: Set<Variant> get() = roots.keys

    // icons, letter pictures and places are the first version's only: the converter reads no other's
    override fun look(variant: Variant): SkinLook = Reader(roots.getValue(variant)).look(variant == Variant.LIGHT).also {
        if (periods > 2) it.notes += SkinNote.MORE_PERIODS
        if (variant == Variant.LIGHT) it.layouts += SogouBoards.read(f)
    }

    private inner class Reader(private val r: String) {
        private val skin = f.ini(r + "phoneSkin.ini")

        private fun file(name: String?): String? {
            val n = name?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return f.first(r + n, "$res/$n", "default/$n", "default/res/$n", n)
        }

        // 'file.png,left,right,top,bottom' (the stretchable band as pixel ranges), or six numbers ('left,right,mode,
        // top,bottom,mode'): the image and its insets
        private fun image(value: String?): SkinFill.Image? {
            val parts = value?.split(',')?.map { it.trim() } ?: return null
            if (!IMAGE.matches(parts[0]) || transparent(parts[0])) return null
            val px = f.pixels(file(parts[0])) ?: return null
            val nums = parts.drop(1)
            val band = when (nums.size) {
                BAND -> SkinText.ints(nums.joinToString(","), BAND)
                BAND_MODES -> SkinText.ints(listOf(nums[0], nums[1], nums[3], nums[4]).joinToString(","), BAND)
                else -> null
            }
            val insets = band?.takeIf { it[0] < it[1] && it[2] < it[3] }?.let {
                Pixels.Box(it[0], it[2], maxOf(0, px.width - it[1]), maxOf(0, px.height - it[3]))
            }
            return SkinFill.Image(px, insets)
        }

        // a key face: an image, or none drawn (transparent.png, or an image all transparent: the picture under it shows)
        private fun face(value: String?): SkinFill? {
            val name = value?.split(',')?.firstOrNull()?.trim().orEmpty()
            if (transparent(name)) return CLEAR
            val img = image(value) ?: return null
            return if (img.pixels.opaqueBounds() == null) CLEAR else img
        }

        private fun transparent(name: String) = name.equals("transparent.png", ignoreCase = true)

        // a key whose symbol is in its face: its ICON transparent.png
        private fun baked(sections: List<String>) = sections.any { skin[it]?.get("ICON")?.trim()?.let(::transparent) == true }

        private fun bgOf(section: String) = skin[section]?.get("BG_IMAGES")?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        private fun pair(section: String?): Pair<SkinFill?, SkinFill?> {
            val s = skin[section ?: return null to null].orEmpty()
            return face(s["NORMAL"]) to face(s["PRESSED"])
        }

        private fun textStyle(vararg names: String): Pair<Int?, Int?> {
            val s = names.firstNotNullOfOrNull { skin[it]?.takeIf { s -> "TEXT_COLOR" in s } } ?: return null to null
            return SkinText.color(s["TEXT_COLOR"]) to SkinText.color(s["TEXT_COLOR_PRESSED"])
        }

        fun look(main: Boolean): SkinLook {
            val look = SkinLook(res.toIntOrNull()?.takeIf { it in MIN_WIDTH..MAX_WIDTH } ?: DEFAULT_WIDTH)
            board(look)
            keys(look)
            look.candidate = textStyle("TextStyle_Candidate").first
            look.first = textStyle("TextStyle_Candidate_Highlight").first
            look.preedit = textStyle("TextStyle_Composing").first
            look.hintText = textStyle("TextStyle_Popup_Qwerty", "TextStyle_Popup").first
            font(look)
            if (main) {
                icons(look)
                letters(look)
            }
            sounds(look)
            if (f.names.any { it.endsWith("anim.ini") || it.lowercase().endsWith(".webp") }) look.notes += SkinNote.ANIMATION
            if (skin.values.any { "TEXT_SIZE" in it }) look.notes += SkinNote.TEXT_SIZE
            if (f.imageFailed) look.notes += SkinNote.IMAGES
            return look
        }

        // a BG_IMAGE: a file's name, or a section's whose NORMAL names it
        private fun ref(value: String?): SkinFill.Image? {
            val v = value?.trim().orEmpty()
            return if (v in skin) image(skin[v]?.get("NORMAL")) else image(v)
        }

        // one picture under the bar and the keys (RootView), the bar's own image laid over its top; or the keyboard's
        // own picture ([Keyboard], under the keys only) with the bar's above it
        private fun board(look: SkinLook) {
            val root = skin["RootView"].orEmpty()
            val picture = if (root["BG_STYLE"]?.trim() == "2") null else listOf("RootView", "WALLPAPER").firstNotNullOfOrNull { ref(skin[it]?.get("BG_IMAGE")) }
            val keyboard = if (picture == null) ref(skin["Keyboard"]?.get("BG_IMAGE")) else null
            if (picture == null) look.boardColor = SkinText.color(root["BG_COLOR"]) ?: SkinText.color(skin["Keyboard"]?.get("BG_COLOR"))
            val bar = ref(skin["CandidateView"]?.get("BG_IMAGE"))
            when {
                picture != null -> under(look, picture, bar)
                keyboard != null -> below(look, keyboard, bar)
                bar != null -> look.barColor = bar.pixels.average(keepAlpha = false)
            }
            look.barColor = look.barColor ?: look.boardColor
        }

        // the bar's height to the keyboard's width: its picture's to the skin's, never past the width itself
        private fun barRatio(bar: SkinFill.Image?, look: SkinLook) =
            if (bar != null) (bar.pixels.height.toDouble() / look.width).coerceAtMost(1.0) else BAR_RATIO

        private fun barHeight(bar: SkinFill.Image?, width: Int, look: SkinLook) = maxOf(1, (width * barRatio(bar, look)).roundToInt())

        private fun under(look: SkinLook, picture: SkinFill.Image, bar: SkinFill.Image?) {
            val board = Pixels(picture.pixels.width, picture.pixels.height).also { it.paste(picture.pixels, 0, 0) }
            look.boardColor = board.average(keepAlpha = false)
            val h = minOf(barHeight(bar, board.width, look), board.height)
            if (bar != null) {
                board.over(bar.pixels.nine(bar.insets, board.width, h), 0, 0)
                look.barColor = board.crop(0, 0, board.width, h)?.average(keepAlpha = false)
            }
            look.board = board
        }

        private fun below(look: SkinLook, keyboard: SkinFill.Image, bar: SkinFill.Image?) {
            val kb = SkinPatch.fitted(keyboard.pixels.nine(keyboard.insets, keyboard.pixels.width, keyboard.pixels.height), barRatio(bar, look))
            val colour = kb.average(keepAlpha = false) ?: GREY
            val h = barHeight(bar, kb.width, look)
            val board = Pixels.filled(kb.width, h + kb.height, colour)
            board.over(kb, 0, h)
            if (bar != null) board.over(bar.pixels.nine(bar.insets, kb.width, h), 0, 0)
            look.boardColor = colour
            look.barColor = board.crop(0, 0, kb.width, h)?.average(keepAlpha = false)
            look.board = board
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
                look.keys[kind.selector] = SkinKey(nm, hl, color, kind.colors.second, icons = kind.selector != "key", drawn = baked(kind.owners))
            }
            // one letter styled on its own (Key_Q, Key_H_PY): by its name on the 26 keys
            for (section in skin.keys) {
                val m = SINGLE.matchEntire(section) ?: continue
                val (nm, hl) = pair(bgOf(section))
                if (nm != null) look.keys["qwerty/" + m.groupValues[1].lowercase()] = SkinKey(nm, hl, null, null, icons = false)
            }
            var key = look.keys["key"] ?: throw SkinError(SkinError.Reason.NO_KEYBOARD)
            // letters drawn as pictures (keys.ini FG_IMAGES): their colour for the keys' text where none is given
            if (key.color == null) {
                val keys = f.ini(r + "keys.ini")
                val glyph = keys.entries.firstOrNull { (s, d) -> LETTER.matches(s) && d["FG_IMAGES"] != null }?.let { (_, d) ->
                    image(keys[d.getValue("FG_IMAGES").split(',')[0].trim()]?.get("NORMAL"))
                }
                if (glyph != null) {
                    key = SkinKey(key.fill, key.pressed, glyph.pixels.glyphColor(), key.pressedColor, key.icons, key.drawn)
                    look.keys["key"] = key
                }
            }
            // Sogou's keys fill their cell: the gap between them is the face's transparent margin, cut off as it is
            // written (SkinTheme), so the inset, half of it on each side
            (key.fill as? SkinFill.Image)?.pixels?.let { px ->
                val box = px.opaqueBounds() ?: return@let
                val scale = look.width / SkinTheme.DP_ACROSS
                fun dp(v: Int) = (v / 2.0 / scale).roundToInt().coerceIn(0, MAX_INSET_DP)
                look.inset = dp(box.top + px.height - box.bottom) to dp(box.left + px.width - box.right)
            }
        }

        // the keys' font: the one the letters' style names (the phone's own: none), else one another style names or
        // the skin's own by name; one at most
        private fun font(look: SkinLook) {
            val letters = listOf("TextStyle_Qwerty", "TextStyle").firstNotNullOfOrNull { skin[it]?.get("TYPEFACE")?.trim()?.takeIf(String::isNotEmpty) }
            if (letters != null && (!isFont(letters) || systemFont(letters))) return
            val named = letters ?: skin.values.firstNotNullOfOrNull { s -> s["TYPEFACE"]?.trim()?.takeIf { isFont(it) && !systemFont(it) } }
            val own = f.names.filter { n ->
                isFont(n) && !systemFont(n) && n.substringBeforeLast('/', "").substringAfterLast('/').lowercase() in FONT_FOLDERS
            }.sorted()
            val at = named?.let { n -> file(n) ?: own.firstOrNull { it.substringAfterLast('/').equals(n, ignoreCase = true) } } ?: own.firstOrNull()
            val bytes = at?.let(f::bytes)
            when {
                at == null || bytes == null -> if (named != null) look.notes += SkinNote.FONT
                bytes.size > SOGOU_FONT_BYTES -> look.notes += SkinNote.FONT
                else -> look.font = at.substringAfterLast('/').lowercase() to bytes
            }
        }

        // an ICON= picture, not transparent.png (the symbol in the face); a name not read is said
        private fun icon(value: String?, look: SkinLook): Pixels? {
            val name = value?.trim()?.takeIf { it.isNotEmpty() && !transparent(it) } ?: return null
            return f.pixels(file(name)).also { if (it == null) look.notes += SkinNote.KEY_ICONS }
        }

        private fun list(section: String, key: String) = skin[section]?.get(key).orEmpty().split(',').map { it.trim() }

        // the function keys' ICON and ICON_PRESSED by the app's icon name; Shift's ICONS at rest, once, locked
        private fun icons(look: SkinLook) {
            for ((name, owners) in ICONS) {
                val s = owners.firstOrNull { skin[it]?.get("ICON") != null } ?: continue
                icon(list(s, "ICON")[0], look)?.let { look.icon(name, it, icon(list(s, "ICON_PRESSED")[0], look)) }
            }
            val shift = SHIFT_OWNERS.firstOrNull { skin[it]?.get("ICON") != null || skin[it]?.get("ICONS") != null } ?: return
            val states = list(shift, "ICONS").takeIf { it.any(String::isNotEmpty) } ?: list(shift, "ICON")
            val pressed = list(shift, "ICON_PRESSED")
            for ((i, name) in SHIFT_ICONS.withIndex()) {
                val normal = icon(states.getOrNull(i), look) ?: continue
                look.icon(name, normal, icon(pressed.getOrNull(i), look))
            }
        }

        // a picture's file named in a value ('file.png,band…'), not transparent.png
        private fun picture(value: String?): Pixels? =
            value?.split(',')?.get(0)?.trim()?.takeIf { IMAGE.matches(it) && !transparent(it) }?.let { f.pixels(file(it)) }

        // a letter's picture on the 26 keys (keys.ini FG_IMAGES, or its own ICON=) as a sticker; any other said
        private fun letters(look: SkinLook) {
            val keys = f.ini(r + "keys.ini")
            fun fg(section: String, state: String) = keys[section]?.get("FG_IMAGES")?.split(',')?.get(0)?.trim()?.let { keys[it]?.get(state) }
            for (c in 'A'..'Z') {
                val names = listOf("Key_$c", "Key_${c}_PY", "Key_${c}_EN")
                val drawn = names.firstOrNull { keys[it]?.get("FG_IMAGES") != null }
                val got = if (drawn != null) {
                    picture(fg(drawn, "NORMAL"))?.let { SkinIcon(it, picture(fg(drawn, "PRESSED"))) }
                } else {
                    names.firstNotNullOfOrNull { n -> picture(skin[n]?.get("ICON"))?.let { SkinIcon(it, picture(skin[n]?.get("ICON_PRESSED"))) } }
                }
                if (got != null) look.letterImage(c, got.normal, got.pressed)
            }
            val drawn = keys.filterValues { "FG_IMAGES" in it }.keys
            val left = drawn.any { s -> LETTER.matchEntire(s)?.groupValues?.get(1)?.single()?.lowercaseChar()?.let { it !in look.letterImages } == true }
            if (left || drawn.any(NINE_LETTERS::matches)) look.notes += SkinNote.LETTER_IMAGES
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
        // Key_G, and as real skins name them, Key_G_L, Key_G_Land_Left
        private val LETTER = Regex("Key_([A-Z])(_.+)?")
        private val NINE_LETTERS = Regex("Key_(ABC|DEF|GHI|JKL|MNO|PQRS|TUV|WXYZ)(_.+)?")
        private val ICONS = listOf(
            "backspace" to listOf("Key_Backspace_Qwerty", "Key_Backspace"),
            "enter" to listOf("Key_Enter_Qwerty", "Key_Enter"),
            "language" to listOf("Key_SwitchToEN_Qwerty", "Key_SwitchToEN"),
            "number-pad" to listOf("Key_Symbols_Qwerty", "Key_Digits"),
            "emoji" to listOf("Key_Emotion"),
        )
        private val SHIFT_OWNERS = listOf("Key_Shift_Qwerty", "Key_Shift")
        private val SHIFT_ICONS = listOf("shift", "shift-once", "shift-locked")
        private val FONT_FOLDERS = setOf("res_typeface", "fonts", "default")
        private val SYSTEM_FONTS = listOf("roboto", "droidsans", "notosans", "arial")
        // half of SkinLimits.MAX_FONT_BYTES: a keyboard's font, not the CJK fallback some Sogou skins copy in
        private const val SOGOU_FONT_BYTES = 8 * 1024 * 1024

        private fun isFont(name: String) = name.lowercase().let { it.endsWith(".ttf") || it.endsWith(".otf") }

        // a font the phone has anyway, which a skin names or carries a copy of
        private fun systemFont(name: String) = name.substringAfterLast('/').lowercase().let { n -> SYSTEM_FONTS.any(n::startsWith) }
        private const val BAND = 4
        private const val BAND_MODES = 6
        private val CLEAR = SkinFill.Color(0)
        private const val NOON = 12
        private const val BAR_RATIO = 0.115
        private const val GREY = 0xFFD1D4DB.toInt()
        private const val MIN_WIDTH = 240
        private const val MAX_WIDTH = 4096
        private const val DEFAULT_WIDTH = 1080
        private const val MAX_INSET_DP = 24
        private val SOUND_EXTENSIONS = listOf("", ".wav", ".ogg", ".mp3", ".aiff")
        private val SOUNDS = listOf(
            SoundKey.LETTER to listOf("Key"), SoundKey.SPACE to listOf("Key_Space"),
            SoundKey.ENTER to listOf("Key_Enter_Qwerty", "Key_Enter"), SoundKey.BACKSPACE to listOf("Key_Backspace_Qwerty", "Key_Backspace"),
        )

        /** Whether [names] are a Sogou phone skin's. */
        fun accepts(names: Set<String>): Boolean = "phoneTheme.ini" in names

        /** Whether [names], which no reader takes, are a Sogou computer skin's (`skin.ini`): said so, not just unknown. */
        fun isComputerSkin(names: Set<String>): Boolean = names.any { it.substringAfterLast('/').equals("skin.ini", ignoreCase = true) }
    }
}

/**
 * Where a Sogou skin's keys are: `[Keyboard] W,H` (to the screen's width), `ROWS`, `H_GAP_QWERTY`, `PADDINGS`; a row's
 * `V_OFFSET` (to the keyboard's height; none: below the row before), `H_OFFSET` (to its width) and `KEYS`, placed one after
 * another unless a key has its own `X`; a key's `W` and `H` in its section or `[Key]`, one of them 0 a hole. Boards in
 * the keyboard's width; a file not holding together is left out.
 */
internal object SogouBoards {
    private val FILES = listOf("26.ini" to "qwerty", "9.ini" to "nine")
    private val LETTER = Regex("Key_([A-Z])(?:_PY|_EN)?")
    private val NINE = listOf("Key_PunctPY", "Key_ABC", "Key_DEF", "Key_GHI", "Key_JKL", "Key_MNO", "Key_PQRS", "Key_TUV", "Key_WXYZ")
    private val ROLES = listOf(
        "Key_Apostrophe_Shift" to "shift", "Key_Shift" to "shift", "Key_Backspace" to "backspace", "Key_SymbolTable" to "symbols",
        "Key_Symbols_Qwerty" to "numbers", "Key_Digits" to "numbers", "Key_UDSymbol1" to "comma", "Key_UDSymbol2" to "period",
        "Key_Space" to "space", "Key_SwitchToEN" to "lang", "Key_Enter" to "enter", "Key_ClearInput" to "reset",
    )
    private const val MAX_ROWS = 8
    private const val MAX_KEYS = 20
    private const val MAX_SIZE = 2.0
    private const val PADDINGS = 4
    private const val PREFERRED = 1080

    /** Each board from the first width's folder (1080, then the widest) whose `port/` file reads. */
    fun read(f: SkinFiles): List<SkinBoard> = FILES.mapNotNull { (file, id) ->
        val widths = f.names.mapNotNull { n -> n.removeSuffix("/port/$file").takeIf { it != n }?.toIntOrNull() }
        widths.sortedWith(compareBy<Int> { it != PREFERRED }.thenByDescending { it }).firstNotNullOfOrNull { board(f.ini("$it/port/$file"), id) }
    }

    // the skin's name for a key as the app's: a letter, a nine key's digit, a role or id; else its own name (not matched)
    fun action(section: String): SkinAction {
        LETTER.matchEntire(section)?.let { return SkinAction.Letter(it.groupValues[1].single().lowercaseChar()) }
        NINE.indexOf(section).takeIf { it >= 0 }?.let { return SkinAction.Letter('1' + it) }
        if (section == "Key_Edits") return SkinAction.Letter('0')
        return SkinAction.Role(ROLES.firstOrNull { section.startsWith(it.first) }?.second ?: section)
    }

    // the shop's skins have no padding, so how it adds to the offsets is a guess: an offset given stands for it
    private fun board(ini: Ini, id: String): SkinBoard? {
        val kb = ini["Keyboard"] ?: return null
        val w = size(kb["W"])?.takeIf { it > 0 } ?: return null
        val tall = (size(kb["H"]) ?: return null) / w
        val gap = size(kb["H_GAP_QWERTY"] ?: kb["H_GAP"]) ?: 0.0
        val pad = kb["PADDINGS"]?.split(',')?.map { size(it) ?: return null }?.takeIf { it.size == PADDINGS } ?: List(PADDINGS) { 0.0 }
        val rows = names(kb["ROWS"]).takeIf { it.size in 1..MAX_ROWS } ?: return null
        var below = pad[1] * tall
        val out = rows.map { name ->
            row(ini, name, pad[0], below, tall, gap)?.also { r -> below = r.maxOfOrNull { it.y + it.h } ?: below } ?: return null
        }
        return SkinBoard(id, out, 1.0)
    }

    private fun names(v: String?) = v.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun size(v: String?) = v?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= MAX_SIZE }

    // a row's keys one after another from its offset, heights as fractions of the width; a V_OFFSET not given (or
    // negative, Sogou's own "none") puts it at [below]
    private fun row(ini: Ini, name: String, left: Double, below: Double, tall: Double, gap: Double): List<SkinKeyBox>? {
        val row = ini[name] ?: return null
        val keys = names(row["KEYS"]).takeIf { it.size in 1..MAX_KEYS } ?: return null
        val v = row["V_OFFSET"]?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }
        val y = if (v == null) below else (v.takeIf { it.isFinite() && it <= MAX_SIZE } ?: return null) * tall
        var x = size(row["H_OFFSET"]) ?: left
        return keys.mapNotNull { key ->
            val kw = size(ini[key]?.get("W") ?: ini["Key"]?.get("W")) ?: return null
            val kh = size(ini[key]?.get("H") ?: ini["Key"]?.get("H")) ?: return null
            ini[key]?.get("X")?.let { x = size(it) ?: return null }
            SkinKeyBox(x, y, kw, kh * tall, action(key)).takeIf { kw > 0 && kh > 0 }.also { x += kw + gap }
        }.takeIf { it.isNotEmpty() }
    }
}
