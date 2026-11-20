/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.keyboard

data class T9State(val composing: Boolean = false, val syllables: List<String> = emptyList(), val id: Int = 0)
