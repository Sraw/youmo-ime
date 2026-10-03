/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.remote

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class HttpRemoteModelTest {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newSingleThreadExecutor()
    private val requests = ArrayList<String>()
    private val headers = ArrayList<String?>()
    private var answer = 200 to ""

    init {
        server.createContext("/") { exchange ->
            requests += exchange.requestMethod + " " + exchange.requestURI.path + " " +
                exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            headers += exchange.requestHeaders.getFirst("Authorization")
            val body = answer.second.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(answer.first, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    private fun model(token: String? = null, path: String = "") =
        HttpRemoteModel(URL("http://127.0.0.1:${server.address.port}$path"), token, executor, timeoutMillis = 2000)

    @After
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun scoresAreAskedForAsJsonAndReadBack() {
        answer = 200 to """{"scores": [-1.5, -20]}"""
        val scores = model("s3cret").score("他说\"好\"\n", listOf("在吗", "再吗")).get()
        assertArrayEquals(floatArrayOf(-1.5f, -20f), scores, 0f)
        assertEquals("""POST /score {"context":"他说\"好\"\u000a","candidates":["在吗","再吗"]}""", requests.single())
        assertEquals("Bearer s3cret", headers.single())
    }

    @Test
    fun candidatesLongerThanTheServerTakesAreNotSent() {
        val failed = model().score("", listOf("在吗", "在".repeat(HttpRemoteModel.MAX_CHARS + 1)))
        assertTrue(failed.isDone)
        assertThrows(ExecutionException::class.java) { failed.get() }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun theAddressMayHaveAPathAndATokenNeedNotBeSet() {
        answer = 200 to """{"scores": [-1, -2]}"""
        model(path = "/youmo/").score("你好", listOf("呀", "啊")).get()
        assertEquals("""POST /youmo/score {"context":"你好","candidates":["呀","啊"]}""", requests.single())
        assertEquals(null, headers.single())
    }

    @Test
    fun anythingButTheExpectedAnswerFails() {
        for (bad in listOf(
            401 to """{"error": "unauthorized"}""",
            200 to """{"scores": [-1.5]}""",
            200 to """{"scores": ["x", 1]}""",
            200 to """not json""",
            200 to """{"texts": [1]}""",
        )) {
            answer = bad
            val e = assertThrows(ExecutionException::class.java) {
                model().score("", listOf("a", "b")).get()
            }
            assertTrue(bad.toString(), e.cause is IOException)
        }
        answer = 200 to """{"scores": [${List(20000) { "-1.0" }.joinToString(",")}]}"""
        assertTrue(assertThrows(ExecutionException::class.java) { model().score("", listOf("a")).get() }.cause is IOException)
    }

    @Test
    fun aServerThatIsNotThereFailsAndIsLetBeAWhile() {
        val port = server.address.port
        server.stop(0)
        val clock = FakeClock()
        val model = HttpRemoteModel(URL("http://127.0.0.1:$port"), null, executor, timeoutMillis = 500, clock = clock)
        assertTrue(assertThrows(ExecutionException::class.java) { model.score("", listOf("a", "b")).get() }.cause is IOException)
        // not tried again: failed at once, as if it had been
        val next = model.score("", listOf("a", "b"))
        assertTrue(next.isDone)
        assertEquals("unreachable", assertThrows(ExecutionException::class.java) { next.get() }.cause?.message)
        clock.now = HttpRemoteModel.BACKOFF
        assertEquals("Connection refused", assertThrows(ExecutionException::class.java) { model.score("", listOf("a", "b")).get() }.cause?.message?.substringBefore(" ("))
    }

    @Test
    fun aRefusedTokenIsLetBeAWhileTooButNotByTheEvaluation() {
        answer = 401 to """{"error": "unauthorized"}"""
        val refused = model("wrong")
        assertThrows(ExecutionException::class.java) { refused.score("", listOf("a", "b")).get() }
        assertTrue(refused.score("", listOf("a", "b")).isDone)
        assertEquals(1, requests.size)
        val asksAll = HttpRemoteModel(URL("http://127.0.0.1:${server.address.port}"), "wrong", executor, backoff = 0)
        repeat(2) { assertThrows(ExecutionException::class.java) { asksAll.score("", listOf("a", "b")).get() } }
        assertEquals(3, requests.size)
    }

    @Test
    fun oneMadeBeforeTheUserTurnedItOffIsNotSent() {
        var on = true
        val model = HttpRemoteModel(URL("http://127.0.0.1:${server.address.port}"), null, executor, wanted = { on })
        on = false
        assertTrue(assertThrows(ExecutionException::class.java) { model.score("", listOf("a", "b")).get() }.cause is IOException)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun healthNamesTheModelWithTheToken() {
        answer = 200 to """{"model": "Qwen/Qwen3.5-4B-Base", "gpu_peak_gb": 3.9}"""
        assertEquals("Qwen/Qwen3.5-4B-Base", model("s3cret").health())
        assertEquals("GET /health ", requests.single())
        assertEquals("Bearer s3cret", headers.single())
        answer = 401 to """{"error": "unauthorized"}"""
        assertEquals(401, assertThrows(HttpRemoteModel.StatusException::class.java) { model("wrong").health() }.code)
    }

    @Test
    fun aWordPackComesNamedOrNotAtAll() {
        answer = 200 to """{"name": "2026q1", "text": "# youmo words 1\n搭子 da'zi -5.6\n"}"""
        val words = model("s3cret").words()!!
        assertEquals("2026q1", words.name)
        assertEquals("# youmo words 1\n搭子 da'zi -5.6\n", words.text)
        assertEquals("GET /words ", requests.single())
        assertEquals("Bearer s3cret", headers.single())
        // none on the server
        answer = 404 to """{"error": "no word pack"}"""
        assertNull(model().words())
        // a name that is no file name, or no text: not taken
        answer = 200 to """{"name": "../etc", "text": ""}"""
        assertThrows(IOException::class.java) { model().words() }
        answer = 200 to """{"name": "ok"}"""
        assertThrows(IOException::class.java) { model().words() }
        answer = 500 to ""
        assertEquals(500, assertThrows(HttpRemoteModel.StatusException::class.java) { model().words() }.code)
    }

    @Test
    fun onlyTheEndOfALongContextGoes() {
        answer = 200 to """{"scores": [-1, -2]}"""
        model().score("头" + "😀".repeat(64) + "尾", listOf("a", "b")).get()
        // 129 chars from the end would start inside 😀: one fewer
        assertEquals("""POST /score {"context":"${"😀".repeat(63)}尾","candidates":["a","b"]}""", requests.single())
    }

    @Test
    fun controlCharactersAreEscaped() {
        assertEquals("\"a\\u0001\\\\\"", HttpRemoteModel.quote("a\u0001\\"))
    }
}
