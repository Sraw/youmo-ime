/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.store

import java.io.IOException

/**
 * What an `onError` hears when what the user changed (a word added or blocked by hand) did not reach
 * the disk and lives in memory alone, gone with the process: told to the user as what is learned and
 * not kept is (Engines' `unkept`), not only logged (0.6.1 audit decode-2).
 */
class Unsaved(message: String, cause: Throwable? = null) : IOException(message, cause)
