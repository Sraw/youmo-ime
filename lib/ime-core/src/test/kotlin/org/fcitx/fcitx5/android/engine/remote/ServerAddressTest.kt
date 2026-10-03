/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.remote

import org.fcitx.fcitx5.android.engine.remote.ServerAddress.Invalid
import org.fcitx.fcitx5.android.engine.remote.ServerAddress.Valid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {

    private fun check(address: String) = ServerAddress.check(address)

    @Test
    fun httpsGoesAnywhere() {
        assertTrue(check("https://example.com:8765") is Valid)
        assertTrue(check(" https://192.168.1.5:8765/ ") is Valid)
        assertTrue(check("https://desktop.local:8765/youmo") is Valid)
    }

    @Test
    fun neverPlainHttpOnThePhone() {
        for (http in listOf("http://127.0.0.1:8765", "http://192.168.1.5:8765", "http://localhost")) {
            assertEquals(http, Invalid.NOT_HTTPS, check(http))
        }
    }

    @Test
    fun theEvaluationMayUsePlainHttpToThisDeviceOnly() {
        assertTrue(ServerAddress.check("http://127.0.0.1:8765", plainLoopback = true) is Valid)
        // a private address or a .local name is whoever answers for it on the network the phone is on
        for (other in listOf(
            "http://192.168.1.5:8765", "http://10.0.0.2", "http://desktop.local", "http://localhost:8765",
            "http://0127.0.0.1", "http://127.0.0.01", "http://127.1.2.3", "http://127.0.1", "http://[::1]",
            "http://8.8.8.8", "http://127.0.0.1.example.com",
        )) {
            assertEquals(other, Invalid.NOT_LOOPBACK, ServerAddress.check(other, plainLoopback = true))
        }
    }

    @Test
    fun anythingElseIsRefused() {
        assertEquals(Invalid.SCHEME, check("ftp://127.0.0.1"))
        assertEquals(Invalid.MALFORMED, check("192.168.1.5:8765"))
        assertEquals(Invalid.MALFORMED, check("https://"))
        assertEquals(Invalid.MALFORMED, check("https://user:pw@example.com"))
        assertEquals(Invalid.MALFORMED, check("https://example.com/?q=1"))
        assertEquals(Invalid.MALFORMED, check("https://example.com/#x"))
        assertEquals(Invalid.MALFORMED, check("https://example.com:65536"))
        assertEquals(Invalid.MALFORMED, check("https://example.com:0"))
        assertTrue(check("https://example.com:65535") is Valid)
    }
}
