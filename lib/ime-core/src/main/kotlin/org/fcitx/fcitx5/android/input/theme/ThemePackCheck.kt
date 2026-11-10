/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.input.Contrast
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.Value

/**
 * What an import asks of a theme 4.0 pack the engine has read (060-render-p3 T8a), beside what the
 * engine checks itself: the files it names, keys it would leave blank, and keys hard to read.
 */
object ThemePackCheck {

    /**
     * The files [theme] names, each by what it is: read as that before it is taken. [images] the
     * nine-slice key images of its styles, [pictures] its picture's source photo apart, [preview] its
     * `<meta preview>`, [effects] the images its keys' press effects play (060-render-p3 A3).
     */
    data class Files(
        val pictures: Set<String>,
        val source: String?,
        val images: Set<String>,
        val fonts: Set<String>,
        val icons: Set<String>,
        val preview: String?,
        val effects: Set<String> = emptySet(),
        /** Every `<sound>`'s file, of a class this version does not know too: kept, checked, exported all the same. */
        val sounds: Set<String> = emptySet(),
    ) {
        val all: Set<String> get() = pictures + images + fonts + icons + effects + sounds + listOfNotNull(source, preview)
    }

    fun files(theme: Theme): Files = Files(
        pictures = theme.picture?.cuts?.values?.toSet().orEmpty(),
        source = theme.picture?.src,
        images = theme.styles.flatMap { it.values.values }.mapNotNull { ((it as? Value.OfFill)?.fill as? Fill.Image)?.file }.toSet(),
        fonts = theme.fonts.values.toSet(),
        icons = theme.icons.values.toSet(),
        preview = theme.meta?.preview,
        effects = theme.styles.flatMap { it.values.values }.mapNotNull { (it as? Value.OfEffect)?.effect?.file }.toSet(),
        sounds = theme.soundFiles.map { it.second }.toSet(),
    )

    /** The keys a theme must not hide the labels of: [BlankKeys.REQUIRED_ROLES]. */
    val REQUIRED_ROLES = BlankKeys.REQUIRED_ROLES

    /**
     * Where [theme] would leave those keys blank on [boards] (the app's and the pack's own), the first found:
     * [BlankKeys.find], the engine's own drawing asked, the theme maker's rule too.
     */
    fun hidesRequired(theme: Theme, boards: List<BlankKeys.Board>): List<BlankKeys.Blank> = BlankKeys.find(theme, boards)

    /** The least contrast between a key's words and its face a theme is let in without a warning (WCAG's for large text). */
    const val MIN_CONTRAST = 3.0

    /**
     * The versions of [theme] (false light, true dark) whose `keyText` on `key` is under [MIN_CONTRAST]:
     * warned of, not refused (§16).
     */
    fun lowContrast(theme: Theme): List<Boolean> = theme.variants.sorted().filter { dark ->
        val colors = theme.variant(dark).colors
        val text = colors["keyText"] ?: return@filter false
        val key = colors["key"] ?: return@filter false
        Contrast.ratio(text.value, key.value) < MIN_CONTRAST
    }
}
