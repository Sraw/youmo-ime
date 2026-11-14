/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * What the nine-key keyboard ([T9Keyboard]) shows beside its keys: whether anything is typed, what its first
 * digits not yet taken may be, and which list that is (made of [org.fcitx.fcitx5.android.core.EngineState.SyllableState]).
 */
data class T9State(val composing: Boolean = false, val syllables: List<String> = emptyList(), val id: Int = 0)
