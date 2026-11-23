/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.Icons
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.theme.RasterIcon
import org.fcitx.fcitx5.android.scene.theme.ThemeFeatures
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Values
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A skin's looks written as a theme 4.0 pack, for the import's own check to read as any pack. A second version
 * restates what the first set that it has not, so a light style never shows in the dark; file names are its own,
 * never a name from the skin.
 */
class SkinTheme(
    private val source: SkinSource,
    private val decode: (ByteArray) -> Pixels?,
    private val fallbackName: String,
    private val layoutSources: LayoutCheck.Sources? = null,
) {
    private val files = LinkedHashMap<String, SkinOutput>()
    private var uses063 = false

    // a key's mark colours, after the shared `key mark` (as specific as `key mark`, they come later)
    private val marks = ArrayList<Pair<String, String>>()

    // written once, whichever keys have it (Baidu's styles share an atlas's tiles)
    private val written = HashMap<SkinFill.Image, String>()

    // a second version's board kept only as its dark picture, made small as it is read: two boards in memory at most
    private fun readLooks(): Pair<Map<Variant, SkinLook>, Pixels?> {
        val order = source.variants.sortedBy { it.ordinal }
        var mainBoard: Pixels? = null
        var darkPicture: Pixels? = null
        val looks = order.associateWith { v ->
            source.look(v).also {
                if (v == order.first()) {
                    mainBoard = it.board
                } else {
                    darkPicture = it.board?.let { b -> darkOf(b, mainBoard) }
                    it.board = null
                }
            }
        }
        return looks to darkPicture
    }

    // none where it is the board itself; else at most DARK_PICTURE_SIDE and no larger than the board
    private fun darkOf(dark: Pixels, board: Pixels?): Pixels? {
        if (board != null && same(dark, board)) return null
        return dark.capped(minOf(DARK_PICTURE_SIDE, board?.let { max(it.width, it.height) } ?: DARK_PICTURE_SIDE))
    }

    fun write(): SkinConversion {
        val (looks, darkBoard) = readLooks()
        val first = looks.keys.first()
        val main = looks.getValue(first)
        val notes = LinkedHashMap<SkinNote, List<String>>()
        looks.values.forEach { l -> l.notes.forEach { (n, args) -> notes.getOrPut(n) { args } } }
        val layouts = layouts(main, notes)
        val variants = variants(looks, main)
        val body = ArrayList<String>()
        body += colorLines(looks, first)
        val board = main.board?.capped(MAX_PICTURE_SIDE)
        board?.let {
            add(SkinOutput.Image(BOARD, it, MAX_PICTURE_BYTES, shrink = true))
            body += pictureLines(BOARD, dark = false)
        }
        val preview = source.preview?.let(decode)?.let {
            add(SkinOutput.Image(PREVIEW, it.capped(MAX_PREVIEW_SIDE), MAX_PREVIEW_BYTES, shrink = true))
            PREVIEW
        }
        main.font?.let { (file, bytes) -> add(SkinOutput.Bytes(fontName(file), bytes)) }
        val sounds = soundLines(main)
        val styled = ArrayList<String>()
        for ((variant, look) in looks) {
            styled += styles(look, variant, scene = if (variant == first) "" else "@dark ", firstKeys = main.keys.keys, picture = variant == first && board != null)
        }
        // made after the faces: the board, preview, font, sounds and faces are written whatever is left, the rest only in it
        val pack = Budget(layouts.values.map { it.text.encodeToByteArray().size.toLong() }, files.values)
        // the dark picture after the faces, fonts and sounds the budget holds first
        val dark = if (looks.size == 2) (main.darkPicture?.let { darkOf(it, main.board) } ?: darkBoard) else null
        when (darkLines(dark, board, pack)) {
            true -> {
                body += pictureLines(DARK_BOARD_FILE, dark = true)
                styled += listOf("""  <style select="@dark keyboard" fill="picture"/>""", """  <style select="@dark bar" fill="picture"/>""")
            }
            false -> notes += SkinNote.DARK_PICTURE
            null -> Unit
        }
        main.font?.let { body += """  <font name="skin" src="${escape(fontName(it.first))}"/>""" }
        body += styled
        val iconLines = icons(main, pack, notes)
        body += stickers(main, pack, notes)
        body += iconLines
        body += sounds
        val name = themeName(source.name).ifEmpty { themeName(baseName(fallbackName)) }.ifEmpty { "skin" }
        val own = if (uses063) ThemeFeatures.MIN_APP_063 else MIN_APP
        val withLayouts = layouts.values.map { it.minApp }.fold(own) { v, l -> if (ThemeReader.newer(l, v)) l else v }
        val themeXml = xml(name, variants, preview, body, own)
        return SkinConversion(
            name, themeXml, files.values.toList(), notes,
            layouts.mapValues { it.value.text }, if (withLayouts == own) themeXml else xml(name, variants, preview, body, withLayouts),
        )
    }

    // a light skin with a dark board is told as dark
    private fun variants(looks: Map<Variant, SkinLook>, main: SkinLook): String {
        val first = looks.keys.first()
        val dark = looks.size == 1 && first == Variant.LIGHT && lum(main.boardColor ?: WHITE) < DARK_BOARD
        return if (dark) "dark" else looks.keys.joinToString(" ") { it.word }
    }

    private fun colorLines(looks: Map<Variant, SkinLook>, first: Variant): List<String> {
        val palettes = looks.mapValues { palette(it.value) }
        val out = colors(palettes.getValue(first), dark = false).toMutableList()
        if (looks.size == 2) {
            val light = palettes.getValue(Variant.LIGHT)
            out += colors(palettes.getValue(Variant.DARK).filter { (k, v) -> light[k] != v }, dark = true)
        }
        return out
    }

    private fun pictureLines(file: String, dark: Boolean): List<String> = listOf(
        if (dark) """  <picture variant="dark" src="$file">""" else """  <picture src="$file">""",
        """    <cut orientation="portrait" file="$file"/>""",
        "  </picture>",
    )

    private fun soundLines(look: SkinLook): List<String> {
        if (look.sounds.isEmpty()) return emptyList()
        val out = arrayListOf("  <sounds>")
        for ((key, sound) in look.sounds) {
            val name = "sound-" + key.word + "." + sound.first.substringAfterLast('.')
            add(SkinOutput.Bytes(name, sound.second))
            out += """    <sound key="${key.word}" src="$name"/>"""
        }
        out += "  </sounds>"
        return out
    }

    // written (true), left out (false), or none to write (null)
    private fun darkLines(dark: Pixels?, board: Pixels?, pack: Budget): Boolean? = when (dark) {
        null -> null
        else -> board != null && darkPicture(dark, pack)
    }

    // written where the pack has room for it
    private fun darkPicture(dark: Pixels, pack: Budget): Boolean {
        val out = SkinOutput.Image(DARK_BOARD_FILE, dark, MAX_PICTURE_BYTES, shrink = true)
        if (!pack.take(out)) return false
        add(out)
        uses063 = true
        return true
    }

    private fun xml(name: String, variants: String, preview: String?, body: List<String>, minApp: String): String = buildString {
        val author = clean(source.author).take(MAX_META)
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<theme xmlns="${ThemeReader.NAMESPACE}" name="${escape(name)}" variants="$variants" version="4.0">""")
        append("  <meta")
        if (author.isNotBlank()) append(""" author="${escape(author)}"""")
        append(""" version="1" min-app="$minApp"""")
        if (preview != null) append(""" preview="$preview"""")
        appendLine("/>")
        body.forEach(::appendLine)
        appendLine("</theme>")
    }

    // the boards that take, by id; the rest told of
    private fun layouts(main: SkinLook, notes: MutableMap<SkinNote, List<String>>): Map<String, SkinLayoutWriter.Written> {
        val sources = layoutSources ?: return emptyMap()
        val boards = main.layouts.filter { it.id in SkinLayoutWriter.IDS }.distinctBy { it.id }
        if (boards.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, SkinLayoutWriter.Written>()
        val failed = ArrayList<String>()
        for (b in boards) SkinLayoutWriter.write(b, sources)?.let { out[b.id] = it } ?: failed.add(b.id)
        if (out.isEmpty()) {
            notes += SkinNote.LAYOUT
        } else {
            notes.remove(SkinNote.LAYOUT)
            if (failed.isNotEmpty()) notes[SkinNote.LAYOUT_SOME] = failed
            out.filterValues { it.dropped > 0 }.keys.toList().takeIf { it.isNotEmpty() }?.let { notes[SkinNote.LAYOUT_EXTRA] = it }
            out.flatMap { (id, w) -> w.lacks.map { (listOf(id) + it.args).joinToString("\t") } }.takeIf { it.isNotEmpty() }?.let { notes[SkinNote.LAYOUT_LACKS] = it }
        }
        return out
    }

    /** The pack's limits (ime-core's ThemeAssetLimits) less what is written regardless: theme.xml, the layouts and [written]. */
    private class Budget(layouts: List<Long>, written: Collection<SkinOutput>) {
        private var entries = MAX_PACK_ENTRIES - 1 - layouts.size - written.size
        private var bytes = MAX_PACK_BYTES - MAX_THEME_XML_BYTES - layouts.sum() - written.sumOf(::bound)

        /** Whether [out] fits in what is left, and if so counted. */
        fun take(out: SkinOutput): Boolean {
            val b = bound(out)
            if (entries < 1 || b > bytes) return false
            entries--
            bytes -= b
            return true
        }

        // one halved till within its bytes is at most those
        private fun bound(o: SkinOutput): Long = when (o) {
            is SkinOutput.Bytes -> o.bytes.size.toLong()
            is SkinOutput.Image -> pngBound(o.pixels.width, o.pixels.height).let { if (o.shrink) minOf(it, o.maxBytes) else it }
        }
    }

    private class Planned(val name: String, val normal: Pixels, val pressedImage: Pixels?, val pressed: SkinIcons.Pressed) {
        var tinted = true
    }

    /**
     * [look]'s icons as `<icon>`s and their keys' styles, written after the faces: each at rest while the pack has room, then
     * each pressed one. One in a colour is drawn as a mask in its colour, so a pressed one in another is only that colour.
     */
    private fun icons(look: SkinLook, pack: Budget, notes: MutableMap<SkinNote, List<String>>): List<String> {
        val plans = look.icons.map { (name, icon) ->
            Planned(name, icon.normal, icon.pressed, if (SkinIcons.keyOf(name) == null) SkinIcons.Pressed.Same else SkinIcons.pressed(icon))
        }
        val rest = allot(plans, MAX_ICONS, pack) { SkinOutput.Image("icon-${it.name}.png", it.normal, RasterIcon.MAX_BYTES.toLong(), shrink = true) }
        val ownPressed = rest.filter(::ownPressed)
        val own = allot(ownPressed, MAX_ICONS - rest.size, pack) {
            SkinOutput.Image("icon-${it.name}-p.png", it.pressedImage!!, RasterIcon.MAX_BYTES.toLong(), shrink = true)
        }.map { it.name }.toSet()
        val left = plans.filter { it !in rest } + ownPressed.filter { it.name !in own }
        if (left.any { SkinIcons.keyOf(it.name) != null }) notes += SkinNote.KEY_ICONS
        if (left.any { SkinIcons.keyOf(it.name) == null }) notes += SkinNote.TOOLBAR_ICONS
        if (rest.isEmpty()) return emptyList()
        uses063 = true
        // Return's icons share one key's colours: ones in different colours are each drawn in their own
        val returns = rest.filter { it.name in Icons.RETURN }
        val returnsTinted = returns.map { colours(it, it.name in own) }.distinct().size <= 1
        val styles = ArrayList<String>()
        val pressedStyles = ArrayList<String>()
        val elements = ArrayList<String>()
        for (p in rest) {
            val sel = SkinIcons.keyOf(p.name)
            p.tinted = p.name !in Icons.RETURN || returnsTinted
            val mono = sel?.takeIf { p.tinted }?.let { SkinIcons.mono(p.normal) }
            elements += iconElement(p.name, "icon-${p.name}.png", mono != null)
            if (sel != null) iconStyles(p, sel, mono, p.name in own, styles, pressedStyles, elements)
        }
        // Return's icons of one colour each set it
        return (styles + pressedStyles).distinct() + elements
    }

    // an icon's colour at rest and pressed, as its key's styles would set them
    private fun colours(p: Planned, own: Boolean): Pair<Int?, Int?> {
        val mono = SkinIcons.mono(p.normal)
        val pressed = if (own) SkinIcons.mono(p.pressedImage!!) else (p.pressed as? SkinIcons.Pressed.Color)?.argb ?: mono
        return mono to pressed
    }

    // Return's pressed one by its action, and "previous" has none of its own (a `content` would show on every action)
    private fun ownPressed(p: Planned): Boolean =
        p.pressed == SkinIcons.Pressed.Image && !p.name.startsWith("shift-") && (p.name !in Icons.RETURN || returnAction(p.name) != null)

    private fun iconElement(name: String, file: String, tint: Boolean) = """  <icon name="$name" src="$file"""" + (if (tint) """ tint="true"""" else "") + "/>"

    private fun iconStyles(
        p: Planned,
        sel: String,
        mono: Int?,
        own: Boolean,
        styles: MutableList<String>,
        pressedStyles: MutableList<String>,
        elements: MutableList<String>,
    ) {
        val state = when (p.name) {
            "shift-once" -> ":shifted"
            "shift-locked" -> ":locked"
            else -> ""
        }
        mono?.let { styles += style("$sel$state", "icon-color" to hex(it)) }
        if (state.isNotEmpty()) return
        if (own) {
            val pm = if (p.tinted) SkinIcons.mono(p.pressedImage!!) else null
            elements += iconElement("${p.name}-pressed", "icon-${p.name}-p.png", pm != null)
            pm?.let { pressedStyles += style("$sel:pressed", "icon-color" to hex(it)) }
            val shown = returnAction(p.name)?.let { Values.RETURN_ICON + it } ?: "content"
            pressedStyles += style("$sel:pressed icon", shown to "icon(${p.name}-pressed)")
            // `:pressed` selects a shifted or locked ⇧ too, which would flash the unshifted picture
            if (p.name == "shift") SHIFTED.forEach { (state, icon) -> pressedStyles += style("$sel:$state:pressed icon", "content" to "icon($icon)") }
        } else if (mono != null) {
            pressedStyles += style("$sel:pressed", "icon-color" to hex((p.pressed as? SkinIcons.Pressed.Color)?.argb ?: mono))
        }
    }

    // 26 keys' letters drawn as pictures: each at rest while the pack has room, then each pressed one
    private fun stickers(look: SkinLook, pack: Budget, notes: MutableMap<SkinNote, List<String>>): List<String> {
        val letters = look.letterImages.map { (c, icon) -> c to icon }
        val rest = allot(letters, RasterIcon.MAX_STICKERS, pack) { (l, icon) -> sticker("st-$l.png", icon.normal) }
        val ownPressed = rest.filter { (_, icon) -> icon.pressed != null && !same(icon.pressed, icon.normal) }
        val pressed = allot(ownPressed, RasterIcon.MAX_STICKERS - rest.size, pack) { (l, icon) -> sticker("st-$l-p.png", icon.pressed!!) }.map { it.first }.toSet()
        if (rest.size < letters.size || pressed.size < ownPressed.size) notes += SkinNote.LETTER_IMAGES
        if (rest.isEmpty()) return emptyList()
        uses063 = true
        return rest.flatMap { (l, _) ->
            listOfNotNull(
                style("qwerty/$l", "sticker" to "st-$l.png"),
                style("qwerty/$l label", "hidden" to "true"),
                if (l in pressed) style("qwerty/$l:pressed", "sticker" to "st-$l-p.png") else null,
            )
        }
    }

    private fun sticker(name: String, p: Pixels) = SkinOutput.Image(name, p, RasterIcon.MAX_STICKER_BYTES.toLong(), shrink = true)

    // each written, in order, while the pack has room and fewer than [max] are; the ones written
    private fun <T> allot(items: List<T>, max: Int, pack: Budget, out: (T) -> SkinOutput): List<T> {
        val taken = ArrayList<T>()
        for (t in items) {
            val o = out(t)
            if (taken.size < max && pack.take(o)) {
                add(o)
                taken += t
            }
        }
        return taken
    }

    private fun returnAction(icon: String): String? = (if (icon == "enter") "newline" else icon).takeIf { it in Values.RETURN_ACTIONS }

    private fun style(sel: String, vararg attrs: Pair<String, String>): String =
        """  <style select="${escape(sel)}" """ + attrs.joinToString(" ") { (k, v) -> """$k="${escape(v)}"""" } + "/>"

    private fun fontName(file: String) = "skin-" + file.filter { it in 'a'..'z' || it in '0'..'9' || it == '.' }.takeLast(MAX_FILE_PART)

    private fun add(out: SkinOutput) {
        files[out.name] = out
    }

    private fun colors(c: Map<String, String>, dark: Boolean): List<String> =
        listOf(if (dark) """  <colors variant="dark">""" else "  <colors>") +
            c.map { (k, v) -> """    <color name="$k" value="$v"/>""" } + "  </colors>"

    // the app's own widgets' colours (panels, the clipboard, menus) from the skin's
    private fun palette(look: SkinLook): Map<String, String> {
        val key = look.keys["key"]
        val fn = look.keys["key.function"] ?: key
        val en = look.keys["key.enter"] ?: fn
        val sp = look.keys["key.space"] ?: key
        val board = look.boardColor?.let(::opaque) ?: DEFAULT_BOARD
        val dark = lum(board) < DARK_TEXT
        val text = key?.color ?: if (dark) WHITE else NEAR_BLACK
        val cand = look.candidate ?: text
        val first = look.first ?: cand
        fun face(k: SkinKey?, default: Int): Int = solid(fillColor(k?.fill) ?: default, board)
        val c = linkedMapOf(
            "background" to hex(board), "bar" to hex(look.barColor?.let(::opaque) ?: board), "keyboard" to hex(board),
            "key" to hex(face(key, WHITE)), "keyText" to hex(text),
            "candidateText" to hex(cand), "candidateLabel" to "alpha(${hex(cand)}, 70%)", "candidateComment" to "alpha(${hex(cand)}, 70%)",
            "altKey" to hex(face(fn, ALT_KEY)), "altKeyText" to hex(fn?.color ?: text),
            "accentKey" to hex(face(en, ALT_KEY)), "accentKeyText" to hex(en?.color ?: fn?.color ?: text),
            "keyPressHighlight" to if (dark) "#33ffffff" else "#1f000000", "keyShadow" to "#33000000",
            "popupBackground" to hex(solid(fillColor(look.hintFill) ?: face(key, WHITE), board)), "popupText" to hex(look.hintText ?: text),
            "spaceBar" to hex(face(sp, WHITE)), "divider" to "alpha(${hex(text)}, 12%)", "clipboardEntry" to hex(face(key, WHITE)),
            "genericActiveBackground" to hex(first), "genericActiveForeground" to if (lum(first) < ACTIVE_DARK) "#ffffff" else "#000000",
            "keyBorder" to "transparent", "keyHint" to "alpha(${hex(text)}, 60%)", "toolbarIcon" to hex(cand),
            "first" to hex(first),
        )
        return c
    }

    // one version's styles; [scene] '' for the first, '@dark ' for the second, which restates each of [firstKeys]
    private fun styles(look: SkinLook, variant: Variant, scene: String, firstKeys: Set<String>, picture: Boolean): List<String> {
        val out = ArrayList<String>()
        val px = look.width / DP_ACROSS
        val prefix = variant.word.take(1)
        fun st(selectors: String, vararg attrs: Pair<String, String?>) {
            val set = attrs.filter { it.second != null }
            if (set.isEmpty()) return
            val sel = selectors.split(',').joinToString(", ") { scene + it.trim() }
            out += """  <style select="${escape(sel)}" """ + set.joinToString(" ") { (k, v) -> """$k="${escape(v!!)}"""" } + "/>"
        }
        if (picture) {
            st("keyboard", "fill" to "picture")
            st("bar", "fill" to "picture")
        } else {
            st("keyboard", "fill" to "@keyboard")
            st("bar", "fill" to "@bar")
        }
        st("panel", "fill" to "@keyboard")
        st(
            "key", "radius" to (look.radius?.let { "${dp(it)}dp" } ?: "0"), "border" to "none", "shadow" to "none", "color" to "@keyText", "icon-color" to "@keyText", "press" to "tint",
            "inset" to look.inset?.let { (v, h) -> "${v}dp ${h}dp" },
        )
        look.inset?.let { (v, h) -> st("@compact key", "inset" to "${Gap.compact(v)}dp ${h}dp") }
        // the app's press tint, under any kind's pressed face of its own (more specific, or later)
        st("key:pressed", "overlay" to "@keyPressHighlight")
        val again = scene.isNotEmpty()
        for ((sel, k) in look.keys) writeKey(sel, k, prefix, px, again, ::st)
        // the first version's single keys and kinds this one has not: as this one's letters, not the first's
        if (again) {
            val base = look.keys["key"]
            for (sel in firstKeys - look.keys.keys) {
                if (base != null) {
                    writeKey(sel, base, prefix, px, again = true, st = ::st)
                } else {
                    st(sel, "fill" to "@key", "color" to "@keyText", "icon-color" to "@keyText")
                    st("$sel:pressed", "fill" to "@key", "overlay" to "@keyPressHighlight", "color" to "@keyText")
                }
            }
        }
        st("key mark, key shortcut", "color" to "@keyHint", "icon-color" to "@keyHint")
        for ((sel, c) in marks) st(sel, "color" to c)
        marks.clear()
        st("candidate", "color" to "@candidateText")
        st("candidate:first", "color" to "@first")
        st("candidate label", "color" to "@candidateLabel")
        st("candidate comment", "color" to "@candidateComment")
        st("candidates-divider", "fill" to "transparent")
        st("candidates-panel", "fill" to "@keyboard")
        st("preedit", "color" to (look.preedit?.let(::hex) ?: "@keyText"), "cursor-color" to "@first")
        st("toolbar", "icon-color" to "@toolbarIcon")
        st("toolbar:selected", "icon-color" to "@first")
        val hint = look.hintFill
        if (hint is SkinFill.Image) {
            st("preview", "fill" to fill(hint, prefix, "preview", "n", px), "color" to "@popupText")
        } else {
            st("preview", "fill" to "@popupBackground", "radius" to "6dp", "shadow" to SHADOW, "color" to "@popupText")
        }
        st("popup", "fill" to "@popupBackground", "radius" to "6dp", "shadow" to SHADOW, "color" to "@popupText")
        st("popup:selected", "fill" to "@genericActiveBackground", "color" to "@genericActiveForeground")
        st("menu", "fill" to "@popupBackground", "radius" to "6dp", "color" to "@popupText", "icon-color" to "@popupText")
        st("menu:selected", "fill" to "@genericActiveBackground", "color" to "@genericActiveForeground", "icon-color" to "@genericActiveForeground")
        st("clipboard", "fill" to "@clipboardEntry", "color" to "@keyText")
        st("picker categories", "fill" to "@altKey", "color" to "@altKeyText")
        st("editing", "fill" to "@altKey")
        st("voice mic", "fill" to "@first", "icon-color" to "@genericActiveForeground")
        if (look.font != null && scene.isEmpty()) st("key", "font" to "skin")
        return out
    }

    /**
     * A kind of key's ([sel]) face and colours, by [st]: its pressed face with no tint over it, or, none, the tint
     * (another kind's pressed face, more general or the first version's, never shows through). [again]: a second
     * version's, which sets its pressed colour too, whatever the first version's was.
     */
    private fun writeKey(sel: String, k: SkinKey, prefix: String, px: Double, again: Boolean, st: (String, Array<out Pair<String, String?>>) -> Unit) {
        val fill = k.fill?.let { fill(it, prefix, sel, "n", px) }
        // a face with its symbol in it: none of the app's on top
        val color = if (k.drawn) "transparent" else k.color?.let(::hex)
        st(
            sel,
            arrayOf(
                "fill" to fill, "color" to color, "icon-color" to color?.takeIf { k.icons || k.drawn },
                "scale" to k.scale?.coerceIn(Values.MIN_SCALE.toInt(), Values.MAX_SCALE.toInt())?.let { "$it%" },
                "weight" to k.weight?.takeIf { it in WEIGHTS }?.toString(),
            ),
        )
        val pressed = k.pressed?.takeIf { !SkinPatch.same(it, k.fill) }?.let { fill(it, prefix, sel, "p", px) }
        val pressedColor = k.pressedColor?.takeIf { it != k.color && !k.drawn }?.let(::hex)
        val pressedText = pressedColor ?: color?.takeIf { again }
        st(
            sel.split(',').joinToString(",") { "$it:pressed" },
            arrayOf(
                // none of its own: its own face still (a more general kind's pressed face never shows through)
                "fill" to (pressed ?: fill),
                "overlay" to if (pressed != null) "transparent" else "@keyPressHighlight",
                "color" to pressedText,
                "icon-color" to (k.pressedIconColor?.let(::hex) ?: pressedText?.takeIf { k.icons || k.drawn }),
                "press-scale" to k.pressScale?.coerceIn(Values.MIN_PRESS_SCALE.toInt(), Values.MAX_PRESS_SCALE.toInt())?.let { "$it%" },
                "transition" to k.transitionMs?.coerceIn(0, Values.MAX_TRANSITION_MS)?.let { "${it}ms ease-out" },
                "press-sink" to k.pressSink?.takeIf { it.isFinite() }?.coerceIn(0.0, Values.MAX_PRESS_SINK_DP)?.let { "${dp(it)}dp" },
            ),
        )
        fun mark(state: String) = sel.split(',').joinToString(",") { "${it.trim()}$state mark" }
        k.markColor?.let { marks += mark("") to hex(it) }
        k.pressedMarkColor?.let { marks += mark(":pressed") to hex(it) }
    }

    // a face's fill value: a colour, or an image of its own (its transparent margins cut, at most MAX_KEY_SIDE)
    private fun fill(f: SkinFill, prefix: String, sel: String, which: String, px: Double): String {
        if (f is SkinFill.Color) return hex(f.argb)
        val img = f as SkinFill.Image
        return written.getOrPut(img) { image(img, prefix, sel, which, px) }
    }

    private fun image(img: SkinFill.Image, prefix: String, sel: String, which: String, px: Double): String {
        var p = img.pixels
        var ins = img.insets ?: Pixels.Box(0, 0, 0, 0)
        val box = p.opaqueBounds()
        if (box != null && (box.width != p.width || box.height != p.height)) {
            ins = Pixels.Box(
                max(0, ins.left - box.left), max(0, ins.top - box.top),
                max(0, ins.right - (p.width - box.right)), max(0, ins.bottom - (p.height - box.bottom)),
            )
            p = p.crop(box.left, box.top, box.width, box.height)!!
        }
        val side = max(p.width, p.height)
        var scale = if (side > MAX_KEY_SIDE) MAX_KEY_SIDE.toDouble() / side else 1.0
        // drawn at a whole density (`image(…, Nx)` takes 1–8): the picture scaled to the nearest, so it draws at the
        // skin's size (3.45 px a dp is not drawn as 3), down a step where up would pass MAX_KEY_SIDE
        val d = px * scale
        if (d >= 1) {
            var whole = d.roundToInt().coerceAtMost(MAX_DENSITY)
            if (side * scale * whole / d > MAX_KEY_SIDE) whole = floor(d).toInt().coerceIn(1, MAX_DENSITY)
            scale *= whole / d
        }
        if (scale != 1.0) p = p.resized(max(1, (p.width * scale).roundToInt()), max(1, (p.height * scale).roundToInt()))
        val name = (prefix + "-" + sel + "-" + which).lowercase().replace(NOT_NAME, "-").trim('-') + ".png"
        add(SkinOutput.Image(name, p, MAX_KEY_BYTES, shrink = false))
        var l = (ins.left * scale).roundToInt()
        var r = (ins.right * scale).roundToInt()
        var t = (ins.top * scale).roundToInt()
        var b = (ins.bottom * scale).roundToInt()
        if (l + r >= p.width) { l = 0; r = 0 }
        if (t + b >= p.height) { t = 0; b = 0 }
        val n = (px * scale).roundToInt().coerceIn(1, MAX_DENSITY)
        return "image($name, $l $t $r $b, ${n}x)"
    }

    // a length in dp as written: whole, or to two places
    private fun dp(v: Double): String {
        val r = (v * 100).roundToInt()
        return if (r % 100 == 0) (r / 100).toString() else (r / 100.0).toString()
    }

    private fun fillColor(f: SkinFill?): Int? = when (f) {
        is SkinFill.Color -> f.argb
        is SkinFill.Image -> f.pixels.average()
        null -> null
    }

    companion object {
        const val MIN_APP = "0.6.0"
        const val BOARD = "board.png"
        const val DARK_BOARD_FILE = "board-dark.png"

        /** A dark picture's longer side at most (a second board in memory with the first). */
        const val DARK_PICTURE_SIDE = 2048
        private val WEIGHTS = setOf(300, 400, 700)

        /** ime-core's ThemeAssetLimits: MAX_ENTRIES, MAX_TOTAL_BYTES, MAX_THEME_XML_BYTES (held to them by the app's test). */
        const val MAX_PACK_ENTRIES = 300
        const val MAX_PACK_BYTES = 64L * 1024 * 1024
        const val MAX_THEME_XML_BYTES = 1024L * 1024

        /** ThemeReader's, SVG and raster alike. */
        const val MAX_ICONS = 200
        const val MAX_STICKER_SIDE = 256
        private val SHIFTED = listOf("shifted" to "shift-once", "locked" to "shift-locked")
        private const val PNG_OVERHEAD_PER = 1000
        private const val PNG_HEADERS = 1024L
        const val PREVIEW = "preview.png"
        const val MAX_KEY_SIDE = 512
        const val MAX_KEY_BYTES = 2L * 1024 * 1024
        const val MAX_PICTURE_SIDE = 4096
        const val MAX_PICTURE_BYTES = 16L * 1024 * 1024
        const val MAX_PREVIEW_SIDE = 720
        const val MAX_PREVIEW_BYTES = 500L * 1024

        /** A skin's width across the keyboard is this many dp: its pixels per dp, whatever the format. */
        const val DP_ACROSS = 360.0
        private const val MAX_DENSITY = 8
        private const val MAX_META = 64
        private const val MAX_FILE_PART = 40
        private const val SHADOW = "0 2dp 6dp #33000000"
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val NEAR_BLACK = 0xFF111111.toInt()
        private const val DEFAULT_BOARD = 0xFFD1D4DB.toInt()
        private const val ALT_KEY = 0xFFBDC2CC.toInt()
        private const val DARK_BOARD = 0.1
        private const val DARK_TEXT = 0.2
        private const val ACTIVE_DARK = 0.4
        private const val SOLID_ALPHA = 0x99
        private const val GAMMA = 2.2
        private val NOT_NAME = Regex("[^a-z0-9]+")
        private val SPACES = Regex("\\s+")
        private val VARIANT_SUFFIX = Regex("#(light|dark)$", RegexOption.IGNORE_CASE)

        /** A file's name with no folder and no suffix (`a/b.c.ssf` → `b.c`), as ime-core's ThemeFileName.base. */
        fun baseName(fileName: String): String {
            val name = fileName.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }

        private fun same(a: Pixels, b: Pixels) = a.width == b.width && a.height == b.height && a.argb.contentEquals(b.argb)

        /** [s] with no control character and one space for each run of white space. */
        fun clean(s: String): String = s.filter { it >= ' ' && it != '\u007F' }.replace(SPACES, " ").trim()

        /** A PNG's bytes at most: its pixels stored as they are (filter bytes too), with deflate's and the chunks' overhead. */
        internal fun pngBound(width: Int, height: Int): Long {
            val raw = 4L * width * height + height
            return raw + raw / PNG_OVERHEAD_PER + PNG_HEADERS
        }

        /**
         * A skin's name as its theme's: [clean], no `/` nor `\` (a theme's name names its folder), not led by a dot,
         * not ending as the app's names of a version do (`#dark`), at most [ThemeReader.MAX_NAME].
         */
        fun themeName(s: String): String {
            var n = clean(s).replace('/', '-').replace('\\', '-').trimStart('.', ' ')
            while (VARIANT_SUFFIX.containsMatchIn(n)) n = n.replace(VARIANT_SUFFIX, "").trimEnd()
            return n.take(ThemeReader.MAX_NAME).trim()
        }

        fun hex(argb: Int): String {
            val a = argb ushr 24
            val rgb = (argb and 0xFFFFFF).toString(16).padStart(6, '0')
            return if (a == 0xFF) "#$rgb" else "#" + a.toString(16).padStart(2, '0') + rgb
        }

        private fun opaque(argb: Int) = argb or (0xFF shl 24)

        // a face mostly transparent (art drawn on the board): the app's own panels need a solid colour
        private fun solid(argb: Int, board: Int): Int = if (argb ushr 24 == 0xFF) argb else if (argb ushr 24 > SOLID_ALPHA) opaque(argb) else board

        fun lum(argb: Int): Double {
            fun ch(shift: Int) = ((argb shr shift and 0xFF) / 255.0).pow(GAMMA)
            return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
        }

        private fun escape(s: String) = buildString {
            for (c in s) {
                when (c) {
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    else -> append(c)
                }
            }
        }

        private fun Pixels.capped(side: Int): Pixels {
            val m = max(width, height)
            if (m <= side) return this
            val s = side.toDouble() / m
            return resized(max(1, (width * s).roundToInt()), max(1, (height * s).roundToInt()))
        }
    }
}

/** A file of a converted skin's theme, named by the converter; the platform writes it (an image as PNG). */
sealed interface SkinOutput {
    val name: String

    /** [pixels] as PNG within [maxBytes]; where [shrink], halved in size till it is (a picture stretched anyway), else none. */
    class Image(override val name: String, val pixels: Pixels, val maxBytes: Long, val shrink: Boolean) : SkinOutput

    class Bytes(override val name: String, val bytes: ByteArray) : SkinOutput
}

/**
 * A skin converted: the theme's [name], its `theme.xml` ([themeXml]), its [files] and what did not carry over ([notes],
 * in the order seen, each with its arguments). [layouts] are `layouts/<id>.xml` by id ([SkinLayoutWriter.IDS]), not in
 * [files]: the user picks whether they come; with them the theme is [themeXmlWithLayouts], its `min-app` theirs if later.
 */
class SkinConversion(
    val name: String,
    val themeXml: String,
    val files: List<SkinOutput>,
    val notes: Map<SkinNote, List<String>>,
    val layouts: Map<String, String> = emptyMap(),
    val themeXmlWithLayouts: String = themeXml,
)
