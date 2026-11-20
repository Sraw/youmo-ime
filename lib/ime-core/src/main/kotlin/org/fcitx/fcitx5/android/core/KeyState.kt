/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.host.KeyStates as Bits

infix fun UInt.or(other: KeyState): UInt = this or other.state

/** fcitx's modifier bits (`keysym.h`); the values are the host's [Bits]. */
@Suppress("EnumEntryName")
enum class KeyState(bits: Int) {
    NoState(0),
    Shift(Bits.SHIFT),
    CapsLock(Bits.CAPS_LOCK),
    Ctrl(Bits.CTRL),
    Alt(Bits.ALT),
    Ctrl_Shift(Bits.CTRL or Bits.SHIFT),
    NumLock(Bits.NUM_LOCK),
    Super(Bits.SUPER),
    Meta(Bits.META),
    Virtual(Bits.VIRTUAL),

    /** Key repetition. */
    Repeat(1 shl 31);

    val state: UInt = bits.toUInt()

    infix fun or(other: KeyState): UInt = state or other.state

    infix fun or(other: UInt): UInt = state or other
}

operator fun UInt.plus(other: KeyState) = or(other.state)
operator fun UInt.minus(other: KeyState) = and(other.state.inv())

@JvmInline
value class KeyStates(val states: UInt) {

    constructor(vararg states: KeyState) : this(mergeStates(states))

    fun has(state: KeyState) = (states and state.state) == state.state

    val alt get() = has(KeyState.Alt)
    val ctrl get() = has(KeyState.Ctrl)
    val shift get() = has(KeyState.Shift)
    val meta get() = has(KeyState.Meta)
    val numLock get() = has(KeyState.NumLock)
    val capsLock get() = has(KeyState.CapsLock)

    /** fcitx5-android only. */
    val virtual get() = has(KeyState.Virtual)

    fun toInt() = states.toInt()

    // the Android side (KeyStates.fromKeyEvent, metaState) is in :app
    companion object {
        val Empty = KeyStates(0u)

        val Virtual = KeyStates(KeyState.Virtual)

        fun of(v: Int) = KeyStates(v.toUInt())

        fun mergeStates(arr: Array<out KeyState>): UInt =
            arr.fold(KeyState.NoState.state) { acc, it -> acc or it.state }
    }
}
