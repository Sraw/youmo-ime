/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.rules.KeyNames
import org.fcitx.fcitx5.android.scene.theme.Argb
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

/**
 * A theme 3.0 look as a theme 4.0 ([theme]; its file, [xml], as [ThemeWriter] writes it) for the scene
 * engine, made as the built-in themes' are (keyboard-scene's material-light.xml: LookDefaults as
 * styles): the palette as its colours, the shape, gaps and press as `key` styles, each kind's fill and
 * pressed face as its class's, single keys' overrides by their names, which theme 4 selects by the
 * same `board/key` and `*`/role. Files are named by their names alone (4.0 takes no path):
 * [fileNames] is what the app looks a file up by.
 */
object LookTheme {

    /** The 24 colours in theme 4's names, as the palette has them. */
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

    /** A file of the look ([ThemeLook.files]) as theme 4 names it: its name without the folders. */
    fun fileName(path: String): String = path.substringAfterLast('/')

    /**
     * The look's files ([ThemeLook.files]) by the names theme 4 gives them: each its [fileName], one
     * that another file has already numbered (`a~2.png`), so two images of one name in two folders
     * stay two. What the app looks a file up by, and the theme written ([xml]) names.
     */
    fun fileNames(look: ThemeLook): Map<String, String> {
        val taken = HashSet<String>()
        return look.files().associateWith { path ->
            val name = fileName(path)
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            generateSequence(1) { it + 1 }.map { n -> if (n == 1) name else "${name.take(dot)}~$n${name.drop(dot)}" }.first(taken::add)
        }
    }

    /** The font's name in the file written, for the look's key font (theme 4 names a `<font>`). */
    const val KEY_FONT = "key"

    /** The look as a 4.0 theme's text: [theme], as [ThemeWriter] writes every theme. */
    fun xml(look: ThemeLook, name: String, dark: Boolean): String = ThemeWriter.write(theme(look, name, dark))

