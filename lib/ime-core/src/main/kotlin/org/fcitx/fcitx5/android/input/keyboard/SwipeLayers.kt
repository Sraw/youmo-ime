/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * What a swipe up and a swipe down on a key reach (the 滑动 setting): a character key's corner
 * symbol, its shortcut, or nothing. The two ways never reach the same layer, so the seven
 * [entries] are all there are; a function key, with no symbol, takes its own shortcut either
 * way a layer is on.
 */
data class SwipeLayers(val up: Layer, val down: Layer) {

    enum class Layer { Symbol, Shortcut, Off }

    init {
        require(up == Layer.Off || up != down) { "both ways on $up" }
    }

    /** The layer a swipe of [totalY] steps reaches (down positive); [Layer.Off] for none. */
    fun layerOf(totalY: Int): Layer = when {
        totalY < 0 -> up
        totalY > 0 -> down
        else -> Layer.Off
    }

    val allOff: Boolean get() = up == Layer.Off && down == Layer.Off

    /** Which way swipes to [layer]: true up, false down, null neither. */
    fun wayOf(layer: Layer): Boolean? = when (layer) {
        Layer.Off -> null
        up -> true
        down -> false
        else -> null
    }

    companion object {
        val Default = SwipeLayers(Layer.Shortcut, Layer.Symbol)

        /** In the order the setting lists them, the default first. */
        val entries = listOf(
            Default,
            SwipeLayers(Layer.Symbol, Layer.Shortcut),
            SwipeLayers(Layer.Off, Layer.Symbol),
            SwipeLayers(Layer.Symbol, Layer.Off),
            SwipeLayers(Layer.Shortcut, Layer.Off),
            SwipeLayers(Layer.Off, Layer.Shortcut),
            SwipeLayers(Layer.Off, Layer.Off),
        )

        /**
         * The old setting's value (划动输入标点符号及数字: `Down`, `Up`, `Disabled`) as the new one: the
         * symbol the way it was, the shortcuts the other; turned off, both off, so a backspace swiped
         * up clears no field all of a sudden. Anything else is the default.
         */
        fun fromLegacy(value: String?): SwipeLayers = when (value) {
            "Up" -> SwipeLayers(Layer.Symbol, Layer.Shortcut)
            "Disabled" -> SwipeLayers(Layer.Off, Layer.Off)
            else -> Default
        }
    }
}
