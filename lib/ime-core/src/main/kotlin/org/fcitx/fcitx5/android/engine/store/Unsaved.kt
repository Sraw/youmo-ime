/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.store

import java.io.IOException

/** Told to an `onError` when a user's change (a word added or blocked) lives in memory alone: the user is told, not only the log. */
class Unsaved(message: String, cause: Throwable? = null) : IOException(message, cause)
