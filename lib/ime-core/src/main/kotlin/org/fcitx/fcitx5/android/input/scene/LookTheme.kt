/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.input.theme.BarBase
import org.fcitx.fcitx5.android.input.theme.BoardBase
import org.fcitx.fcitx5.android.input.theme.KeyFill
import org.fcitx.fcitx5.android.input.theme.KeyLabel
import org.fcitx.fcitx5.android.input.theme.KeyPressed
import org.fcitx.fcitx5.android.input.theme.KindStyle
import org.fcitx.fcitx5.android.input.theme.NineSlice
import org.fcitx.fcitx5.android.input.theme.Palette
import org.fcitx.fcitx5.android.input.theme.PressEffect
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.scene.theme.Argb

/**
 * A theme 3.0 look as a theme 4.0 file (theme-4.xsd) for the scene engine, written as the built-in
 * themes' are (keyboard-scene's material-light.xml: LookDefaults as styles): the palette as its
 * colours, the shape, gaps and press as `key` styles, each kind's fill and pressed face as its
 * class's, single keys' overrides by their names, which theme 4 selects by the same `board/key` and
 * `*`/role. Files are named by their names alone (4.0 takes no path): [fileNames] is what the app
 * looks a file up by.
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

    fun xml(look: ThemeLook, name: String, dark: Boolean): String = buildString {
        val keys = look.keys
        val names = fileNames(look)
        fun fileName(path: String) = names.getOrElse(path) { LookTheme.fileName(path) }
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<theme xmlns="https://youmo-ime/theme/4" name="${escape(name)}" dark="$dark" version="4.0">""")
        appendLine("  <colors>")
        colors(look.palette).forEach { (n, c) -> appendLine("""    <color name="$n" value="${Argb(c)}"/>""") }
        appendLine("  </colors>")
        look.picture?.let { p ->
            appendLine("""  <picture src="${escape(fileName(p.src))}" brightness="${p.brightness}%" blur="${p.blurDp}dp">""")
            appendLine("""    <cut orientation="portrait" file="${escape(fileName(p.portrait.file))}"/>""")
            p.landscape?.let { appendLine("""    <cut orientation="landscape" file="${escape(fileName(it.file))}"/>""") }
            appendLine("  </picture>")
        }
        look.text.keyFont?.let { appendLine("""  <font name="$KEY_FONT" src="${escape(fileName(it))}"/>""") }
        // the board: the picture under it, or ThemeColors.board's colour (block: background, flat: keyboard)
        if (look.picture != null && look.base == BoardBase.Picture) {
            style("keyboard", "fill" to "picture")
        } else {
            style("keyboard", "fill" to "@background")
            style("@flat keyboard", "fill" to "@keyboard")
        }
        style(
            "bar",
            "fill" to when (val bar = look.bar) {
                BarBase.Transparent -> "transparent"
                BarBase.Color -> "@bar"
                is BarBase.Picture -> picture(bar.blurDp, bar.brightness)
            },
        )
        style("panel", "fill" to "transparent")
        style("@flat panel", "fill" to "@bar")
        val text = look.text
        style(
            "key",
            "fill" to fill(names, keys.letter.fill, "@key"),
            "radius" to "${keys.radiusDp}dp",
            "border" to if (keys.borderDp > 0) "${keys.borderDp}dp @keyBorder" else "none",
            "shadow" to if (keys.shadowDp > 0) "0 ${keys.shadowDp}dp 0 @keyShadow" else "none",
            "inset" to "${keys.gap.v}dp ${keys.gap.h}dp",
            "color" to "@keyText",
            "icon-color" to "@keyText",
            "font" to if (text.keyFont != null) KEY_FONT else "system",
            "weight" to "${text.keyWeight}",
            "scale" to "${text.keyScale}%",
            "press" to if (keys.press == PressEffect.Ripple) "ripple" else "tint",
        )
        style("@landscape key", "inset" to "${keys.gap.vLand}dp ${keys.gap.hLand}dp")
        style("key:pressed", "overlay" to "@keyPressHighlight")
        style("key.function", "fill" to fill(names, keys.function.fill, "@altKey"), "color" to "@altKeyText", "icon-color" to "@altKeyText")
        style("key.enter", "fill" to fill(names, keys.enter.fill, "@accentKey"), "color" to "@accentKeyText", "icon-color" to "@accentKeyText")
        style("key.space", "fill" to fill(names, keys.space.fill, "@key"))
        listOf("letter" to keys.letter, "function" to keys.function, "enter" to keys.enter, "space" to keys.space)
            .forEach { (kind, k) -> pressed(names, "key.$kind:pressed", k.pressed) }
        style("key.alt-text", "color" to "@altKeyText", "icon-color" to "@altKeyText")
        style("key mark, key shortcut", "color" to "@keyHint", "icon-color" to "@keyHint")
        // a shortcut's text is not of the theme's size (rules B §6: keyScale leaves it be)
        style("key shortcut", "scale" to "100%")
        style("key.enter mark", "color" to "@accentKeyText")
        style("key badge", "icon-color" to "@altKeyText")
        style("*/reading:on", "color" to "@accentKey", "icon-color" to "@accentKey")
        style("@flat key", "fill" to "transparent", "shadow" to "none", "radius" to "0")
        style("@flat key.space", "fill" to "@spaceBar", "shape" to "bar", "radius" to "3dp")
        style("@flat key.enter", "fill" to "@accentKey", "shape" to "circle")
        // single keys over their kinds, later so they win the tie
        keys.overrides.toSortedMap().forEach { (key, o) ->
            // Color: the kind's colour, which the kind's style gives already
            o.fill?.takeIf { it != KeyFill.Color }?.let { style(key, "fill" to fill(names, it, "")) }
            o.pressed?.let { pressed(names, "$key:pressed", it) }
            if (o.label == KeyLabel.Hide) style("$key label", "hidden" to "true")
        }
        style("preview, popup", "fill" to "@popupBackground", "color" to "@popupText", "radius" to "${keys.radiusDp}dp", "shadow" to "0 2dp 2dp #33000000")
        style("popup:selected", "fill" to "@genericActiveBackground", "color" to "@genericActiveForeground")
        style("menu", "fill" to "@accentKey", "color" to "@accentKeyText", "icon-color" to "@accentKeyText", "radius" to "50%")
        // theme 3.0's cell in focus: the key's pressed colour over its own
        style("menu:selected", "overlay" to "@keyPressHighlight")
        style("@floating keyboard", "radius" to "${keys.radiusDp + minOf(keys.gap.h, keys.gap.v)}dp")
        style("handle", "fill" to "mix(@background, @keyText, 10%)")
        appendLine("</theme>")
    }

    /** The kinds' fills as the look has them: what the kind's own [KindStyle] says. */
    private fun fill(names: Map<String, String>, f: KeyFill, color: String): String = when (f) {
        KeyFill.Color -> color
        is KeyFill.Image -> image(names, f.slice)
        is KeyFill.Picture -> picture(f.blurDp, f.brightness)
    }

    // a pressed face of its own is not tinted too (rules B §3): press="face"
    private fun StringBuilder.pressed(names: Map<String, String>, select: String, p: KeyPressed) {
        when (p) {
            KeyPressed.Darken -> Unit
            is KeyPressed.Color -> style(select, "fill" to Argb(p.argb).toString(), "press" to "face")
            is KeyPressed.Image -> style(select, "fill" to image(names, p.slice), "press" to "face")
        }
    }

    private fun image(names: Map<String, String>, s: NineSlice) =
        "image(${names[s.file] ?: fileName(s.file)}, ${s.insets.left} ${s.insets.top} ${s.insets.right} ${s.insets.bottom}, ${s.pxPerDp}x)"

    private fun picture(blurDp: Int, brightness: Int) = "picture(${blurDp}dp, $brightness%)"

    private fun StringBuilder.style(select: String, vararg props: Pair<String, String>) {
        append("""  <style select="${escape(select)}"""")
        props.forEach { (k, v) -> append(""" $k="${escape(v)}"""") }
        appendLine("/>")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
