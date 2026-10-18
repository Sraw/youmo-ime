/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

/**
 * A finger's glide across the letters, as the keyboard hands it to the engine: its [path] over
 * [layout] ([GlidePath]), and whether the shift was on ([shift], an English word's first letter a
 * capital) or locked ([capsLock]).
 */
class Glide(val path: FloatArray, val layout: GlideLayout, val shift: Boolean = false, val capsLock: Boolean = false)
