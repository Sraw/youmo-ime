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
import org.fcitx.fcitx5.android.scene.theme.Values

/** What an import asks of a theme 4.0 pack beyond the engine's own checks: named files, blank keys, hard-to-read keys. */
object ThemePackCheck {

    /** Each read as what it is before taken: [pictures] the picture's source photo, [effects] the press effects' images. */
    data class Files(
        val pictures: Set<String>,
        val source: String?,
        val images: Set<String>,
        val fonts: Set<String>,
        val icons: Set<String>,
        val preview: String?,
        val effects: Set<String> = emptySet(),
        /** Of a class this version does not know too: kept, checked, exported all the same. */
        val sounds: Set<String> = emptySet(),
        /** The dark version's picture: its source and cuts. */
        val darkPictures: Set<String> = emptySet(),
        val stickers: Set<String> = emptySet(),
    ) {
        /** What a pack must hold: a sticker it lacks is drawn as its stand-in, and a 0.6.2 pack's reserved one was never copied. */
        val required: Set<String> get() = pictures + images + fonts + icons + effects + sounds + darkPictures + listOfNotNull(source, preview)

        /** What is placed and exported where it is there. */
        val all: Set<String> get() = required + stickers
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
        darkPictures = theme.darkPicture?.let { it.cuts.values + it.src }?.toSet().orEmpty(),
        stickers = theme.styles.mapNotNull { (it.values["sticker"] as? Value.Word)?.word?.takeIf { w -> w != Values.NONE } }.toSet(),
    )

    val REQUIRED_ROLES = BlankKeys.REQUIRED_ROLES

    /** The first found, on the app's and the pack's boards; [BlankKeys.find] asks the engine's own drawing. */
    fun hidesRequired(theme: Theme, boards: List<BlankKeys.Board>): List<BlankKeys.Blank> = BlankKeys.find(theme, boards)

    /** WCAG's for large text: under it, a warning. */
    const val MIN_CONTRAST = 3.0

    /** Light (false) and dark (true) versions under [MIN_CONTRAST]: warned of, not refused. */
    fun lowContrast(theme: Theme): List<Boolean> = theme.variants.sorted().filter { dark ->
        val colors = theme.variant(dark).colors
        val text = colors["keyText"] ?: return@filter false
        val key = colors["key"] ?: return@filter false
        Contrast.ratio(text.value, key.value) < MIN_CONTRAST
    }
}
