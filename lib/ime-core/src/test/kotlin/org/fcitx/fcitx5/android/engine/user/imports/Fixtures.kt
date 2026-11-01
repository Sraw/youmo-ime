/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user.imports

import java.security.MessageDigest

/** The files in `resources/libime` (see its `README.md`), and the digest the tests check what they make by. */
object Fixtures {

    fun resource(name: String): ByteArray =
        Fixtures::class.java.getResourceAsStream("/libime/$name")!!.use { it.readBytes() }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
