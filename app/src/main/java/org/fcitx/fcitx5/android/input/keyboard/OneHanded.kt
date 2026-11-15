/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * The side 单手 puts the whole keyboard at ([SplitKeyboard.placement]), remembered (`one_handed_side`) for both
 * shapes. Named as 0.6.0's 单手模式 stored it (`one_handed`, Off as well), which KeyboardStyleMigration reads.
 */
enum class OneHanded(val dock: SplitKeyboard.Dock) {
    Right(SplitKeyboard.Dock.RIGHT),
    Left(SplitKeyboard.Dock.LEFT),
}
