/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

/**
 * The look of a theme that sets none but its colours: a built-in one, Monet's, one of a file before
 * 3.0. As the default key style settings drew before they went into themes: blocks, 4 dp corners, a
 * 1 dp shadow, the standard gaps, a press tinted.
 */
object LookDefaults {

    val GAP = Gap(h = 3, v = 7, hLand = 3, vLand = 4)

    private val plain = KindStyle(KeyFill.Color, KeyPressed.Darken)

    val KEYS = Keys(
        blocks = true,
        radiusDp = 4,
        borderDp = 0,
        shadowDp = 1,
        gap = GAP,
        press = PressEffect.Tint,
        letter = plain,
        function = plain,
        space = plain,
        enter = plain,
        overrides = emptyMap(),
    )

    val TEXT = Text(keyFont = null, candidateFont = null, keyWeight = 400, candidateWeight = 400, keyScale = 100)

    /** [palette]'s look, with [picture] under the keyboard if there is one. */
    fun look(palette: Palette, picture: Picture? = null) = ThemeLook(
        palette = palette,
        picture = picture,
        base = if (picture != null) BoardBase.Picture else BoardBase.Color,
        bar = BarBase.Transparent,
        keys = KEYS,
        text = TEXT,
    )
}

/**
 * The key style settings before 3.0 (global, `key_border`, `key_radius`…) as a theme's keys, for
 * the themes they drew. The two hidden radii of the editing buttons and clipboard entries are not
 * here: they follow the keys' now.
 */
data class LegacyLook(
    val keyBorder: Boolean = true,
    val keyBorderStroke: Boolean = false,
    val keyRippleEffect: Boolean = false,
    val keyRadius: Int = 4,
    val hMargin: Int = 3,
    val hMarginLandscape: Int = 3,
    val vMargin: Int = 7,
    val vMarginLandscape: Int = 4,
) {
    /** What they set: a stroke was 1 dp and no shadow; out of the theme's ranges, kept in them. */
    fun keys(base: Keys = LookDefaults.KEYS): Keys = base.copy(
        blocks = keyBorder,
        borderDp = if (keyBorderStroke) 1 else 0,
        shadowDp = if (keyBorderStroke) 0 else 1,
        press = if (keyRippleEffect) PressEffect.Ripple else PressEffect.Tint,
        radiusDp = keyRadius.coerceIn(0, Keys.MAX_RADIUS_DP),
        gap = Gap(
            h = hMargin.coerceIn(0, Gap.MAX_GAP_DP),
            v = vMargin.coerceIn(0, Gap.MAX_GAP_DP),
            hLand = hMarginLandscape.coerceIn(0, Gap.MAX_GAP_DP),
            vLand = vMarginLandscape.coerceIn(0, Gap.MAX_GAP_DP),
        ),
    )

    /** [look] with these keys; a flat keyboard's bar had the bar colour, a block one's showed the keyboard's. */
    fun applyTo(look: ThemeLook): ThemeLook = look.copy(
        keys = keys(look.keys),
        bar = if (!keyBorder && look.bar == BarBase.Transparent) BarBase.Color else look.bar,
    )

    /** Whether they draw as the defaults do: then a theme is left as it is. */
    val isDefault get() = keys() == LookDefaults.KEYS
}
