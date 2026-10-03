/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.remote

import java.net.MalformedURLException
import java.net.URL

/**
 * Where the user's server is. What is typed goes there, so over HTTPS only, to the key the user
 * trusted ([ServerKey]). Not plain HTTP even to this device: when `adb reverse` or the server on
 * the phone stops, any app may take its port, and would be sent the token and the text. A private
 * address is no better: on a café's Wi-Fi, 192.168.1.5 is whoever took it.
 */
object ServerAddress {

    sealed interface Checked
    data class Valid(val url: URL) : Checked
    enum class Invalid : Checked { MALFORMED, SCHEME, NOT_HTTPS, NOT_LOOPBACK }

    /**
     * [plainLoopback]: plain HTTP to [LOOPBACK] too, for the evaluation run beside its server
     * (`server.py --plain`); never the phone's.
     */
    fun check(address: String, plainLoopback: Boolean = false): Checked {
        val url = try {
            URL(address.trim())
        } catch (_: MalformedURLException) {
            return Invalid.MALFORMED
        }
        // a user name, a query or a fragment has no place in it: a mistake, or a way to hide where it goes
        val extras = listOf(url.userInfo, url.query, url.ref)
        // URL takes any number: past 65535 the connection would fail with no IOException
        val portValid = url.port == -1 || url.port in 1..MAX_PORT
        if (url.host.isNullOrEmpty() || extras.any { it != null } || !portValid) return Invalid.MALFORMED
        return when (url.protocol) {
            "https" -> Valid(url)
            "http" -> when {
                !plainLoopback -> Invalid.NOT_HTTPS
                // not "localhost", which a resolver could send elsewhere
                url.host == LOOPBACK -> Valid(url)
                else -> Invalid.NOT_LOOPBACK
            }
            else -> Invalid.SCHEME
        }
    }

    private const val MAX_PORT = 65535

    const val LOOPBACK = "127.0.0.1"
}
