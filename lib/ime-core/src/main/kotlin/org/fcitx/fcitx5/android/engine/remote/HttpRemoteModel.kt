/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.remote

import org.fcitx.fcitx5.android.engine.rerank.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * [RemoteModel] over `cloud/server.py`'s HTTP API, each request run on [executor]. [token], if
 * the server was started with one, goes with every request; over HTTPS, [key] is the only key
 * the server may have ([ServerKey.pinned]), or with none, the system's authorities vouch for it.
 * [timeoutMillis] bounds connecting and reading: past it the answer is no use to anyone, and the
 * thread is freed for the next one. [wanted] is asked as each request's turn comes: one made
 * before the user turned the server off is not sent after. A server that cannot be reached is
 * not tried again for [backoff] (nanoseconds, by [clock]): each try is a radio woken for nothing;
 * nor is one that refuses the token.
 */
class HttpRemoteModel(
    private val server: URL,
    private val token: String?,
    private val executor: Executor,
    private val key: String? = null,
    private val timeoutMillis: Int = TIMEOUT_MILLIS,
    private val wanted: () -> Boolean = { true },
    private val clock: () -> Long = System::nanoTime,
    private val backoff: Long = BACKOFF,
) : RemoteModel {

    /** An answer other than 200, by its [code]. */
    class StatusException(val code: Int) : IOException("HTTP $code")

    private val pinned by lazy { key?.let { ServerKey.pinned(it) } }

    @Volatile private var unreachableUntil: Long? = null

    override fun score(context: String, candidates: List<String>): Future<FloatArray> =
        // the server takes none longer (server.py MAX_CHARS): not asked, it is the same as no answer
        if (candidates.any { it.length > MAX_CHARS }) failed("too long")
        else submit("/score", """{"context":${quote(sent(context))},"candidates":[${candidates.joinToString(",") { quote(it) }}]}""") { body ->
            val scores = (body as? Map<*, *>)?.get("scores") as? List<*>
            if (scores == null || scores.size != candidates.size) throw IOException("bad answer")
            FloatArray(scores.size) { (scores[it] as? Double)?.toFloat() ?: throw IOException("bad score") }
        }

    /** What of [context] goes: its last [CONTEXT] chars, as the settings tell the user. */
    private fun sent(context: String): String {
        if (context.length <= CONTEXT) return context
        val from = context.length - CONTEXT
        return context.substring(if (Character.isLowSurrogate(context[from])) from + 1 else from)
    }

    /** The server's model, as `/health` names it: whether the address, key and token all work. Blocks. */
    fun health(): String =
        (send("/health", null) as? Map<*, *>)?.get("model") as? String ?: throw IOException("bad answer")

    private fun <T> submit(path: String, request: String, read: (Any?) -> T): Future<T> {
        if (unreachableUntil?.let { clock() - it < 0 } == true) return failed("unreachable")
        return FutureTask {
            if (!wanted()) throw IOException("turned off")
            read(send(path, request))
        }.also(executor::execute)
    }

    private fun <T> failed(why: String): Future<T> = FutureTask<T> { throw IOException(why) }.apply { run() }

    private fun open(path: String): HttpURLConnection {
        // never a proxy: the user's server is theirs, and a proxy (one a network's PAC sets, say) would be on the way
        val connection = URL(server.toString().trimEnd('/') + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        val (factory, names) = pinned ?: return connection
        (connection as? HttpsURLConnection)?.apply {
            sslSocketFactory = factory
            hostnameVerifier = names
        } ?: run {
            connection.disconnect()
            throw IOException("a key, and not https")
        }
        return connection
    }

    /** POSTs [request], or with none GETs. */
    private fun send(path: String, request: String?): Any? {
        val connection = open(path)
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = false // nowhere but where the user said
            connection.useCaches = false
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            val bytes = request?.toByteArray(Charsets.UTF_8)
            if (bytes != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
            }
            // set up before connecting: none of it can be changed after
            connect(connection)
            if (bytes != null) connection.outputStream.use { it.write(bytes) }
            return answer(connection)
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
        // read to its end, the connection is kept for the next question: a new one for each, TLS
        // handshake and all, took the whole 300 ms the phone waits (on an emulator, to the host)
    }

    private fun connect(connection: HttpURLConnection) {
        try {
            connection.connect()
        } catch (e: IOException) {
            letBe()
            throw e
        }
        unreachableUntil = null
    }

    private fun letBe() {
        if (backoff > 0) unreachableUntil = clock() + backoff
    }

    private fun answer(connection: HttpURLConnection): Any? {
        val code = connection.responseCode
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED) letBe()
        if (code != HttpURLConnection.HTTP_OK) throw StatusException(code)
        val body = connection.inputStream.use { String(it.readBytes(MAX_ANSWER), Charsets.UTF_8) }
        return try {
            Json.parse(body)
        } catch (e: IllegalArgumentException) {
            throw IOException("bad answer", e)
        }
    }

    private fun InputStream.readBytes(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > limit) throw IOException("answer over $limit bytes")
            out.write(buffer, 0, n)
        }
    }

    companion object {
        /** Past this the answer would come after the user has moved on (see [RemoteRefiner]). */
        const val TIMEOUT_MILLIS = 2000
        /** How long a server that could not be reached is let be, in nanoseconds. */
        val BACKOFF = TimeUnit.SECONDS.toNanos(30)
        const val CONTEXT = 128
        const val MAX_CHARS = 64
        private const val MAX_ANSWER = 1 shl 16
        private const val BUFFER = 4096

        /** [s] as a JSON string. */
        fun quote(s: String): String = buildString(s.length + 2) {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
