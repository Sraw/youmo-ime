/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.input.Contrast
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Selector
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
     * `<meta preview>`.
     */
    data class Files(
        val pictures: Set<String>,
        val source: String?,
        val images: Set<String>,
        val fonts: Set<String>,
        val icons: Set<String>,
        val preview: String?,
    ) {
        val all: Set<String> get() = pictures + images + fonts + icons + listOfNotNull(source, preview)
    }

    fun files(theme: Theme): Files = Files(
        pictures = theme.picture?.cuts?.values?.toSet().orEmpty(),
        source = theme.picture?.src,
        images = theme.styles.flatMap { it.values.values }.mapNotNull { ((it as? Value.OfFill)?.fill as? Fill.Image)?.file }.toSet(),
        fonts = theme.fonts.values.toSet(),
        icons = theme.icons.values.toSet(),
        preview = theme.meta?.preview,
    )

    /**
     * The keys without which nothing can be typed or put right: a theme hiding their labels (`hidden`)
     * leaves a keyboard of blank keys where those are, refused.
     */
    val REQUIRED_ROLES = setOf("shift", "backspace", "space", "enter")

    // the kinds those keys are of: a selector of another kind (letter, alt-text) never matches one
    private val REQUIRED_KINDS = setOf("function", "space", "enter")

    // what `hidden` hides of a key: its words and icon, not a mark or a hint alone
    private val HIDING_PARTS = setOf(null, "label", "icon")

    /**
     * The lines of the styles that hide the labels of a key of [REQUIRED_ROLES] (or may: `key { hidden }`). A
     * hide aimed at one key (Return on every board, by name) whose face is an image of its own (a fill=image style of that key)
     * is not: the image is what the key shows, as theme 3.0 drew a key with an image and its label hidden, and
     * a 3.0 theme the app takes must stay one it takes once converted.
     */
    fun hidesRequired(theme: Theme): List<Int> = theme.styles.filter { style ->
        (style.values["hidden"] as? Value.Flag)?.on == true && style.selectors.any { s ->
            s.widget == "key" && s.reserved == null && s.part in HIDING_PARTS && s.classes.all { it in REQUIRED_KINDS } &&
                s.roles.all { it in REQUIRED_ROLES } && (s.starRole == null || s.starRole in REQUIRED_ROLES) &&
                (s.key == null || s.key in REQUIRED_ROLES) && !imageFaced(theme, s)
        }
    }.map { it.line }

    // [s] names one key, and a style gives that very key (any board's as [s] says, any state's face but its
    // resting one aside) an image as its face
    private fun imageFaced(theme: Theme, s: Selector): Boolean = (s.key != null || s.starRole != null) &&
        theme.styles.any { style ->
            (style.values["fill"] as? Value.OfFill)?.fill is Fill.Image && style.selectors.any { o ->
                o.widget == "key" && o.part == null && o.states.isEmpty() && o.scenes.isEmpty() && o.classes.isEmpty() &&
                    o.roles.isEmpty() && o.board == s.board && o.key == s.key && o.starRole == s.starRole
            }
        }

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
