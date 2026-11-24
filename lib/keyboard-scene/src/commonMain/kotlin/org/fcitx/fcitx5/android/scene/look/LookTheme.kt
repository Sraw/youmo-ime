/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.rules.KeyNames
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Border
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.Picture
import org.fcitx.fcitx5.android.scene.theme.Radius
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Shadow
import org.fcitx.fcitx5.android.scene.theme.Style
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.ThemeWriter
import org.fcitx.fcitx5.android.scene.theme.Value

/** A theme 3.0 look as a theme 4.0, made as the built-in themes are; files are named by their names alone (4.0 takes no path). */
object LookTheme {

    fun colors(p: Palette): List<Pair<String, Int>> = listOf(
        "background" to p.backgroundColor,
        "bar" to p.barColor,
        "keyboard" to p.keyboardColor,
        "key" to p.keyBackgroundColor,
        "keyText" to p.keyTextColor,
        "candidateText" to p.candidateTextColor,
        "candidateLabel" to p.candidateLabelColor,
        "candidateComment" to p.candidateCommentColor,
        "altKey" to p.altKeyBackgroundColor,
        "altKeyText" to p.altKeyTextColor,
        "accentKey" to p.accentKeyBackgroundColor,
        "accentKeyText" to p.accentKeyTextColor,
        "keyPressHighlight" to p.keyPressHighlightColor,
        "keyShadow" to p.keyShadowColor,
        "popupBackground" to p.popupBackgroundColor,
        "popupText" to p.popupTextColor,
        "spaceBar" to p.spaceBarColor,
        "divider" to p.dividerColor,
        "clipboardEntry" to p.clipboardEntryColor,
        "genericActiveBackground" to p.genericActiveBackgroundColor,
        "genericActiveForeground" to p.genericActiveForegroundColor,
        "keyBorder" to p.keyBorderColor,
        "keyHint" to p.keyHintColor,
        "toolbarIcon" to p.toolbarIconColor,
    )

    fun fileName(path: String): String = path.substringAfterLast('/')

