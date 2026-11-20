/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

/**
 * A [ThemeLookFile] as theme file 3.0's json, compact, what [ThemeLookReader] reads back: the version first (a
 * reader sees it before what it governs), then every field in the order the format has them (the app's files and
 * the theme maker's are written so), `null` where one is none, a sealed type's `"type"` before its own fields.
 */
object ThemeLookWriter {
    fun write(file: ThemeLookFile): String = buildString {
        val look = file.look
        obj {
            field("version") { str(ThemeLookReader.VERSION) }
            field("name") { str(file.name) }
            field("isDark") { append(file.dark) }
            field("palette") { palette(look.palette) }
            field("picture") { nullable(look.picture) { picture(it) } }
            field("base") {
                obj {
                    field("type") {
                        str(
                            when (look.base) {
                                BoardBase.Color -> "color"
                                BoardBase.Picture -> "picture"
                            },
                        )
                    }
                }
            }
            field("bar") {
                obj {
                    when (val b = look.bar) {
                        BarBase.Transparent -> field("type") { str("transparent") }
                        BarBase.Color -> field("type") { str("color") }
                        is BarBase.Picture -> {
                            field("type") { str("picture") }
                            field("blurDp") { append(b.blurDp) }
                            field("brightness") { append(b.brightness) }
                        }
                    }
                }
            }
            field("keys") { keys(look.keys) }
            field("text") {
                val t = look.text
                obj {
                    field("keyFont") { nullable(t.keyFont) { str(it) } }
                    field("candidateFont") { nullable(t.candidateFont) { str(it) } }
                    field("keyWeight") { append(t.keyWeight) }
                    field("candidateWeight") { append(t.candidateWeight) }
                    field("keyScale") { append(t.keyScale) }
                }
            }
        }
    }

    private fun StringBuilder.palette(p: Palette) = obj {
        listOf(
            "backgroundColor" to p.backgroundColor,
            "barColor" to p.barColor,
            "keyboardColor" to p.keyboardColor,
            "keyBackgroundColor" to p.keyBackgroundColor,
            "keyTextColor" to p.keyTextColor,
            "candidateTextColor" to p.candidateTextColor,
            "candidateLabelColor" to p.candidateLabelColor,
            "candidateCommentColor" to p.candidateCommentColor,
            "altKeyBackgroundColor" to p.altKeyBackgroundColor,
            "altKeyTextColor" to p.altKeyTextColor,
            "accentKeyBackgroundColor" to p.accentKeyBackgroundColor,
            "accentKeyTextColor" to p.accentKeyTextColor,
            "keyPressHighlightColor" to p.keyPressHighlightColor,
            "keyShadowColor" to p.keyShadowColor,
            "popupBackgroundColor" to p.popupBackgroundColor,
            "popupTextColor" to p.popupTextColor,
            "spaceBarColor" to p.spaceBarColor,
            "dividerColor" to p.dividerColor,
            "clipboardEntryColor" to p.clipboardEntryColor,
            "genericActiveBackgroundColor" to p.genericActiveBackgroundColor,
            "genericActiveForegroundColor" to p.genericActiveForegroundColor,
            "keyBorderColor" to p.keyBorderColor,
            "keyHintColor" to p.keyHintColor,
            "toolbarIconColor" to p.toolbarIconColor,
        ).forEach { (name, argb) -> field(name) { append(argb) } }
    }

    private fun StringBuilder.picture(p: Picture) = obj {
        field("src") { str(p.src) }
        field("portrait") { crop(p.portrait) }
        field("landscape") { nullable(p.landscape) { crop(it) } }
        field("brightness") { append(p.brightness) }
        field("blurDp") { append(p.blurDp) }
    }

    // its rectangle's edges by name in this order, as 2.1's was written
    private fun StringBuilder.crop(c: Crop) = obj {
        field("file") { str(c.file) }
        field("cropRect") {
            nullable(c.cropRect) { b ->
                obj {
                    field("bottom") { append(b.bottom) }
                    field("left") { append(b.left) }
                    field("right") { append(b.right) }
                    field("top") { append(b.top) }
                }
            }
        }
        field("rotation") { append(c.rotation) }
    }

    private fun StringBuilder.keys(k: Keys) = obj {
        field("blocks") { append(k.blocks) }
        field("radiusDp") { append(k.radiusDp) }
        field("borderDp") { append(k.borderDp) }
        field("shadowDp") { append(k.shadowDp) }
        field("gap") {
            obj {
                field("h") { append(k.gap.h) }
                field("v") { append(k.gap.v) }
                field("hLand") { append(k.gap.hLand) }
                field("vLand") { append(k.gap.vLand) }
            }
        }
        field("press") {
            str(
                when (k.press) {
                    PressEffect.Tint -> "tint"
                    PressEffect.Ripple -> "ripple"
                },
            )
        }
        for ((name, kind) in listOf("letter" to k.letter, "function" to k.function, "space" to k.space, "enter" to k.enter)) {
            field(name) {
                obj {
                    field("fill") { fill(kind.fill) }
                    field("pressed") { pressed(kind.pressed) }
                }
            }
        }
        field("overrides") {
            obj {
                for ((name, o) in k.overrides) {
                    field(name) {
                        obj {
                            field("fill") { nullable(o.fill) { fill(it) } }
                            field("pressed") { nullable(o.pressed) { pressed(it) } }
                            field("label") {
                                str(
                                    when (o.label) {
                                        KeyLabel.Show -> "show"
                                        KeyLabel.Hide -> "hide"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun StringBuilder.fill(f: KeyFill) = obj {
        when (f) {
            KeyFill.Color -> field("type") { str("color") }
            is KeyFill.Image -> {
                field("type") { str("image") }
                slice(f.slice)
            }
            is KeyFill.Picture -> {
                field("type") { str("picture") }
                field("blurDp") { append(f.blurDp) }
                field("brightness") { append(f.brightness) }
            }
        }
    }

    private fun StringBuilder.pressed(p: KeyPressed) = obj {
        when (p) {
            KeyPressed.Darken -> field("type") { str("darken") }
            is KeyPressed.Color -> {
                field("type") { str("color") }
                field("argb") { append(p.argb) }
            }
            is KeyPressed.Image -> {
                field("type") { str("image") }
                slice(p.slice)
            }
        }
    }

    // insets left, top, right, bottom
    private fun Members.slice(s: NineSlice) {
        field("file") { str(s.file) }
        field("insets") { s.insets.run { append('[').append(left).append(',').append(top).append(',').append(right).append(',').append(bottom).append(']') } }
        field("pxPerDp") { append(s.pxPerDp) }
    }

    /** An object's members as they are written: a comma before each but the first. */
    private class Members(val out: StringBuilder) {
        private var first = true

        fun field(name: String, value: StringBuilder.() -> Unit) {
            if (!first) out.append(',')
            first = false
            out.str(name)
            out.append(':')
            out.value()
        }
    }

    private fun StringBuilder.obj(members: Members.() -> Unit) {
        append('{')
        Members(this).members()
        append('}')
    }

    private fun <T : Any> StringBuilder.nullable(value: T?, write: StringBuilder.(T) -> Unit) {
        if (value == null) append("null") else write(value)
    }

    private fun StringBuilder.str(s: String) {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u").append(c.code.toString(HEX).padStart(UNICODE_DIGITS, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    private const val HEX = 16
    private const val UNICODE_DIGITS = 4
}