    /** The look as a 4.0 theme: what [ThemeReader] reads [xml]'s text as (its styles at no line). */
    fun theme(look: ThemeLook, name: String, dark: Boolean): Theme {
        val keys = look.keys
        val names = fileNames(look)
        fun fileName(path: String) = names.getOrElse(path) { LookTheme.fileName(path) }
        val s = Styles(names)
        // the board: the picture under it, or ThemeColors.board's colour (block: background, flat: keyboard)
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
        s.add(key.with(state = PRESSED), "overlay" to color("keyPressHighlight"))
        s.add(key.with(kind = "function"), "fill" to s.face(keys.function.fill, ALT_KEY), "color" to color("altKeyText"), "icon-color" to color("altKeyText"))
        s.add(key.with(kind = "enter"), "fill" to s.face(keys.enter.fill, ACCENT_KEY), "color" to color("accentKeyText"), "icon-color" to color("accentKeyText"))
        s.add(key.with(kind = "space"), "fill" to s.face(keys.space.fill, KEY))
        listOf(Triple("letter", keys.letter, KEY), Triple("function", keys.function, ALT_KEY), Triple("enter", keys.enter, ACCENT_KEY), Triple("space", keys.space, KEY))
            .forEach { (kind, k, color) -> s.pressed(listOf(key.with(kind = kind, state = PRESSED)), k.pressed, color) }
        // a flat space's colour is the space bar's (ThemeColors.face), its pressed image's too
        s.pressed(listOf(key.with(FLAT, "space", PRESSED)), keys.space.pressed, SPACE_BAR)
        s.add(key.with(kind = "alt-text"), "color" to color("altKeyText"), "icon-color" to color("altKeyText"))
        s.add(listOf(key.with(part = "mark"), key.with(part = "shortcut")), "color" to color("keyHint"), "icon-color" to color("keyHint"))
        // a shortcut's text is not of the theme's size (rules B §6: keyScale leaves it be)
        s.add(key.with(part = "shortcut"), "scale" to Value.Number(FULL))
        s.add(key.with(kind = "enter", part = "mark"), "color" to color("accentKeyText"))
        s.add(key.with(part = "badge"), "icon-color" to color("altKeyText"))
        s.add(selector("*/reading").with(state = "on"), "color" to color("accentKey"), "icon-color" to color("accentKey"))
        s.add(key.with(FLAT), "fill" to TRANSPARENT, "shadow" to Value.OfShadow(Shadow.NONE), "radius" to radius(0))
        s.add(key.with(FLAT, "space"), "fill" to solid(SPACE_BAR), "shape" to Value.Word("bar"), "radius" to radius(FLAT_SPACE_RADIUS_DP))
        s.add(key.with(FLAT, "enter"), "fill" to solid(ACCENT_KEY), "shape" to Value.Word("circle"))
        // single keys over their kinds, later so they win the tie
        s.overrides(keys, keys.overrides.entries.sortedBy { it.key }.associate { selector(it.key) to it.value })
        val popupShadow = Shadow(0.0, POPUP_SHADOW_DP, POPUP_SHADOW_DP, ColorExpr.Literal(Argb(POPUP_SHADOW)))
        s.add(listOf(of("preview"), of("popup")), "fill" to solid(ref("popupBackground")), "color" to color("popupText"), "radius" to radius(keys.radiusDp), "shadow" to Value.OfShadow(popupShadow))
        s.add(of("popup", state = "selected"), "fill" to solid(ref("genericActiveBackground")), "color" to color("genericActiveForeground"))
        s.add(of("menu"), "fill" to solid(ACCENT_KEY), "color" to color("accentKeyText"), "icon-color" to color("accentKeyText"), "radius" to Value.OfRadius(Radius(List(CORNERS) { 0.0 }, half = true)))
        // theme 3.0's cell in focus: the key's pressed colour over its own
        s.add(of("menu", state = "selected"), "overlay" to color("keyPressHighlight"))
        s.add(of("keyboard", FLOATING), "radius" to radius(keys.radiusDp + minOf(keys.gap.h, keys.gap.v)))
        s.add(of("handle"), "fill" to solid(ColorExpr.Fn("mix", listOf(ref("background"), ref("keyText")), HANDLE_MIX)))
        val picture = look.picture?.let { p ->
            val cuts = listOfNotNull("portrait" to fileName(p.portrait.file), p.landscape?.let { "landscape" to fileName(it.file) }).toMap()
            Picture(fileName(p.src), p.brightness.toDouble(), p.blurDp.toDouble(), cuts)
        }
        val fonts = look.text.keyFont?.let { mapOf(KEY_FONT to fileName(it)) } ?: emptyMap()
        val colors = colors(look.palette).associate { (n, c) -> n to Argb(c) }
        // one version, light or dark; the dark one's colours its dark version's too, as a file of it reads
        return Theme(name, dark, VERSION, colors, s.styles, fonts, emptyMap(), picture, darkColors = colors.takeIf { dark })
    }

    // Single keys' faces, each over its kind's colour as ThemeColors.face gives it (under a picture, while an
    // image loads or cannot be read, and as fill=Color's face): a key and its kind together outrank the key
    // alone, so a kind whose colour is not the letters' has its own style. Flat, a letter or function key has
    // no face at all, its own or pressed (3.0's face=null), the space and Return theirs. Keys of one face
    // share its styles (a list of selectors), and the styles go in this order for the ties: a key's, its
    // function kind's, flat none, then Return's and the flat space's.
    private fun Styles.overrides(keys: Keys, overrides: Map<Selector, KeyOverride>) {
        val ripple = keys.press == PressEffect.Ripple
        val fills = overrides.entries.mapNotNull { (k, o) -> o.fill?.let { k to it } }.groupBy({ it.second }, { it.first })
        val pressed = overrides.entries.mapNotNull { (k, o) -> o.pressed?.takeIf { it != KeyPressed.Darken }?.let { k to it } }
            .groupBy({ it.second }, { it.first })
        // Darken, a key's own: its face tinted as it is, not its kind's pressed one (3.0's own won); by its own fill
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
        // flat, nothing pressed either: no face, and no tint where 3.0 drew none over a pressed face of its own
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
        overrides.filterValues { it.label == KeyLabel.Hide }.keys.toList().takeIf { it.isNotEmpty() }
            ?.let { add(it.of(part = "label"), "hidden" to Value.Flag(true)) }
    }

