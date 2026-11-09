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
     * The look's files ([ThemeLook.files]) by the names theme 4 gives them: each its [fileName] as theme 4
     * takes a file's name (ThemeReader's FILE: a character it does not, `,()/\:*?"<>|` and white space,
     * as `_`; [MAX_FILE] characters at most, the extension kept), one that another file has already
     * numbered (`a~2.png`), so two images of one name in two folders stay two. What the app looks a
     * file up by, and the theme written ([xml]) names: a 3.0 file of any names (the web converts any) reads.
     */
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

    // every file a theme names is one of ThemeLook.files, named by fileNames: one not is a bug of ours
    private fun notAFile(path: String): Nothing = error("$path is not among the look's files (ThemeLook.files)")

    // a file's name of the characters theme 4 takes in one, one at least
    private fun safe(name: String): String = name.map { if (it in UNSAFE || it.isWhitespace()) '_' else it }.joinToString("").ifEmpty { "_" }

    // [s] at most [n] characters, not a character cut in two (a surrogate pair's first left alone)
    private fun cut(s: String, n: Int): String = if (s.length <= n) s else s.take(n).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }

    /** A file's name in a theme 4 at its longest (ThemeReader's FILE). */
    const val MAX_FILE = 128
    private const val MAX_EXTENSION = 10
    private const val UNSAFE = ",()/\\:*?\"<>|"

    /** [name] as a theme 4's name: cut to [ThemeReader.MAX_NAME], `theme` where blank (a 3.0 file's may be either). */
    fun themeName(name: String): String = cut(name, ThemeReader.MAX_NAME).ifBlank { UNNAMED }

    private const val UNNAMED = "theme"

    /** The font's name in the file written, for the look's key font (theme 4 names a `<font>`). */
    const val KEY_FONT = "key"

    /** The look as a 4.0 theme's text: [theme], as [ThemeWriter] writes every theme. */
    fun xml(look: ThemeLook, name: String, dark: Boolean): String = ThemeWriter.write(theme(look, name, dark))

    /** The look as a 4.0 theme: what [ThemeReader] reads [xml]'s text as (its styles at no line). */
    fun theme(look: ThemeLook, name: String, dark: Boolean): Theme {
        val keys = look.keys
        val names = fileNames(look)
        fun fileName(path: String) = names[path] ?: notAFile(path)
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
        kinds(keys).forEach { (kind, k, color) -> s.pressed(listOf(key.with(kind = kind, state = PRESSED)), k.pressed, color) }
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
        s.overrides(keys, keys.overrides.entries.sortedBy { it.key }.associate { it.key to it.value })
        val popupShadow = Shadow(0.0, POPUP_SHADOW_DP, POPUP_SHADOW_DP, ColorExpr.Literal(Argb(POPUP_SHADOW)))
        s.add(listOf(of("preview"), of("popup")), "fill" to solid(ref("popupBackground")), "color" to color("popupText"), "radius" to radius(keys.radiusDp), "shadow" to Value.OfShadow(popupShadow))
        s.add(of("popup", state = "selected"), "fill" to solid(ref("genericActiveBackground")), "color" to color("genericActiveForeground"))
        s.add(of("menu"), "fill" to solid(ACCENT_KEY), "color" to color("accentKeyText"), "icon-color" to color("accentKeyText"), "radius" to Value.OfRadius(Radius(List(CORNERS) { 0.0 }, half = true)))
        // theme 3.0's cell in focus: the key's pressed colour over its own
        s.add(of("menu", state = "selected"), "overlay" to color("keyPressHighlight"))
        // no rounder than a radius is read (keys of 24 dp and a gap made no theme, and no keyboard was drawn); the
        // frame round it is the app's outline, of no such limit (WindowLooks.cornerDp), so its corners are the same.
        // The handle the engine's default (WindowLooks.handleColor), as the window's frame
        s.add(of("keyboard", FLOATING), "radius" to radius(minOf(keys.radiusDp + minOf(keys.gap.h, keys.gap.v), Keys.MAX_RADIUS_DP)))
        val picture = look.picture?.let { p ->
            val cuts = listOfNotNull("portrait" to fileName(p.portrait.file), p.landscape?.let { "landscape" to fileName(it.file) }).toMap()
            Picture(fileName(p.src), p.brightness.toDouble(), p.blurDp.toDouble(), cuts)
        }
        val fonts = look.text.keyFont?.let { mapOf(KEY_FONT to fileName(it)) } ?: emptyMap()
        val colors = colors(look.palette).associate { (n, c) -> n to Argb(c) }
        // one version, light or dark; the dark one's colours its dark version's too, as a file of it reads
        return Theme(themeName(name), dark, VERSION, colors, s.styles, fonts, emptyMap(), picture, darkColors = colors.takeIf { dark })
    }

    // Single keys' faces, each over its kind's colour as ThemeColors.face gives it (under a picture, while an
    // image loads or cannot be read, and as fill=Color's face): a key and its kind together outrank the key
    // alone, so a kind whose colour is not the letters' has its own style. Flat, a letter or function key has
    // no face at all, its own or pressed (3.0's face=null), the space and Return theirs. Keys of one face
    // share its styles (a list of selectors), and the styles go in this order for the ties: a key's, its
    // function kind's, flat none, then Return's and the flat space's. [named]: by the keys' names, each as 3.0
    // drew it ([resolved]).
    private fun Styles.overrides(keys: Keys, named: Map<String, KeyOverride>) {
        val resolved = resolved(named)
        val overrides = resolved.entries.associate { (n, o) -> selector(n) to o }
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
        kindsPressed(keys, overrides)
        overrides.filterValues { it.label == KeyLabel.Hide }.keys.toList().takeIf { it.isNotEmpty() }
            ?.let { add(it.of(part = "label"), "hidden" to Value.Flag(true)) }
        // a board's key that shows its label where its role's on every board hides theirs: as an id, it wins
        named.keys.filter { n -> resolved.getValue(n).label == KeyLabel.Show && role(named, n)?.label == KeyLabel.Hide }
            .takeIf { it.isNotEmpty() }?.let { ns -> add(ns.map { selector(it).with(part = "label") }, "hidden" to Value.Flag(false)) }
    }

    /**
     * [overrides] as theme 3.0 drew each (KeyStyleResolver): a board's key of a role (`qwerty/shift`)
     * takes what it does not set from the role's on every board (`*`/shift), so its own Darken tints the
     * role's fill, not its kind's, and its own label wins; an id outranks a role in theme 4, so the key
     * has it all on its own styles.
     */
    internal fun resolved(overrides: Map<String, KeyOverride>): Map<String, KeyOverride> = overrides.mapValues { (name, own) ->
        role(overrides, name)?.let { all -> KeyOverride(own.fill ?: all.fill, own.pressed ?: all.pressed, own.label) } ?: own
    }

    // [overrides] of a face of their own and no pressed one: pressed as their kind (3.0's own ?: all ?: kind), which
    // their fill, an id's, would outrank; each kind's on the keys' own names, after their flat style (the tie the
    // kind's wins too), one style a kind for them all
    private fun Styles.kindsPressed(keys: Keys, overrides: Map<Selector, KeyOverride>) {
        val ks = overrides.filterValues { it.fill != null && it.pressed == null }.keys.toList().takeIf { it.isNotEmpty() } ?: return
        kinds(keys).forEach { (kind, k, color) -> pressed(ks.map { it.with(kind = kind, state = PRESSED) }, k.pressed, color) }
        pressed(ks.map { it.with(FLAT, "space", PRESSED) }, keys.space.pressed, SPACE_BAR)
    }

    // each kind's class, its look and its colour
    private fun kinds(keys: Keys) = listOf(
        Triple("letter", keys.letter, KEY),
        Triple("function", keys.function, ALT_KEY),
        Triple("enter", keys.enter, ACCENT_KEY),
        Triple("space", keys.space, KEY),
    )

    // the override of [name]'s role on every board, where [name] is a board's key of a role
    private fun role(overrides: Map<String, KeyOverride>, name: String): KeyOverride? {
        val (board, key) = KeyNames.split(name)
        return if (board != KeyNames.ALL && KeyNames.isRole(key)) overrides["${KeyNames.ALL}/$key"] else null
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

        // [fallback]: the kind's colour, which theme 3.0 showed where the image was not (yet) there; an inset
        // past any key image's side (a 3.0 file has no bound) at that side: theme 4 takes four digits at most
        private fun image(s: NineSlice, fallback: ColorExpr?) = Fill.Image(
            names[s.file] ?: notAFile(s.file),
            listOf(s.insets.left, s.insets.top, s.insets.right, s.insets.bottom).map { it.coerceIn(0, NineSlice.MAX_SIDE) },
            s.pxPerDp,
            fallback,
        )
    }

    /**
     * The styles a written theme may have ([ThemeReader]'s `maxStyles`), more than a theme file's
     * `ThemeReader.MAX_STYLES` when single keys' faces all differ: the look's own (some 30), up to nine
     * for each single key of the most a 3.0 theme brings (four its fill's, five its pressed face's, a
     * Darken's), and a dozen the keys share (flat, the kinds' pressed faces on their names, labels).
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
}
