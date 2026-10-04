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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * The Chinese pages of a CommonCrawl crawl (commoncrawl.org, under its terms of use), out of its
 * WET files: the text CommonCrawl took out of each page it fetched. Newer than FineWeb-2, whose
 * dumps end in 2024: a crawl of the last months has the words of the last months
 * (dev/TRAINING-PLAN.md 11.7d). A page is taken when CommonCrawl's own language detection has
 * Chinese first and it holds [MIN_HAN] Han characters or more; its boilerplate and spam are
 * [PageCleaner]'s to take out.
 */
object CommonCrawl {

    const val BASE = "https://data.commoncrawl.org/"

    /** WET files a shard: 100 hold some 150 million Han characters, 230 MB of parquet. */
    const val FILES_PER_SHARD = 100

    /**
     * Writes the Chinese pages of WET files [from] until [from] + [files] of [crawl], in the order
     * of its `wet.paths.gz`, to [outDir]: a shard of [FILES_PER_SHARD] files each,
     * `<crawl>-<first file>.parquet`, its pages in the files' order. A shard already there is
     * kept, so a run stopped halfway goes on where it was. [base] is CommonCrawl's, or for a test
     * a directory laid out as it.
     * @throws IOException if a file cannot be fetched after some tries, or is not WARC
     */
    fun extract(crawl: String, from: Int, files: Int, outDir: File, base: String = BASE, log: (String) -> Unit) {
        val paths = GZIPInputStream(ByteArrayInputStream(fetch(base, "crawl-data/$crawl/wet.paths.gz")))
            .bufferedReader().readLines().filter(String::isNotBlank)
        require(from >= 0 && files > 0 && from + files <= paths.size) { "$crawl has ${paths.size} WET files, not $from to ${from + files}" }
        if (!outDir.isDirectory && !outDir.mkdirs()) throw IOException("cannot make $outDir")
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            for (first in from until from + files step FILES_PER_SHARD) {
                val shard = File(outDir, "%s-%05d.parquet".format(Locale.ROOT, crawl, first))
                if (shard.exists()) {
                    log("${shard.name}: there already")
                    continue
                }
                val until = minOf(first + FILES_PER_SHARD, from + files)
                val fetched = (first until until).map { i ->
                    pool.submit(Callable { ArrayList<WebText.Page>().also { list -> pages(ByteArrayInputStream(fetch(base, paths[i])), list::add) } })
                }
                val pages = fetched.flatMap { future ->
                    try {
                        future.get()
                    } catch (e: ExecutionException) {
                        throw IOException(e.cause?.message, e)
                    }
                }
                WebText.write(shard, pages)
                log("${shard.name}: ${until - first} files, ${pages.size} pages, ${pages.sumOf { p -> p.text.count { it in HAN } }} Han characters")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The Chinese pages of a WET file, [wet]: gzip members of a WARC record each, a `conversion`
     * record a page.
     * @throws IOException if it is not WARC
     */
    fun pages(wet: InputStream, page: (WebText.Page) -> Unit) {
        val input = BufferedInputStream(GZIPInputStream(wet, BUFFER), BUFFER)
        while (true) {
            val headers = headers(input) ?: return
            val length = headers["content-length"]?.toIntOrNull() ?: throw IOException("a WARC record without its length")
            val content = input.readNBytes(length)
            if (content.size < length) throw IOException("a WARC record cut short")
            if (headers["warc-type"] != "conversion" || !headers["warc-identified-content-language"].orEmpty().startsWith("zho")) continue
            val text = String(content, Charsets.UTF_8)
            if (text.count { it in HAN } >= MIN_HAN) page(WebText.Page(headers["warc-target-uri"].orEmpty(), text, headers["warc-date"].orEmpty()))
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
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_SECONDS)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    /** A whole file: one is 60 MB, and the gzip members are read from memory past the network's pauses. */
    private fun fetch(base: String, path: String): ByteArray {
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
            // CommonCrawl answers 503 (and now and then 429) to slow a client down: some 1 in 100
            // files of a run, as measured; a 404 and the like will not mend
            if (status != 0) {
                failure = IOException("$path: HTTP $status")
                if (!passing(status)) throw failure
            }
            Thread.sleep(PAUSE_MILLIS * attempt)
        }
        throw failure ?: IOException("$path: not fetched")
    }

    private fun passing(status: Int) = status == SLOW_DOWN || status == TOO_MANY || status >= SERVER_ERROR

    private val HAN = '一'..'鿿'
    private const val MIN_HAN = 50
    private const val THREADS = 8
    private const val TRIES = 8
    private const val PAUSE_MILLIS = 10_000L
    private const val CONNECT_SECONDS = 30L
    private const val FETCH_MINUTES = 5L
    private const val BUFFER = 1 shl 16
    private const val OK = 200
    private const val TOO_MANY = 429
    private const val SERVER_ERROR = 500
    private const val SLOW_DOWN = 503
}
