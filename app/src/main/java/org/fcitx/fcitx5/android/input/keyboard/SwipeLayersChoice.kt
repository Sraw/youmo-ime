/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers.Layer

/** The 滑动 setting's seven choices ([SwipeLayers.entries]), stored by name. */
enum class SwipeLayersChoice(override val stringRes: Int, val layers: SwipeLayers) : ManagedPreferenceEnum {
    ShortcutUpSymbolDown(R.string.swipe_shortcut_up_symbol_down, SwipeLayers(Layer.Shortcut, Layer.Symbol)),
    SymbolUpShortcutDown(R.string.swipe_symbol_up_shortcut_down, SwipeLayers(Layer.Symbol, Layer.Shortcut)),
    SymbolDownOnly(R.string.swipe_symbol_down_only, SwipeLayers(Layer.Off, Layer.Symbol)),
    SymbolUpOnly(R.string.swipe_symbol_up_only, SwipeLayers(Layer.Symbol, Layer.Off)),
    ShortcutUpOnly(R.string.swipe_shortcut_up_only, SwipeLayers(Layer.Shortcut, Layer.Off)),
    ShortcutDownOnly(R.string.swipe_shortcut_down_only, SwipeLayers(Layer.Off, Layer.Shortcut)),
    Off(R.string.swipe_off, SwipeLayers(Layer.Off, Layer.Off));

    companion object {
        /** The choice of [layers]: every one of the seven has one. */
        fun of(layers: SwipeLayers): SwipeLayersChoice = entries.first { it.layers == layers }
    }
}
