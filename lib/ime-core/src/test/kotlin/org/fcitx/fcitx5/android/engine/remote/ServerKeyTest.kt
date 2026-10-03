/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.remote

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URL
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class ServerKeyTest {

    // a self-signed certificate, as cloud/server.py makes one: no authority vouches for it
    private val keys = KeyStore.getInstance("PKCS12").apply {
        ServerKeyTest::class.java.getResourceAsStream("/remote/test-server.p12")!!.use { load(it, PASSWORD) }
    }
    private val certificate = keys.getCertificate("test") as X509Certificate
    private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newSingleThreadExecutor()
    private var asked = 0

    init {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASSWORD) }
        server.httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) })
        server.createContext("/") { exchange ->
            asked++
            exchange.requestBody.readBytes()
            val body = """{"scores": [-1, -2]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    private val url = URL("https://127.0.0.1:${server.address.port}")

    @After
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun theKeyTheServerShowsIsWhatItIsTrustedBy() {
        val key = ServerKey.fetch(url, 2000)
        assertEquals(ServerKey.fingerprint(certificate), key)
        assertEquals(0, asked)
        val scores = HttpRemoteModel(url, null, executor, key).score("", listOf("在吗", "再吗")).get()
        assertArrayEquals(floatArrayOf(-1f, -2f), scores, 0f)
        assertEquals(1, asked)
    }

    @Test
    fun anyOtherKeyOrNoneIsRefusedBeforeAnythingIsSent() {
        val other = "AB".repeat(32)
        for (model in listOf(HttpRemoteModel(url, "s3cret", executor, other), HttpRemoteModel(url, "s3cret", executor))) {
            assertThrows(ExecutionException::class.java) { model.score("", listOf("在吗", "再吗")).get() }
        }
        assertEquals(0, asked)
    }

    @Test
    fun aFingerprintIsSixtyFourHexDigitsAsTheUserMayWriteThem() {
        val key = ServerKey.fingerprint(certificate)
        assertTrue(key.matches(Regex("[0-9A-F]{64}")))
        assertEquals(key, ServerKey.normalized(ServerKey.grouped(key).lowercase()))
        assertEquals(16, ServerKey.grouped(key).split(' ').size)
        assertNull(ServerKey.normalized(key.drop(1)))
        assertNull(ServerKey.normalized(key.dropLast(1) + "G"))
    }

    private companion object {
        val PASSWORD = "changeit".toCharArray()
    }
}
