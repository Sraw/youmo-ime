/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import kotlin.math.roundToInt

/**
 * A Trime (同文) theme: a `*.trime.yaml`, alone or in a ZIP with its `backgrounds/`, `fonts/` and the files it
 * `__include`s or `__patch`es ([RimeCompose] puts them together first). Read: `name`, `author`, `style` (the colour
 * scheme in use, key corners and gaps, the key font, the pictures' folder), one scheme of `preset_color_schemes` with
 * its `dark_scheme` (or, a dark one, its `light_scheme`) and the first letter keyboard `style/keyboards` lists. A colour
 * is `0xRRGGBB`/`0xAARRGGBB`, another colour's name, or a picture's file (a `.9.png` one uncompiled: its marks are its
 * stretch band); one not given falls back as Trime's own `fallback_colors` table says ([FALLBACK]). Not read: sizes
 * and sounds. Written from the format's documentation (the comments of Trime's own trime.yaml), none of Trime's code.
 */
class TrimeSkin(private val f: SkinFiles) : SkinSource {
    private val yaml: Map<*, *>
    private val style: Map<*, *>
    private val schemes: Map<*, *>
    private val roots = LinkedHashMap<Variant, Map<*, *>>()
    private val folder: String
    private val skipped: Int
    private val more: Boolean
    private val board: SkinBoard?
    override val name: String
    override val author: String
    override val preview: ByteArray? = null

    // the YAML read to put the theme file being tried together, held to MAX_COMPOSED_BYTES
    private var composedBytes = 0L

    // what one theme file tried includes is parsed once for every other tried after it
    private val parsed = HashMap<String, Any?>()

    // the shallowest file of each name, made before init reads includes by it
    private val byFileName = f.names.groupBy { it.substringAfterLast('/') }.mapValues { (_, ns) -> ns.minBy { n -> n.count { it == '/' } } }

