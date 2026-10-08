/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

/** [name], joined to a directory, names a file directly inside it, not a path. */
internal fun isPlainFileName(name: String) =
    name.isNotEmpty() && name != "." && name != ".." && '/' !in name
