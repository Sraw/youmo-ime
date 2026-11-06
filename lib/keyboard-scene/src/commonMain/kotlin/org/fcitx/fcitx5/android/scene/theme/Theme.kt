/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/**
 * A theme as theme-4.xsd writes it (`https://youmo-ime/theme/4`), its colour variables resolved
 * ([colors]: every one a colour, none naming one that is not there or itself).
 */
class Theme(
    val name: String,
    val dark: Boolean,
    val version: String,
    val colors: Map<String, Argb>,
    val styles: List<Style>,
    val fonts: Map<String, String>,
    val icons: Map<String, String>,
    val picture: Picture?,
) {
    /** [expr]'s colour here; transparent where it names what is not there (checked on reading). */
    fun color(expr: ColorExpr): Argb = expr.resolve(colors::get) ?: Argb.TRANSPARENT
}

/** A `<style>`: its selectors, its properties as read, and where it is (later wins a tie). */
class Style(val selectors: List<Selector>, val values: Map<String, Value>, val order: Int, val line: Int)

class Picture(val src: String, val brightness: Double, val blurDp: Double, val cuts: Map<String, String>)
