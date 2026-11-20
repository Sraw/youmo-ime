/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.duckdb.DuckDBConnection
import java.io.File
import java.io.IOException
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties

/** The pages of a FineWeb-2 shard worth mixing in as chat: simplified, not encyclopedic, rich in sentence particles. */
object WebText {

    /** Whether the page at [url] is taken: not an encyclopedia's, not traditional, and unless not [chatOnly], chat-like. */
    fun accepts(url: String, text: String, chatOnly: Boolean = true): Boolean {
        // Wikipedia's and other wikis' text is share-alike or NC, and 百科's are their sites' own
        val host = HOST.find(url)?.groupValues?.get(1).orEmpty().lowercase().removeSuffix(".")
        if (ENCYCLOPEDIA.containsMatchIn(host)) return false
        var han = 0
        var simplified = 0
        var traditional = 0
        var particles = 0
        for (c in text) {
            if (TextChars.isHan(c)) han++
            when {
                IS_SIMPLIFIED[c.code] -> simplified++
                IS_TRADITIONAL[c.code] -> traditional++
                IS_PARTICLE[c.code] -> particles++
            }
        }
        // cmn_Hani is Taiwan's and Hong Kong's pages too, whose words the model has not got
        return traditional * TRADITIONAL_SHARE <= simplified && (!chatOnly || particles * PER_MILLE >= MIN_PARTICLES * maxOf(1, han))
    }

    /** The lines of each page of [shard] that [accepts] takes, and the page's date, in the shard's order. */
    fun read(shard: File, chatOnly: Boolean = true, page: (List<String>, String) -> Unit) = select(shard) { statement, path ->
        query(statement, path, chatOnly, page)
    }

    /** A page as a shard holds it: where it was, its text, and when (`2023-06-14T...`, or empty). */
    class Page(val url: String, val text: String, val date: String)

    /** Every page of [shard], in the shard's order. */
    fun pages(shard: File, page: (Page) -> Unit) = select(shard) { statement, path ->
        rows(statement, path) { url, text, date -> page(Page(url, text, date)) }
    }

    /** Writes [pages] to [shard] through a file beside it, so a shard is there whole or not at all. */
    fun write(shard: File, pages: List<Page>) {
        val temp = File(shard.path + ".tmp")
        try {
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE pages (url VARCHAR, text VARCHAR, date VARCHAR)") }
                (connection as DuckDBConnection).createAppender("pages").use { appender ->
                    for (p in pages) appender.beginRow().append(p.url).append(p.text).append(p.date).endRow()
                }
                // zstd, as FineWeb-2's own: a third the size of the default snappy for Chinese text
                connection.createStatement().use { it.execute("COPY pages TO '${quoted(temp)}' (FORMAT parquet, COMPRESSION zstd)") }
            }
            if (!temp.renameTo(shard)) throw IOException("cannot write $shard")
        } catch (e: SQLException) {
            throw IOException("$shard: ${e.message}", e)
        } finally {
            temp.delete()
        }
    }

    private fun quoted(file: File): String {
        // read_parquet takes these for a glob
        require(file.path.none { it in "*?[" }) { "a path DuckDB would take for a glob: $file" }
        return file.path.replace("'", "''")
    }

    private fun select(shard: File, query: (Statement, String) -> Unit) {
        // without streaming DuckDB's JDBC driver holds the whole result, a shard's text unpacked
        val streaming = Properties().apply { setProperty("jdbc_stream_results", "true") }
        try {
            DriverManager.getConnection("jdbc:duckdb:", streaming).use { connection ->
                connection.createStatement().use { statement -> query(statement, quoted(shard)) }
            }
        } catch (e: SQLException) {
            throw IOException("$shard: ${e.message}", e)
        }
    }

    private fun rows(statement: Statement, path: String, row: (String, String, String) -> Unit) {
        // in the file's order: the mixed model must come out the same every build
        statement.execute("SET preserve_insertion_order = true")
        // DuckDB's memory is off the JVM's heap and by default most of the machine's
        statement.execute("SET memory_limit = '1GB'")
        // each thread buffers row groups of its own; mix is single-threaded past the read anyway
        statement.execute("SET threads = 2")
        val columns = "coalesce(url, ''), text, coalesce(CAST(date AS VARCHAR), '')"
        statement.executeQuery("SELECT $columns FROM read_parquet('$path') WHERE text IS NOT NULL").use { rows ->
            while (rows.next()) row(rows.getString(1), rows.getString(2), rows.getString(3))
        }
    }

    private fun query(statement: Statement, path: String, chatOnly: Boolean, page: (List<String>, String) -> Unit) =
        rows(statement, path) { url, text, date ->
            if (accepts(url, text, chatOnly)) page(text.split('\n').map(String::trim).filter(String::isNotEmpty), date)
        }

    private val HOST = Regex("""^[A-Za-z]+://([^/:?#]+)""")
    private val ENCYCLOPEDIA = Regex(
        """wiki(pedia|books|source|quote|voyage|news|versity|media)\.org$|wiktionary\.org$|wikiwand|wikizero|""" +
            """wiki2\.org$|unionpedia|jinzhao\.wiki$|wanweibaike|baike\.|hudong\.com$|""" +
            """fandom\.com$|moegirl\.org(\.cn)?$|huijiwiki\.com$|wiki\.biligame\.com$|wikihow\.com$""",
    )
    // chars whose traditional form differs, and those forms
    private const val SIMPLIFIED = "们这说时为会国个来对过发经还没么后机书车门问间关开"
    private const val TRADITIONAL = "們這說時為會國個來對過發經還沒麼後機書車門問間關開"
    private const val TRADITIONAL_SHARE = 4
    private const val PARTICLES = "吗呢吧啊呀嘛啦哦哈嗯"
    private const val PER_MILLE = 1000
    private const val MIN_PARTICLES = 5

    // looked up, not searched for: accepts goes over every char of every page, on each pass of words
    private val IS_SIMPLIFIED = table(SIMPLIFIED)
    private val IS_TRADITIONAL = table(TRADITIONAL)
    private val IS_PARTICLE = table(PARTICLES)

    private fun table(chars: String) = BooleanArray(Char.MAX_VALUE.code + 1).apply { for (c in chars) this[c.code] = true }
}
