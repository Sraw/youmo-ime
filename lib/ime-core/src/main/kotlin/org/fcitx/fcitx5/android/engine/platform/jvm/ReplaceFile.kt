/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform.jvm

import java.io.File
import java.io.IOException

/**
 * [org.fcitx.fcitx5.android.engine.store.replaceFile] of files of the platform's file system, for
 * the app's own files (a custom phrases file, the host's config): [dest] replaced with what
 * [write] puts in [temp], whole or not at all, no [temp] left behind.
 *
 * @throws IOException if [dest] could not be replaced
 */
inline fun replaceFile(dest: File, temp: File, write: (File) -> Unit) =
    org.fcitx.fcitx5.android.engine.store.replaceFile(JvmFile(dest), JvmFile(temp)) { write(temp) }
