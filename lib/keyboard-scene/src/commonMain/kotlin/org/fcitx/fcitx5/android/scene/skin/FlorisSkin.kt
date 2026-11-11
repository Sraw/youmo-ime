/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import kotlin.math.roundToInt

/**
 * A FlorisBoard theme extension (`.flex`, a ZIP; dev/design/060-skin-formats.md §4.2): `extension.json` lists its
 * themes, `stylesheets/<id>.json` is each one's Snygg v2 stylesheet (selector → properties, CSS values, `@defines`
 * variables). The first light theme and the first dark one are the two versions; a theme is dark where it says
 * `isNight`, or where its window's background is dark (many leave `isNight` out, which means false). Read: the window
 * and keyboard background (colour or picture), the smartbar, keys (`key`, `:pressed`, by code: 10 enter, 32 space,
 * the negative ones function keys), corner radius, margin, candidates, the key popup, a font the extension carries.
 * Written from the stylesheet schema (Apache-2.0), none of FlorisBoard's code.
 */
class FlorisSkin(private val f: SkinFiles) : SkinSource {
    private val roots = LinkedHashMap<Variant, Map<*, *>>()
    private val more: Boolean
    override val name: String
    override val author: String
    override val preview: ByteArray? = null

    init {
        val ext = SkinJson.parse(f.text(EXTENSION)) as? Map<*, *> ?: throw SkinError(SkinError.Reason.BROKEN, EXTENSION)
        val themes = (ext["themes"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { t ->
            val sheet = (t["id"] as? String)?.let { SkinJson.parse(f.text("stylesheets/$it.json")) as? Map<*, *> }
            sheet?.let { t to it }
        }
        if (themes.isEmpty()) throw SkinError(SkinError.Reason.NO_KEYBOARD, EXTENSION)
        fun night(t: Pair<Map<*, *>, Map<*, *>>): Boolean {
            if (t.first["isNight"] == true) return true
            val sheet = Sheet(t.second)
            val bg = sheet.color("window", "background") ?: sheet.color("keyboard", "background")
            return bg != null && SkinTheme.lum(bg) < DARK
        }
        val day = themes.firstOrNull { !night(it) }
        val dark = themes.firstOrNull { night(it) }
        day?.let { roots[Variant.LIGHT] = it.second }
        dark?.let { roots[Variant.DARK] = it.second }
        more = themes.size > roots.size
        val meta = ext["meta"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val main = (day ?: dark)!!.first
        name = (main["label"] as? String)?.takeIf { it.isNotBlank() } ?: (meta["title"] as? String).orEmpty()
        val authors = (main["authors"] as? List<*>) ?: (meta["maintainers"] as? List<*>)
        author = authors.orEmpty().filterIsInstance<String>().joinToString(", ")
    }

    override val variants: Set<Variant> get() = roots.keys

    override fun look(variant: Variant): SkinLook = Look(Sheet(roots.getValue(variant))).look()

    // a stylesheet's values, its variables put in
    private class Sheet(private val sheet: Map<*, *>) {
        private val defines = sheet["@defines"] as? Map<*, *> ?: emptyMap<String, Any?>()

        val rules: Map<*, *> get() = sheet

        fun value(rule: String, prop: String): String? {
            var v = (sheet[rule] as? Map<*, *>)?.get(prop) as? String
            repeat(MAX_HOPS) {
                val name = VAR.matchEntire(v?.trim() ?: return v)?.groupValues?.get(1) ?: return v
                v = defines[name] as? String
            }
            return v
        }

        fun color(rule: String, prop: String) = SkinPatch.cssColor(value(rule, prop))
    }

    private inner class Look(private val s: Sheet) {
        private val look = SkinLook(ASSUMED_WIDTH)

        private fun picture(rule: String): SkinFill.Image? {
            val path = FLEX_URI.find(s.value(rule, "background-image").orEmpty())?.groupValues?.get(1) ?: return null
            val px = f.pixels(path)
            if (px == null) look.notes += SkinNote.IMAGES
            return px?.let { SkinFill.Image(it, null) }
        }

        fun look(): SkinLook {
            val bg = picture("window") ?: picture("keyboard")
                ?: (s.color("window", "background") ?: s.color("keyboard", "background"))?.let { SkinFill.Color(it) }
            val bar = s.color("smartbar", "background")?.let { SkinFill.Color(it) }
            SkinPatch.board(look, bg, bar)
            keys()
            (s.value("key", "shape")?.let(CORNER::find)?.groupValues?.get(1)?.toDoubleOrNull())?.let { look.radius = it.coerceIn(0.0, MAX_RADIUS) }
            look.inset = margin(s.value("key", "margin")) ?: (DEFAULT_MARGIN to DEFAULT_MARGIN)
            look.candidate = s.color("candidates-row-item", "foreground") ?: s.color("smartbar", "foreground") ?: s.color("window", "foreground")
            look.preedit = look.candidate
            look.hintFill = s.color("key-popup-box", "background")?.let { SkinFill.Color(it) }
            look.hintText = s.color("key-popup-box", "foreground")
            font()
            look.notes += SkinNote.LAYOUT
            look.notes += SkinNote.PANELS
            if (more) look.notes += SkinNote.MORE_THEMES
            return look
        }

        private fun keys() {
            look.keys["key"] = SkinKey(
                s.color("key", "background")?.let { SkinFill.Color(it) }, s.color("key:pressed", "background")?.let { SkinFill.Color(it) },
                s.color("key", "foreground"), s.color("key:pressed", "foreground"), icons = false,
            )
            val base = look.keys.getValue("key")
            for ((kind, k) in byCode()) {
                if (k.any { it != null }) {
                    look.keys[kind] = SkinKey(
                        k[0]?.let { SkinFill.Color(it) } ?: base.fill, k[1]?.let { SkinFill.Color(it) } ?: base.pressed,
                        k[2] ?: base.color, k[3] ?: base.pressedColor, icons = true,
                    )
                }
            }
        }

        // by key code, the selectors that are only codes (not the shift state's and others' variants): a kind's
        // background, pressed background, text, pressed text
        private fun byCode(): Map<String, Array<Int?>> {
            val found = LinkedHashMap<String, Array<Int?>>()
            for (sel in s.rules.keys.filterIsInstance<String>()) {
                val m = CODE_RULE.matchEntire(sel)
                val kind = m?.let { kindOf(it.groupValues[1].split(',').map(String::trim).toSet()) } ?: continue
                val k = found.getOrPut(kind) { arrayOfNulls(FIELDS) }
                val pressed = m.groupValues[2].isNotEmpty()
                s.color(sel, "background")?.let { k[if (pressed) 1 else 0] = it }
                s.color(sel, "foreground")?.let { k[if (pressed) 3 else 2] = it }
            }
            return found
        }

        private fun kindOf(codes: Set<String>): String? = when {
            codes == setOf("10") -> "key.enter"
            codes == setOf("32") -> "key.space"
            codes.all { it in FUNCTION_CODES } -> "key.function"
            else -> null
        }

        // `4dp` or `4dp 2dp` (CSS order: up and down, then across)
        private fun margin(v: String?): Pair<Int, Int>? {
            val parts = v?.trim()?.split(Regex("\\s+"))?.map { DP.matchEntire(it)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null } ?: return null
            if (parts.isEmpty()) return null
            val vertical = parts[0].roundToInt().coerceIn(0, MAX_INSET)
            val horizontal = (parts.getOrNull(1) ?: parts[0]).roundToInt().coerceIn(0, MAX_INSET)
            return vertical to horizontal
        }

        private fun font() {
            val family = s.value("key", "font-family") ?: s.value("window", "font-family") ?: return
            val name = BACKTICKS.matchEntire(family.trim())?.groupValues?.get(1) ?: return
            val src = (s.rules["@font `$name`"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val path = FLEX_URI.find(src?.get("src") as? String ?: return)?.groupValues?.get(1) ?: return
            val bytes = f.bytes(path) ?: return
            if (bytes.size > SkinLimits.MAX_FONT_BYTES) {
                look.notes += SkinNote.TEXT_SIZE
                return
            }
            look.font = path.substringAfterLast('/').lowercase() to bytes
        }
    }

    companion object {
        private const val EXTENSION = "extension.json"
        private val VAR = Regex("var\\((--[\\w-]+)\\)")
        private val FLEX_URI = Regex("uri\\(`flex:/([^`]+)`\\)")
        private val CORNER = Regex("rounded-corner\\(\\s*([\\d.]+)dp")
        private val DP = Regex("([\\d.]+)dp")
        private val BACKTICKS = Regex("`([^`]+)`")
        private val CODE_RULE = Regex("key\\[code=([-\\d,\\s]+)\\](:pressed)?")

        // delete, shift, the switches to other boards and the like
        private val FUNCTION_CODES = setOf("-7", "-11", "-201", "-202", "-203", "-204", "-205", "-206", "-212", "-213", "-221", "-301")
        private const val FIELDS = 4
        private const val MAX_HOPS = 8
        private const val MAX_INSET = 24
        private const val MAX_RADIUS = 32.0
        private const val DEFAULT_MARGIN = 2
        private const val DARK = 0.2
        private const val ASSUMED_WIDTH = 1080

        /** Whether [names] are a FlorisBoard extension's (its `extension.json` says which kind in itself: checked as it is read). */
        fun accepts(names: Set<String>): Boolean = EXTENSION in names && names.any { it.startsWith("stylesheets/") }
    }
}
