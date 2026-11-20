/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/** The Chinese pages of a CommonCrawl crawl's WET files (under its terms of use): Chinese first by its detection, [MIN_HAN] Han characters or more. */
object CommonCrawl {

    const val BASE = "https://data.commoncrawl.org/"

    /** WET files a shard: 100 hold some 150 million Han characters, 230 MB of parquet. */
    const val FILES_PER_SHARD = 100

    /** Writes the Chinese pages of [files] WET files to [outDir] in shards; a whole shard already there is kept, so a stopped run goes on. */
    fun extract(crawl: String, from: Int, files: Int, outDir: File, base: String = BASE, log: (String) -> Unit) {
        // the pool's threads tell of their retries too
        val say: (String) -> Unit = { line -> synchronized(log) { log(line) } }
        val paths = GZIPInputStream(ByteArrayInputStream(fetch(base, "crawl-data/$crawl/wet.paths.gz", say)))
            .bufferedReader().readLines().filter(String::isNotBlank)
        require(from >= 0 && files > 0 && from + files <= paths.size) { "$crawl has ${paths.size} WET files, not $from to ${from + files}" }
        if (!outDir.isDirectory && !outDir.mkdirs()) throw IOException("cannot make $outDir")
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            // a shard's files are fetched while the one before is written, so neither the threads nor the network idle
            var pending: Pair<File, List<Future<ArrayList<WebText.Page>>>>? = null
            for (first in from until from + files step FILES_PER_SHARD) {
                val until = minOf(first + FILES_PER_SHARD, from + files)
                val shard = File(outDir, "%s-%05d-%05d.parquet".format(Locale.ROOT, crawl, first, until))
                if (shard.exists()) {
                    say("${shard.name}: there already")
                    continue
                }
                val fetched = (first until until).map { i ->
                    pool.submit(Callable { ArrayList<WebText.Page>().also { list -> pages(ByteArrayInputStream(fetch(base, paths[i], say)), list::add) } })
                }
                pending?.let { (file, its) -> write(file, its, say) }
                pending = shard to fetched
            }
            pending?.let { (file, its) -> write(file, its, say) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Writes the pages of a shard's [fetched] files to [shard], in the files' order, once all are fetched. */
    private fun write(shard: File, fetched: List<Future<ArrayList<WebText.Page>>>, log: (String) -> Unit) {
        val pages = fetched.flatMap { future ->
            try {
                future.get()
            } catch (e: ExecutionException) {
                throw IOException(e.cause?.message, e)
            }
        }
        WebText.write(shard, pages)
        log("${shard.name}: ${fetched.size} files, ${pages.size} pages, ${pages.sumOf { p -> p.text.count { TextChars.isHan(it) } }} Han characters")
    }

    /** The Chinese pages of a WET file; [wet]'s `available()` must be true, as GZIPInputStream reads past a member only then. */
    fun pages(wet: InputStream, page: (WebText.Page) -> Unit) {
        val input = BufferedInputStream(GZIPInputStream(wet, BUFFER), BUFFER)
        while (true) {
            val headers = headers(input) ?: return
            val length = headers["content-length"]?.toIntOrNull() ?: throw IOException("a WARC record without its length")
            val content = input.readNBytes(length)
            if (content.size < length) throw IOException("a WARC record cut short")
            if (headers["warc-type"] != "conversion" || !headers["warc-identified-content-language"].orEmpty().startsWith("zho")) continue
            val text = String(content, Charsets.UTF_8)
            if (text.count { TextChars.isHan(it) } >= MIN_HAN) page(WebText.Page(headers["warc-target-uri"].orEmpty(), text, headers["warc-date"].orEmpty()))
        }
    }

    /** A record's headers by their names in lower case, the blank lines before it skipped; null at the end. */
    private fun headers(input: InputStream): Map<String, String>? {
        var first = line(input) ?: return null
        while (first.isEmpty()) first = line(input) ?: return null
        if (!first.startsWith("WARC/")) throw IOException("not a WARC record: ${first.take(40)}")
        val headers = HashMap<String, String>()
        while (true) {
            val line = line(input) ?: throw IOException("a WARC record cut short")
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }

    private fun line(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8)
            if (b == '\n'.code) return bytes.toString(Charsets.UTF_8).removeSuffix("\r")
            bytes.write(b)
        }
    }

    private val client: HttpClient by lazy {
        // HTTP/1.1: over HTTP/2 all requests to one host share one connection's bandwidth
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
            .followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    /** A whole file, so the gzip members are read from memory; each pause before another try is told to [log]. */
    internal fun fetch(base: String, path: String, log: (String) -> Unit, sleep: (Long) -> Unit = Thread::sleep): ByteArray {
        if (!base.startsWith("http")) return File(base, path).readBytes()
        val request = HttpRequest.newBuilder(URI(base + path)).timeout(Duration.ofMinutes(FETCH_MINUTES)).build()
        var failure: IOException? = null
        for (attempt in 1..TRIES) {
            val status = try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() == OK) return response.body()
                response.statusCode()
            } catch (e: IOException) {
                failure = e
                0
            }
            // 503 and 429 slow a client down and are retried; a 404 and the like will not mend
            if (status != 0) {
                failure = IOException("$path: HTTP $status")
                if (!passing(status)) throw failure
            }
            // doubling, at random within half of it: threads slowed together must not come back together
            if (attempt < TRIES) {
                val least = minOf(PAUSE_MILLIS shl (attempt - 1), MAX_PAUSE_MILLIS)
                val pause = least + Random.nextLong(least / 2)
                log("$path: ${if (status != 0) "HTTP $status" else failure}, try ${attempt + 1} of $TRIES in ${Duration.ofMillis(pause).toSeconds()} s")
                sleep(pause)
            }
        }
        throw failure ?: IOException("$path: not fetched")
    }

    private fun passing(status: Int) = status == SLOW_DOWN || status == TOO_MANY || status >= SERVER_ERROR

    private const val MIN_HAN = 50
    private const val THREADS = 8
    private const val TRIES = 8
    private const val PAUSE_MILLIS = 10_000L
    private const val MAX_PAUSE_MILLIS = 300_000L
    private const val CONNECT_SECONDS = 30L
    private const val FETCH_MINUTES = 5L
    private const val BUFFER = 1 shl 16
    private const val OK = 200
    private const val TOO_MANY = 429
    private const val SERVER_ERROR = 500
    private const val SLOW_DOWN = 503
}
