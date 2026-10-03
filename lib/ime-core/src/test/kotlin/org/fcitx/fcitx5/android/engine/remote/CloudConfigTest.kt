/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CloudConfigTest {

    private val key = "0123456789ABCDEF".repeat(4)

    @Test
    fun overHttpsOnlyToTheKeyTheUserTrusted() {
        val config = CloudConfig().withServer(" https://example.com:8765 ").withToken("s3cret ")
        assertNull(config.target())
        // on with no key: nothing it may send to
        assertNull(config.on("").target())
        assertEquals(CloudConfig.Target("https://example.com:8765", "s3cret", key), config.on(key).target())
        assertNull(config.on(key).off().target())
    }

    @Test
    fun neverPlainHttpNotEvenToThisDevice() {
        assertNull(CloudConfig().withServer("http://127.0.0.1:8765").on(key).target())
        assertNull(CloudConfig().withServer("http://192.168.1.5:8765").on(key).target())
    }

    @Test
    fun aNewAddressIsOffAndItsKeyForgotten() {
        val on = CloudConfig().withServer("https://example.com").on(key)
        assertEquals(on, on.withServer("https://example.com "))
        val moved = on.withServer("https://example.org")
        assertFalse(moved.enabled)
        assertEquals("", moved.key)
        // a new token is the same server: still on
        assertEquals(key, on.withToken("other").target()?.key)
    }

    @Test
    fun aTokenThatCannotGoInAHeaderTurnsNothingOn() {
        val on = CloudConfig().withServer("https://example.com").on(key)
        for (token in listOf("长口令", "two words", "tab\there")) {
            assertFalse(token, CloudConfig.tokenValid(token))
            assertNull(token, on.withToken(token).target())
        }
    }
}
