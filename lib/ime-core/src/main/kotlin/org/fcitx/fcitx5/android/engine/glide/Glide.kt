/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

/** A glide as the keyboard hands it: [path] over [layout] ([GlidePath]); [shift] capitalises an English word's first letter. */
class Glide(val path: FloatArray, val layout: GlideLayout, val shift: Boolean = false, val capsLock: Boolean = false)
