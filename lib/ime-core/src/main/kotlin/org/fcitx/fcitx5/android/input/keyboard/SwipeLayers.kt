/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** 滑动: up and down never reach the same layer, so [entries] are all; a function key takes its shortcut whichever way is on. */
data class SwipeLayers(val up: Layer, val down: Layer) {

    enum class Layer { Symbol, Shortcut, Off }

    init {
        require(up == Layer.Off || up != down) { "both ways on $up" }
    }

    /** Down positive. */
    fun layerOf(totalY: Int): Layer = when {
        totalY < 0 -> up
        totalY > 0 -> down
        else -> Layer.Off
    }

    val allOff: Boolean get() = up == Layer.Off && down == Layer.Off

    companion object {
        val Default = SwipeLayers(Layer.Shortcut, Layer.Symbol)

        /** The default first. */
        val entries = listOf(
            Default,
            SwipeLayers(Layer.Symbol, Layer.Shortcut),
            SwipeLayers(Layer.Off, Layer.Symbol),
            SwipeLayers(Layer.Symbol, Layer.Off),
            SwipeLayers(Layer.Shortcut, Layer.Off),
            SwipeLayers(Layer.Off, Layer.Shortcut),
            SwipeLayers(Layer.Off, Layer.Off),
        )

        /** The old `Down`/`Up`/`Disabled`: symbol that way, shortcuts the other; disabled, both off (a swiped backspace must not start clearing fields). */
        fun fromLegacy(value: String?): SwipeLayers = when (value) {
            "Up" -> SwipeLayers(Layer.Symbol, Layer.Shortcut)
            "Disabled" -> SwipeLayers(Layer.Off, Layer.Off)
            else -> Default
        }
    }
}
