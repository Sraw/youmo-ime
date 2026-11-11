/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import kotlin.math.roundToInt

/**
 * A Trime (同文) theme (dev/design/060-skin-formats.md §4.1): a `*.trime.yaml`, alone or in a ZIP with its
 * `backgrounds/` and `fonts/`. Read: `name`, `author`, `style` (the colour scheme in use, key corners and gaps, the
 * key font, the pictures' folder) and one scheme of `preset_color_schemes` with its `dark_scheme` (or, a dark one,
 * its `light_scheme`). A colour is `0xRRGGBB`/`0xAARRGGBB`, another colour's name, or a picture's file (a `.9.png`
 * one uncompiled: its marks are its stretch band); one not given falls back as Trime's own `fallback_colors` table
 * says ([FALLBACK]). Not read: the keyboards (`preset_keyboards`: the app's layouts are its own), other files a
 * theme `__include`s or `__patch`es (noted), sizes and sounds. Written from the format's documentation (the comments
 * of Trime's own trime.yaml), none of Trime's code.
 */
class TrimeSkin(private val f: SkinFiles) : SkinSource {
    private val yaml: Map<*, *>
    private val style: Map<*, *>
    private val schemes: Map<*, *>
    private val roots = LinkedHashMap<Variant, Map<*, *>>()
    private val folder: String
    private val includes: Boolean
    private val more: Boolean
    override val name: String
    override val author: String
    override val preview: ByteArray? = null

    init {
        val main = mainFile(f.names) { f.bytes(it)?.size ?: 0 } ?: throw SkinError(SkinError.Reason.NO_KEYBOARD)
        if (f.bytes(main)!!.size > SkinLimits.MAX_TEXT_BYTES) throw SkinError(SkinError.Reason.UNPACKED_TOO_BIG, main)
        yaml = SkinYaml.parse(f.text(main)) as? Map<*, *> ?: throw SkinError(SkinError.Reason.BROKEN, main)
        schemes = yaml["preset_color_schemes"] as? Map<*, *> ?: throw SkinError(SkinError.Reason.NO_KEYBOARD, main)
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
        includes = listOf<Any?>(yaml, style, yaml["preset_color_schemes"]).any { m -> m is Map<*, *> && m.keys.any { it == "__include" || it == "__patch" } }
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
                f.bytes(at)?.takeIf { it.size <= MAX_FONT_BYTES }?.let { look.font = at.substringAfterLast('/').lowercase() to it }
            }
            look.notes += SkinNote.LAYOUT
            look.notes += SkinNote.TEXT_SIZE
            if (includes) look.notes += SkinNote.INCLUDES
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
        const val MAX_FONT_BYTES = 4 * 1024 * 1024

        /**
         * The theme's file: the largest ([size]) `*.trime.yaml` at the top (a theme's repository has small variants and
         * patches beside it), or the one YAML there is.
         */
        fun mainFile(names: Set<String>, size: (String) -> Int): String? {
            val top = names.filter { '/' !in it }
            val trime = top.filter { it.endsWith(".trime.yaml", ignoreCase = true) }
            val yaml = top.filter { it.endsWith(".yaml", ignoreCase = true) || it.endsWith(".yml", ignoreCase = true) }
            return trime.ifEmpty { yaml.takeIf { it.size == 1 }.orEmpty() }.maxByOrNull(size)
        }

        fun accepts(names: Set<String>): Boolean = mainFile(names) { 0 } != null
    }
}
