/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.remote

import java.net.URL

/**
 * The cloud build's settings for the user's server, and what they allow. Text goes out only
 * while the user has it on for exactly this address and, over HTTPS, only to the [key] they
 * compared with what the server printed: a new address is off again, its key forgotten.
 */
data class CloudConfig(
    val enabled: Boolean = false,
    val server: String = "",
    val token: String = "",
    /** [ServerKey.fingerprint] of the server's key, once the user has trusted it. */
    val key: String = "",
) {
    /** Where questions go. By [address], not a URL: URL.equals looks the host up, and targets are compared on the fcitx thread. */
    data class Target(val address: String, val token: String?, val key: String) {
        val url get() = URL(address)
    }

    /** Where questions go now, or null: off, or nowhere it may send to. */
    fun target(): Target? {
        if (!enabled || !tokenValid(token)) return null
        val url = (ServerAddress.check(server) as? ServerAddress.Valid)?.url ?: return null
        val pin = ServerKey.normalized(key) ?: return null
        return Target(url.toString(), token.ifEmpty { null }, pin)
    }

    fun withServer(address: String): CloudConfig =
        if (address.trim() == server) this else copy(enabled = false, server = address.trim(), key = "")

    fun withToken(token: String) = copy(token = token.trim())

    /** On, the user having trusted [key]. */
    fun on(key: String) = copy(enabled = true, key = key)

    fun off() = copy(enabled = false)

    companion object {
        /** What can go in an HTTP header as it is: printable ASCII, no spaces. */
        fun tokenValid(token: String) = token.all { it in '!'..'~' }
    }
}