    // [overrides]' Darken styles, before the flat ones (a key's, its function kind's, the space's) or after (Return's, the flat space's)
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

    // the styles of the theme made, in order; the look's files by the names it gives them
    private class Styles(private val names: Map<String, String>) {
        val styles = ArrayList<Style>()

        fun add(selectors: List<Selector>, vararg values: Pair<String, Value>) {
            styles += Style(selectors, values.toMap(), styles.size, 0)
        }

        fun add(selector: Selector, vararg values: Pair<String, Value>) = add(listOf(selector), *values)

        /** A kind's or key's fill as the look has it, over [color], the kind's colour. */
        fun face(f: KeyFill, color: ColorExpr): Value = when (f) {
            KeyFill.Color -> solid(color)
            is KeyFill.Image -> Value.OfFill(image(f.slice, color))
            // theme 3.0 laid the kind's colour over the picture (its face's colour, translucent or not)
            is KeyFill.Picture -> Value.OfFill(Fill.Picture(f.blurDp.toDouble(), f.brightness.toDouble(), color))
        }

        // a pressed face of its own is not tinted too (rules B §3): press="face"
        fun pressed(selectors: List<Selector>, p: KeyPressed, color: ColorExpr?) {
            when (p) {
                KeyPressed.Darken -> Unit
                is KeyPressed.Color -> add(selectors, "fill" to solid(ColorExpr.Literal(Argb(p.argb))), "press" to Value.Word("face"))
                is KeyPressed.Image -> add(selectors, "fill" to Value.OfFill(image(p.slice, color)), "press" to Value.Word("face"))
            }
        }

        // [fallback]: the kind's colour, which theme 3.0 showed where the image was not (yet) there
        private fun image(s: NineSlice, fallback: ColorExpr?) =
            Fill.Image(names[s.file] ?: fileName(s.file), listOf(s.insets.left, s.insets.top, s.insets.right, s.insets.bottom), s.pxPerDp, fallback)
    }

    /**
     * The styles a written theme may have ([ThemeReader]'s `maxStyles`): its own lines, and up to ten a
     * single key's face of its own (four its fill's, six its pressed one's) for each of the most a 3.0
     * theme brings, more than a theme file's `ThemeReader.MAX_STYLES` when they all differ.
     */
    const val MAX_STYLES = 100 + 10 * Keys.MAX_OVERRIDES

    /**
     * A 3.0 key's name (`board/key`, `*`/role) as the selector of that key: `*`/role every board's key of
     * the role, any other the board's key of that id, whatever characters it has (大千's `zhuyin/,` is
     * its comma key, the number pad's star its star key, not every key: ThemeWriter escapes them).
     */
    internal fun selector(name: String): Selector = KeyNames.split(name).let { (board, key) ->
        if (board == KeyNames.ALL) Selector(emptySet(), "key", starRole = key) else Selector(emptySet(), "key", board = board, key = key)
    }

    // a widget's selector: in [scene], of [kind], in [state], its [part]
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

    // vertical, horizontal
    private fun inset(v: Int, h: Int): Value = Value.OfInset(Inset(v.toDouble(), h.toDouble(), v.toDouble(), h.toDouble()))

    private fun press(keys: Keys): Value = Value.Word(if (keys.press == PressEffect.Ripple) "ripple" else "tint")

    private val KEY = ref("key")
    private val ALT_KEY = ref("altKey")
    private val ACCENT_KEY = ref("accentKey")
    private val SPACE_BAR = ref("spaceBar")
    private val TRANSPARENT = solid(ColorExpr.Literal(Argb.TRANSPARENT))
    private const val FLAT = "flat"
    private const val LANDSCAPE = "landscape"
    private const val FLOATING = "floating"
    private const val PRESSED = "pressed"
    private const val VERSION = "4.0"
    private const val CORNERS = 4
    private const val FULL = 100.0
    private const val FLAT_SPACE_RADIUS_DP = 3
    private const val POPUP_SHADOW_DP = 2.0
    private const val POPUP_SHADOW = 0x33000000
    private const val HANDLE_MIX = 10.0
}