    /** Numbered where another file took the name (`a~2.png`), so two images of one name in two folders stay two. */
    fun fileNames(look: ThemeLook): Map<String, String> {
        val taken = HashSet<String>()
        return look.files().associateWith { path ->
            val name = safe(fileName(path))
            val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= MAX_EXTENSION } ?: name.length
            val (stem, extension) = name.take(dot) to name.drop(dot)
            generateSequence(1) { it + 1 }.map { n ->
                val number = if (n == 1) "" else "~$n"
                cut(stem, MAX_FILE - extension.length - number.length) + number + extension
            }.first(taken::add)
        }
    }

    private fun notAFile(path: String): Nothing = error("$path is not among the look's files (ThemeLook.files)")

    private fun safe(name: String): String =
        name.map { if (it in UNSAFE || it.isWhitespace() || it.code < 0x20) '_' else it }.joinToString("").let { if (it.all { c -> c == '.' }) "_" else it }

    private fun cut(s: String, n: Int): String = if (s.length <= n) s else s.take(n).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }

    const val MAX_FILE = 128
    private const val MAX_EXTENSION = 10
    private const val UNSAFE = ",()/\\:*?\"<>|"

    fun themeName(name: String): String = cut(name, ThemeReader.MAX_NAME).ifBlank { UNNAMED }

    private const val UNNAMED = "theme"

    private const val MAX_KEY_NAME = 32

    // `*/Shift`, `*/,` select nothing and would make the whole theme unreadable; the others stay in the 3.0 file, unused
    private fun named(name: String): Boolean = KeyNames.split(name).let { (board, key) ->
        key.length <= MAX_KEY_NAME && (board != KeyNames.ALL || KeyNames.isRole(key))
    }

    const val KEY_FONT = "key"

    const val CANDIDATE_FONT = "candidate"

    fun candidateFont(text: Text): Pair<String, Int>? =
        if (text.candidateFont == null && text.candidateWeight == LookDefaults.TEXT.candidateWeight) null
        else (if (text.candidateFont != null) CANDIDATE_FONT else "system") to text.candidateWeight

    fun xml(look: ThemeLook, name: String, dark: Boolean): String = ThemeWriter.write(theme(look, name, dark))

    fun theme(look: ThemeLook, name: String, dark: Boolean): Theme {
        val keys = look.keys
        val names = fileNames(look)
        fun fileName(path: String) = names[path] ?: notAFile(path)
        val s = Styles(names)
        if (look.picture != null && look.base == BoardBase.Picture) {
            s.add(of("keyboard"), "fill" to Value.OfFill(Fill.Picture(null, null)))
        } else {
            s.add(of("keyboard"), "fill" to solid(ref("background")))
            s.add(of("keyboard", FLAT), "fill" to solid(ref("keyboard")))
        }
        val bar = when (val b = look.bar) {
            BarBase.Transparent -> TRANSPARENT
            BarBase.Color -> solid(ref("bar"))
            is BarBase.Picture -> Value.OfFill(Fill.Picture(b.blurDp.toDouble(), b.brightness.toDouble()))
        }
        s.add(of("bar"), "fill" to bar)
        s.add(of("panel"), "fill" to TRANSPARENT)
        s.add(of("panel", FLAT), "fill" to solid(ref("bar")))
        val text = look.text
        val key = of("key")
        s.add(
            key,
            "fill" to s.face(keys.letter.fill, KEY),
            "radius" to radius(keys.radiusDp),
            "border" to Value.OfBorder(if (keys.borderDp > 0) Border(keys.borderDp.toDouble(), ref("keyBorder")) else Border.NONE),
            "shadow" to Value.OfShadow(if (keys.shadowDp > 0) Shadow(0.0, keys.shadowDp.toDouble(), 0.0, ref("keyShadow")) else Shadow.NONE),
            "inset" to inset(keys.gap.v, keys.gap.h),
            "color" to color("keyText"),
            "icon-color" to color("keyText"),
            "font" to Value.Word(if (text.keyFont != null) KEY_FONT else "system"),
            "weight" to Value.Number(text.keyWeight.toDouble()),
            "scale" to Value.Number(text.keyScale.toDouble()),
            "press" to press(keys),
        )
        s.add(key.with(LANDSCAPE), "inset" to inset(keys.gap.vLand, keys.gap.hLand))
        s.add(key.with(COMPACT), "inset" to inset(keys.gap.vCompact, keys.gap.h))
        s.add(key.with(COMPACT).with(LANDSCAPE), "inset" to inset(keys.gap.vLandCompact, keys.gap.hLand))
        s.add(key.with(state = PRESSED), "overlay" to color("keyPressHighlight"))
        s.add(key.with(kind = "function"), "fill" to s.face(keys.function.fill, ALT_KEY), "color" to color("altKeyText"), "icon-color" to color("altKeyText"))
        s.add(key.with(kind = "enter"), "fill" to s.face(keys.enter.fill, ACCENT_KEY), "color" to color("accentKeyText"), "icon-color" to color("accentKeyText"))
        s.add(key.with(kind = "space"), "fill" to s.face(keys.space.fill, KEY))
        kinds(keys).forEach { (kind, k, color) -> s.pressed(listOf(key.with(kind = kind, state = PRESSED)), k.pressed, color) }
        s.pressed(listOf(key.with(FLAT, "space", PRESSED)), keys.space.pressed, SPACE_BAR)
        s.add(key.with(kind = "alt-text"), "color" to color("altKeyText"), "icon-color" to color("altKeyText"))
        s.add(listOf(key.with(part = "mark"), key.with(part = "shortcut")), "color" to color("keyHint"), "icon-color" to color("keyHint"))
        s.add(key.with(part = "shortcut"), "scale" to Value.Number(FULL))
        s.add(key.with(kind = "enter", part = "mark"), "color" to color("accentKeyText"))
        s.add(key.with(part = "badge"), "icon-color" to color("altKeyText"))
        s.add(key.with(state = "locked", part = "badge"), "icon-color" to color("accentKey"))
        s.add(selector("*/reading").with(state = "on"), "color" to color("accentKey"), "icon-color" to color("accentKey"))
        s.add(key.with(FLAT), "fill" to TRANSPARENT, "shadow" to Value.OfShadow(Shadow.NONE), "radius" to radius(0))
        s.add(key.with(FLAT, "space"), "fill" to solid(SPACE_BAR), "shape" to Value.Word("bar"), "radius" to radius(FLAT_SPACE_RADIUS_DP))
        s.add(key.with(FLAT, "enter"), "fill" to solid(ACCENT_KEY), "shape" to Value.Word("circle"))
        // single keys after their kinds, so they win the tie
        s.overrides(keys, keys.overrides.entries.filter { named(it.key) }.sortedBy { it.key }.associate { it.key to it.value })
        val popupShadow = Shadow(0.0, POPUP_SHADOW_DP, POPUP_SHADOW_DP, ColorExpr.Literal(Argb(POPUP_SHADOW)))
        s.add(listOf(of("preview"), of("popup")), "fill" to solid(ref("popupBackground")), "color" to color("popupText"), "radius" to radius(keys.radiusDp), "shadow" to Value.OfShadow(popupShadow))
        s.add(of("popup", state = "selected"), "fill" to solid(ref("genericActiveBackground")), "color" to color("genericActiveForeground"))
        s.add(of("menu"), "fill" to solid(ref("popupBackground")), "color" to color("popupText"), "icon-color" to color("popupText"), "radius" to Value.OfRadius(Radius(List(CORNERS) { 0.0 }, half = true)))
        s.add(of("menu", state = "selected"), "fill" to solid(ACCENT_KEY), "color" to color("accentKeyText"), "icon-color" to color("accentKeyText"))
        // no rounder than a radius is read (else no theme is made and no keyboard drawn)
        fun floating(flat: Boolean, h: Int, v: Int) =
            if (keys.radiusDp == 0) 0 else minOf((if (flat) 0 else keys.radiusDp) + minOf(h, v), Keys.MAX_RADIUS_DP)
        s.add(of("keyboard", FLOATING), "radius" to radius(floating(false, keys.gap.h, keys.gap.v)))
        s.add(of("keyboard", FLOATING).with(LANDSCAPE), "radius" to radius(floating(false, keys.gap.hLand, keys.gap.vLand)))
        s.add(of("keyboard", FLOATING).with(FLAT), "radius" to radius(floating(true, keys.gap.h, keys.gap.v)))
        s.add(of("keyboard", FLOATING).with(FLAT).with(LANDSCAPE), "radius" to radius(floating(true, keys.gap.hLand, keys.gap.vLand)))
        s.add(of("keyboard", FLOATING).with(COMPACT), "radius" to radius(floating(false, keys.gap.h, keys.gap.vCompact)))
        s.add(of("keyboard", FLOATING).with(COMPACT).with(LANDSCAPE), "radius" to radius(floating(false, keys.gap.hLand, keys.gap.vLandCompact)))
        s.add(of("keyboard", FLOATING).with(FLAT).with(COMPACT), "radius" to radius(floating(true, keys.gap.h, keys.gap.vCompact)))
        s.add(of("keyboard", FLOATING).with(FLAT).with(COMPACT).with(LANDSCAPE), "radius" to radius(floating(true, keys.gap.hLand, keys.gap.vLandCompact)))
        val picture = look.picture?.let { p ->
            val cuts = listOfNotNull("portrait" to fileName(p.portrait.file), p.landscape?.let { "landscape" to fileName(it.file) }).toMap()
            Picture(fileName(p.src), p.brightness.toDouble(), p.blurDp.toDouble(), cuts, p.animated)
        }
        candidateFont(text)?.let { (font, weight) -> s.add(of("candidate"), "font" to Value.Word(font), "weight" to Value.Number(weight.toDouble())) }
        val fonts = listOfNotNull(text.keyFont?.let { KEY_FONT to fileName(it) }, text.candidateFont?.let { CANDIDATE_FONT to fileName(it) }).toMap()
        val colors = colors(look.palette).associate { (n, c) -> n to Argb(c) }
        return Theme(themeName(name), dark, VERSION, colors, s.styles, fonts, emptyMap(), picture, darkColors = colors.takeIf { dark })
    }

    // the keys one cannot type without: BlankKeys' own, which refuses a pack that leaves one blank
    private val REQUIRED_KEYS = BlankKeys.REQUIRED_ROLES

    private fun hidesLabel(k: Selector, o: KeyOverride) =
        o.label == KeyLabel.Hide && ((k.key ?: k.starRole) !in REQUIRED_KEYS || o.fill is KeyFill.Image)

    private fun Styles.overrides(keys: Keys, named: Map<String, KeyOverride>) {
        val resolved = resolved(named)
        val overrides = resolved.entries.associate { (n, o) -> selector(n) to o }
        val ripple = keys.press == PressEffect.Ripple
        val fills = overrides.entries.mapNotNull { (k, o) -> o.fill?.let { k to it } }.groupBy({ it.second }, { it.first })
        val pressed = overrides.entries.mapNotNull { (k, o) -> o.pressed?.takeIf { it != KeyPressed.Darken }?.let { k to it } }
            .groupBy({ it.second }, { it.first })
        val darkened = overrides.entries.filter { it.value.pressed == KeyPressed.Darken }.groupBy({ it.value.fill }, { it.key })
        fun List<Selector>.of(scene: String? = null, kind: String? = null, state: String? = null, part: String? = null) =
            map { it.with(scene, kind, state, part) }
        fills.forEach { (f, ks) ->
            add(ks, "fill" to face(f, KEY))
            add(ks.of(kind = "function"), "fill" to face(f, ALT_KEY))
        }
        pressed.forEach { (p, ks) ->
            pressed(ks.of(state = PRESSED), p, KEY)
            if (p is KeyPressed.Image) pressed(ks.of(kind = "function", state = PRESSED), p, ALT_KEY)
        }
        darkened(keys, darkened, beforeFlat = true)
        fills.values.flatten().takeIf { it.isNotEmpty() }?.let { add(it.of(FLAT), "fill" to TRANSPARENT) }
        pressed.values.flatten().takeIf { it.isNotEmpty() }
            ?.let { add(it.of(FLAT, state = PRESSED), "fill" to TRANSPARENT, "press" to Value.Word(if (ripple) "ripple" else "face")) }
        darkened.values.flatten().takeIf { it.isNotEmpty() }?.let { add(it.of(FLAT, state = PRESSED), "fill" to TRANSPARENT) }
        fills.forEach { (f, ks) ->
            add(ks.of(kind = "enter"), "fill" to face(f, ACCENT_KEY))
            add(ks.of(FLAT, "space"), "fill" to face(f, SPACE_BAR))
        }
        pressed.forEach { (p, ks) ->
            when (p) {
                is KeyPressed.Image -> {
                    pressed(ks.of(kind = "enter", state = PRESSED), p, ACCENT_KEY)
                    pressed(ks.of(FLAT, "space", PRESSED), p, SPACE_BAR)
                }
                else -> pressed(ks.of(kind = "enter", state = PRESSED) + ks.of(FLAT, "space", PRESSED), p, null)
            }
        }
        darkened(keys, darkened, beforeFlat = false)
        kindsPressed(keys, overrides)
        labels(keys, overrides, named, resolved)
    }

    private fun Styles.labels(keys: Keys, overrides: Map<Selector, KeyOverride>, named: Map<String, KeyOverride>, resolved: Map<String, KeyOverride>) {
        fun List<Selector>.of(scene: String? = null, kind: String? = null, state: String? = null, part: String? = null) =
            map { it.with(scene, kind, state, part) }
        // 4.0 refuses a blank required key, and a 3.0 theme the app took must convert to one it takes
        overrides.filter { (k, o) -> hidesLabel(k, o) }
            .keys.toList().takeIf { it.isNotEmpty() }
            ?.let { hid ->
                add(hid.of(part = "label"), "hidden" to Value.Flag(true))
                hid.filter { (it.key ?: it.starRole) in REQUIRED_KEYS }.takeIf { it.isNotEmpty() }?.let {
                    add(it.of(FLAT, part = "label"), "hidden" to Value.Flag(false))
                    add(it.of(FLAT, "enter", part = "label") + it.of(FLAT, "space", part = "label"), "hidden" to Value.Flag(true))
                }
                val required = hid.filter { (it.key ?: it.starRole) in REQUIRED_KEYS }
                required.filter { overrides.getValue(it).pressed is KeyPressed.Color }.takeIf { it.isNotEmpty() }?.let {
                    add(it.of(state = PRESSED, part = "label"), "hidden" to Value.Flag(false))
                    add(it.of(FLAT, "enter", PRESSED, "label") + it.of(FLAT, "space", PRESSED, "label"), "hidden" to Value.Flag(false))
                }
                required.filter { overrides.getValue(it).pressed == null }.takeIf { it.isNotEmpty() }?.let { ks ->
                    kinds(keys).filter { (_, k, _) -> k.pressed is KeyPressed.Color }.forEach { (kind, _, _) ->
                        add(ks.of(kind = kind, state = PRESSED, part = "label"), "hidden" to Value.Flag(false))
                        if (kind == "enter" || kind == "space") add(ks.of(FLAT, kind, PRESSED, "label"), "hidden" to Value.Flag(false))
                    }
                }
            }
        named.keys.filter { n -> resolved.getValue(n).label == KeyLabel.Show && role(named, n)?.label == KeyLabel.Hide }
            .takeIf { it.isNotEmpty() }?.let { ns -> add(ns.map { selector(it).with(part = "label") }, "hidden" to Value.Flag(false)) }
    }

    /** An id outranks a role in theme 4, so a role key's 3.0 inheritance is folded into its own styles. */
    internal fun resolved(overrides: Map<String, KeyOverride>): Map<String, KeyOverride> = overrides.mapValues { (name, own) ->
        role(overrides, name)?.let { all -> KeyOverride(own.fill ?: all.fill, own.pressed ?: all.pressed, own.label) } ?: own
    }

    private fun Styles.kindsPressed(keys: Keys, overrides: Map<Selector, KeyOverride>) {
        val ks = overrides.filterValues { it.fill != null && it.pressed == null }.keys.toList().takeIf { it.isNotEmpty() } ?: return
        kinds(keys).forEach { (kind, k, color) -> pressed(ks.map { it.with(kind = kind, state = PRESSED) }, k.pressed, color) }
        pressed(ks.map { it.with(FLAT, "space", PRESSED) }, keys.space.pressed, SPACE_BAR)
    }

    private fun kinds(keys: Keys) = listOf(
        Triple("letter", keys.letter, KEY),
        Triple("function", keys.function, ALT_KEY),
        Triple("enter", keys.enter, ACCENT_KEY),
        Triple("space", keys.space, KEY),
    )

    private fun role(overrides: Map<String, KeyOverride>, name: String): KeyOverride? {
        val (board, key) = KeyNames.split(name)
        return if (board != KeyNames.ALL && KeyNames.isRole(key)) overrides["${KeyNames.ALL}/$key"] else null
    }

    private fun Styles.darkened(keys: Keys, darkened: Map<KeyFill?, List<Selector>>, beforeFlat: Boolean) {
        darkened.forEach { (f, ks) ->
            fun of(scene: String? = null, kind: String? = null) = ks.map { it.with(scene, kind, PRESSED) }
            if (beforeFlat) {
                add(of(), "fill" to face(f ?: keys.letter.fill, KEY), "press" to press(keys))
                add(of(kind = "function"), "fill" to face(f ?: keys.function.fill, ALT_KEY))
                add(of(kind = "space"), "fill" to face(f ?: keys.space.fill, KEY))
            } else {
                add(of(kind = "enter"), "fill" to face(f ?: keys.enter.fill, ACCENT_KEY))
                add(of(FLAT, "space"), "fill" to (f?.let { face(it, SPACE_BAR) } ?: solid(SPACE_BAR)))
            }
        }
    }

    private class Styles(private val names: Map<String, String>) {
        val styles = ArrayList<Style>()

        fun add(selectors: List<Selector>, vararg values: Pair<String, Value>) {
            styles += Style(selectors, values.toMap(), styles.size, 0)
        }

        fun add(selector: Selector, vararg values: Pair<String, Value>) = add(listOf(selector), *values)

        fun face(f: KeyFill, color: ColorExpr): Value = when (f) {
            KeyFill.Color -> solid(color)
            is KeyFill.Image -> Value.OfFill(image(f.slice, color))
            is KeyFill.Picture -> Value.OfFill(Fill.Picture(f.blurDp.toDouble(), f.brightness.toDouble(), color))
        }

        fun pressed(selectors: List<Selector>, p: KeyPressed, color: ColorExpr?) {
            when (p) {
                KeyPressed.Darken -> Unit
                is KeyPressed.Color -> add(selectors, "fill" to solid(ColorExpr.Literal(Argb(p.argb))), "press" to Value.Word("face"))
                is KeyPressed.Image -> add(selectors, "fill" to Value.OfFill(image(p.slice, color)), "press" to Value.Word("face"))
            }
        }

        // an inset past an image's side (a 3.0 file has no bound) clamped to that side: theme 4 takes four digits at most
        private fun image(s: NineSlice, fallback: ColorExpr?) = Fill.Image(
            names[s.file] ?: notAFile(s.file),
            listOf(s.insets.left, s.insets.top, s.insets.right, s.insets.bottom).map { it.coerceIn(0, NineSlice.MAX_SIDE) },
            s.pxPerDp,
            fallback,
        )
    }

    /** Past `ThemeReader.MAX_STYLES` when single keys' faces all differ. */
    const val MAX_STYLES = 100 + 10 * Keys.MAX_OVERRIDES

    /** 大千's `zhuyin/,` is its comma key, not a list: ThemeWriter escapes it. */
    internal fun selector(name: String): Selector = KeyNames.split(name).let { (board, key) ->
        if (board == KeyNames.ALL) Selector(emptySet(), "key", starRole = key) else Selector(emptySet(), "key", board = board, key = key)
    }

    private fun of(widget: String, scene: String? = null, kind: String? = null, state: String? = null, part: String? = null) =
        Selector(setOfNotNull(scene), widget, classes = setOfNotNull(kind), states = setOfNotNull(state), part = part)

    private fun Selector.with(scene: String? = null, kind: String? = null, state: String? = null, part: String? = null) = copy(
        scenes = scenes + setOfNotNull(scene),
        classes = classes + setOfNotNull(kind),
        states = states + setOfNotNull(state),
        part = part ?: this.part,
    )

    private fun ref(name: String): ColorExpr = ColorExpr.Ref(name)

    private fun solid(c: ColorExpr): Value = Value.OfFill(Fill.Solid(c))

    private fun color(name: String): Value = Value.OfColor(ref(name))

    private fun radius(dp: Int): Value = Value.OfRadius(Radius(List(CORNERS) { dp.toDouble() }))

    private fun inset(v: Int, h: Int): Value = Value.OfInset(Inset(v.toDouble(), h.toDouble(), v.toDouble(), h.toDouble()))

    private fun press(keys: Keys): Value = Value.Word(if (keys.press == PressEffect.Ripple) "ripple" else "tint")

    private val KEY = ref("key")
    private val ALT_KEY = ref("altKey")
    private val ACCENT_KEY = ref("accentKey")
    private val SPACE_BAR = ref("spaceBar")
    private val TRANSPARENT = solid(ColorExpr.Literal(Argb.TRANSPARENT))
    private const val FLAT = "flat"
    private const val LANDSCAPE = "landscape"
    private const val COMPACT = "compact"
    private const val FLOATING = "floating"
    private const val PRESSED = "pressed"
    private const val VERSION = "4.0"
    private const val CORNERS = 4
    private const val FULL = 100.0
    private const val FLAT_SPACE_RADIUS_DP = 3
    private const val POPUP_SHADOW_DP = 2.0
    private const val POPUP_SHADOW = 0x33000000
}