    init {
        val (main, composed) = chosen()
        yaml = composed.root as Map<*, *>
        skipped = composed.skipped.size
        schemes = yaml["preset_color_schemes"] as Map<*, *>
        style = yaml["style"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val chosen = (style["color_scheme"] as? String)?.takeIf { schemes[it] is Map<*, *> }
            ?: "default".takeIf { schemes["default"] is Map<*, *> }
            ?: schemes.entries.firstOrNull { it.value is Map<*, *> }?.key as? String
            ?: throw SkinError(SkinError.Reason.NO_KEYBOARD, main)
        val first = schemes[chosen] as Map<*, *>
        val dark = (first["dark_scheme"] as? String)?.takeIf { it != chosen }?.let { schemes[it] as? Map<*, *> }
        val light = (first["light_scheme"] as? String)?.takeIf { it != chosen }?.let { schemes[it] as? Map<*, *> }
        when {
            dark != null -> {
                roots[Variant.LIGHT] = first
                roots[Variant.DARK] = dark
            }
            light != null -> {
                roots[Variant.LIGHT] = light
                roots[Variant.DARK] = first
            }
            else -> roots[Variant.LIGHT] = first
        }
        val own = (yaml["name"] as? String)?.takeIf { it.isNotBlank() } ?: (first["name"] as? String).orEmpty()
        val schemeName = (first["name"] as? String)?.takeIf { it.isNotBlank() } ?: chosen
        // a file of many schemes (a theme's repository): the one taken named
        more = schemes.values.count { it is Map<*, *> } > roots.size
        name = if (more && schemeName != own && own.isNotEmpty()) "$own · $schemeName" else own.ifEmpty { schemeName }
        author = (yaml["author"] as? String).orEmpty()
        folder = (style["background_folder"] as? String).orEmpty()
        board = keyboard()
    }

    // the largest theme file there that, put together, has colour schemes
    private fun chosen(): Pair<String, RimeCompose.Composed> {
        // a `trime.yaml` beside themes is Trime's own settings copied along
        val tops = candidates(f.names).sortedWith(compareBy<String> { it.equals("trime.yaml", ignoreCase = true) }.thenByDescending { f.bytes(it)?.size ?: 0 })
            .take(MAX_CANDIDATES)
        val main = tops.firstOrNull() ?: throw SkinError(SkinError.Reason.NO_KEYBOARD)
        var broken = false
        var mainError: SkinError? = null
        for (name in tops) {
            val text = text(name)
            val tree = SkinYaml.parse(text)
            if (tree !is Map<*, *>) {
                broken = broken || name == main
                continue
            }
            val id = idOf(name)
            val composed = try {
                if (DIRECTIVES.any { it in text } || fileOf("$id.custom") != null) {
                    // each its own limits: a larger file without colours tried first leaves the theme after it all of them
                    composedBytes = f.bytes(name)!!.size.toLong()
                    RimeCompose(::included).compose(tree, id)
                } else {
                    RimeCompose.Composed(tree, emptyList())
                }
            } catch (e: SkinError) {
                if (name == main) mainError = e
                null
            }
            if (composed != null && (composed.root as? Map<*, *>)?.get("preset_color_schemes") is Map<*, *>) return name to composed
        }
        throw mainError ?: SkinError(if (broken) SkinError.Reason.BROKEN else SkinError.Reason.NO_KEYBOARD, main)
    }

    // the first letter keyboard `style/keyboards` lists, a Chinese one before an English one: as the app's 26 keys
    private fun keyboard(): SkinBoard? {
        val presets = yaml["preset_keyboards"] as? Map<*, *> ?: return null
        val listed = (style["keyboards"] as? List<*>)?.filterIsInstance<String>() ?: listOf(".default")
        val boards = listed.take(MAX_KEYBOARDS).mapNotNull { n -> (presets[n.removePrefix(".")] as? Map<*, *>)?.let { it to boxes(it, presets) } }
            .filter { (_, keys) -> keys.mapNotNull { (it.action as? SkinAction.Letter)?.char }.toSet().containsAll(LETTERS) }
        val (_, keys) = boards.firstOrNull { (kb, _) -> !truthy(kb["ascii_mode"]) } ?: boards.firstOrNull() ?: return null
        return SkinBoard("qwerty", listOf(keys), FULL)
    }

    // a keyboard's keys placed as Trime does: left to right in percents of its width, a new row where one would not fit
    private fun boxes(kb: Map<*, *>, presets: Map<*, *>): List<SkinKeyBox> {
        val imported = (kb["import_preset"] as? String)?.let { presets[it] as? Map<*, *> }
        val keys = kb["keys"] as? List<*> ?: imported?.get("keys") as? List<*> ?: return emptyList()
        val actions = yaml["preset_keys"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val across = size(kb["width"]) ?: size(imported?.get("width")) ?: DEFAULT_KEY_WIDTH
        val high = size(kb["height"]) ?: size(imported?.get("height")) ?: size(style["key_height"]) ?: 1.0
        val out = ArrayList<SkinKeyBox>()
        var x = 0.0
        var y = 0.0
        var row = 0.0
        for (k in keys.take(MAX_KEYS)) {
            val m = k as? Map<*, *> ?: continue
            val action = action(m, actions)
            val w = (size(m["width"]) ?: across).let { if (it == 0.0 && action != SkinAction.None) across else it }.coerceAtMost(FULL)
            val h = size(m["height"]) ?: high
            if (x + w > FULL + ROUNDING && x > 0) {
                x = 0.0
                y += row
                row = 0.0
            }
            out += SkinKeyBox(x, y, w, h, action)
            x += w
            row = maxOf(row, h)
        }
        return out
    }

    // a key's `click`: a letter, a key Trime names (or `preset_keys` sends or switches to) the app has, or another key
    private fun action(key: Map<*, *>, actions: Map<*, *>): SkinAction {
        val click = (key["click"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return SkinAction.None
        if (click.length == 1 && click[0].lowercaseChar() in LETTERS) return SkinAction.Letter(click[0].lowercaseChar())
        val preset = actions[click] as? Map<*, *>
        val names = listOfNotNull(click, preset?.get("send") as? String, (preset?.get("select") as? String)?.let { "Keyboard_$it" })
        return names.firstNotNullOfOrNull(::role)?.let { SkinAction.Role(it) } ?: SkinAction.Role(click)
    }

    private fun text(name: String): String {
        if (f.bytes(name)!!.size > SkinLimits.MAX_TEXT_BYTES) throw SkinError(SkinError.Reason.UNPACKED_TOO_BIG, name)
        return f.text(name)
    }

    // a file another refers to by its id: `base` is the shallowest `base.yaml` there
    private fun included(id: String): Any? {
        val name = fileOf(id) ?: return null
        val size = f.bytes(name)!!.size
        composedBytes += size
        if (size > SkinLimits.MAX_TEXT_BYTES || composedBytes > MAX_COMPOSED_BYTES) throw SkinError(SkinError.Reason.BROKEN, name)
        return parsed.getOrPut(name) { SkinYaml.parse(f.text(name)) }
    }

    private fun fileOf(id: String): String? {
        val want = if (YAML.containsMatchIn(id)) id else "$id.yaml"
        if ('/' !in want) return byFileName[want]
        return f.names.filter { it == want || it.endsWith("/$want") }.minByOrNull { n -> n.count { it == '/' } }
    }

    override val variants: Set<Variant> get() = roots.keys

    override fun look(variant: Variant): SkinLook = Look(roots.getValue(variant)).look()

    private inner class Look(private val sc: Map<*, *>) {
        private val look = SkinLook(ASSUMED_WIDTH)

        // a name's value: the scheme's, else what it falls back to; a value naming another colour followed
        private fun raw(name: String, depth: Int = 0): String? {
            if (depth > MAX_HOPS) return null
            val v = sc[name] as? String
            return when {
                v == null -> FALLBACK[name]?.let { raw(it, depth + 1) }
                !IMAGE.containsMatchIn(v) && sc[v] is String -> raw(v, depth + 1)
                else -> v
            }
        }

        private fun color(name: String): Int? = raw(name)?.let(SkinText::color)

        private fun fill(name: String): SkinFill? {
            val v = raw(name) ?: return null
            if (!IMAGE.containsMatchIn(v)) return SkinText.color(v)?.let { SkinFill.Color(it) }
            val at = f.first("backgrounds/$folder/$v", "backgrounds/$v", v)
            val px = f.pixels(at)
            if (px == null) {
                look.notes += SkinNote.IMAGES
                return null
            }
            return if (at!!.lowercase().endsWith(".9.png")) SkinPatch.nine(px) else SkinFill.Image(px, null)
        }

        fun look(): SkinLook {
            val board = fill("keyboard_back_color")
            val bar = fill(if ("candidate_background" in sc) "candidate_background" else "back_color")
            SkinPatch.board(look, board, bar)
            keys()
            look.candidate = color("candidate_text_color")
            look.first = color("hilited_candidate_text_color")
            look.preedit = color("text_color")
            look.hintFill = fill("preview_back_color")
            look.hintText = color("preview_text_color")
            val h = (style["horizontal_gap"] as? String)?.toDoubleOrNull()
            val v = (style["vertical_gap"] as? String)?.toDoubleOrNull()
            if (h != null && v != null) look.inset = (v / 2).roundToInt().coerceIn(0, MAX_INSET) to (h / 2).roundToInt().coerceIn(0, MAX_INSET)
            (style["round_corner"] as? String)?.toDoubleOrNull()?.let { look.radius = it.coerceIn(0.0, MAX_RADIUS) }
            (style["key_font"] as? String)?.let { font -> f.first("fonts/$font") }?.let { at ->
                // past the limit: not taken, said by TEXT_SIZE below as every Trime theme's sizes are
                f.bytes(at)?.takeIf { it.size <= SkinLimits.MAX_FONT_BYTES }?.let { look.font = at.substringAfterLast('/').lowercase() to it }
            }
            this@TrimeSkin.board?.let { look.layouts.add(it) }
            look.notes += SkinNote.LAYOUT
            look.notes += SkinNote.TEXT_SIZE
            if (skipped > 0) look.notes[SkinNote.INCLUDES] = listOf(skipped.toString())
            if (more) look.notes += SkinNote.MORE_THEMES
            if (f.imageFailed) look.notes += SkinNote.IMAGES
            return look
        }

        private fun keys() {
            val key = SkinKey(fill("key_back_color"), fill("hilited_key_back_color"), color("key_text_color"), color("hilited_key_text_color"), icons = false)
            val fn = SkinKey(
                fill("off_key_back_color"), fill("hilited_off_key_back_color"), color("off_key_text_color"),
                color("hilited_off_key_text_color"), icons = true,
            )
            // a theme's own names for a kind of key (Trime lets its keyboards name any): the ones popular themes use
            val main = own("main", key)
            look.keys["key"] = main
            if (!same(fn, main)) look.keys["key.function"] = fn
            if ("space_back_color" in sc) look.keys["key.space"] = own("space", main)
            if ("enter_back_color" in sc) look.keys["key.enter"] = own("enter", fn)
        }

        private fun own(prefix: String, base: SkinKey): SkinKey {
            if ("${prefix}_back_color" !in sc) return base
            return SkinKey(
                fill("${prefix}_back_color") ?: base.fill,
                fill("hilited_${prefix}_back_color") ?: base.pressed,
                color("${prefix}_text_color") ?: base.color,
                color("hilited_${prefix}_text_color") ?: base.pressedColor,
                icons = base.icons,
            )
        }

        private fun same(a: SkinKey, b: SkinKey) =
            SkinPatch.same(a.fill, b.fill) && SkinPatch.same(a.pressed, b.pressed) && a.color == b.color && a.pressedColor == b.pressedColor
    }

    companion object {
        /** The colours Trime falls back to where a scheme leaves one out (trime.yaml's `fallback_colors`): name → name. */
        val FALLBACK = mapOf(
            "candidate_text_color" to "text_color", "comment_text_color" to "candidate_text_color", "border_color" to "back_color",
            "hilited_text_color" to "text_color", "hilited_back_color" to "back_color",
            "hilited_candidate_text_color" to "hilited_text_color", "hilited_candidate_back_color" to "hilited_back_color",
            "hilited_key_back_color" to "hilited_candidate_back_color", "hilited_key_text_color" to "hilited_candidate_text_color",
            "hilited_off_key_back_color" to "hilited_key_back_color", "hilited_off_key_text_color" to "hilited_key_text_color",
            "key_back_color" to "back_color", "key_text_color" to "candidate_text_color", "key_symbol_color" to "comment_text_color",
            "off_key_back_color" to "key_back_color", "off_key_text_color" to "key_text_color",
            "preview_back_color" to "key_back_color", "preview_text_color" to "key_text_color",
            "root_background" to "back_color", "candidate_background" to "back_color", "keyboard_back_color" to "border_color",
        )
        private val IMAGE = Regex("\\.(png|jpe?g|webp)$", RegexOption.IGNORE_CASE)

        // Trime's sizes are dp; its pictures taken as drawn for 3x screens
        private const val ASSUMED_WIDTH = 1080
        private const val MAX_HOPS = 12
        private const val MAX_INSET = 24
        private const val MAX_RADIUS = 32.0

        private val LETTERS = ('a'..'z').toSet()
        private const val FULL = 100.0
        private const val ROUNDING = 0.01
        private const val DEFAULT_KEY_WIDTH = 10.0
        private const val MAX_KEYBOARDS = 64
        private const val MAX_CANDIDATES = 16
        private const val MAX_KEYS = 256
        private const val MAX_KEY_SIZE = 1000.0

        private val ROLES = mapOf(
            "BackSpace" to "backspace", "Shift_L" to "shift", "Shift_R" to "shift", "Caps_Lock" to "shift",
            "Return" to "enter", "KP_Enter" to "enter", "space" to "space", "Mode_switch" to "lang",
            "comma" to "comma", "," to "comma", "，" to "comma", "period" to "period", "." to "period", "。" to "period",
        )

        // Trime's own key names, and a keyboard switched to by what it is for
        private fun role(name: String): String? = ROLES[name] ?: name.takeIf { it.startsWith("Keyboard_") }?.lowercase()?.let { k ->
            when {
                "num" in k -> "numbers"
                "sym" in k -> "symbols"
                "emoji" in k -> "emoji"
                else -> null
            }
        }

        private fun size(v: Any?): Double? = (v as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.coerceAtMost(MAX_KEY_SIZE)

        private fun truthy(v: Any?) = v == "1" || v == "true"

        /**
         * The YAML one theme file is put together from, in all: a real one with all its bases is some hundred KB. Each of
         * up to [MAX_CANDIDATES] files tried has its own (and RimeCompose's node cap), so an import's worst is that many times it.
         */
        const val MAX_COMPOSED_BYTES = 4L * 1024 * 1024

        private val DIRECTIVES = listOf("__include", "__patch", "__append", "__merge")
        private val YAML = Regex("\\.ya?ml$", RegexOption.IGNORE_CASE)

        private fun idOf(name: String) = name.substringAfterLast('/').replace(YAML, "")

        /**
         * The theme's files, at the top: the `*.trime.yaml` and `trime.yaml` (a theme's repository has variants and
         * patches beside it), or the one YAML there is.
         */
        private fun candidates(names: Set<String>): List<String> =
            trimeFiles(names).ifEmpty { names.filter { '/' !in it && YAML.containsMatchIn(it) }.takeIf { it.size == 1 }.orEmpty() }

        private fun trimeFiles(names: Set<String>) =
            names.filter { '/' !in it && (it.endsWith(".trime.yaml", ignoreCase = true) || it.equals("trime.yaml", ignoreCase = true)) }

        /** Whether [names] have a Trime theme's own file at the top (not the one YAML another format's may be taken for). */
        fun isTheme(names: Set<String>): Boolean = trimeFiles(names).isNotEmpty()

        fun accepts(names: Set<String>): Boolean = candidates(names).isNotEmpty()
    }
}
