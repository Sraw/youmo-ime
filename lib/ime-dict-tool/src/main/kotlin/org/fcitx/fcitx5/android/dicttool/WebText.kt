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

/**
 * The pages of a FineWeb-2 shard (HuggingFaceFW/fineweb-2, `cmn_Hani`; ODC-By) worth mixing into
 * the model as chat: in simplified Chinese, not from an encyclopedia, and written the way people
 * talk, by how many sentence particles (吗呢吧 ...) they hold a thousand Han characters; a 哈 of
 * 哈尔滨 counts too, as measured. Among all of a shard's pages these
 * measured better on the written and the chat evaluation sets alike (dev/ENGINE-DESIGN.md).
 */
object WebText {

    /**
     * Whether the page at [url] with [text] is taken: not an encyclopedia's, not traditional, and
     * unless [chatOnly] is off, written the way people write to each other (particles enough).
     */
    fun accepts(url: String, text: String, chatOnly: Boolean = true): Boolean {
        // Wikipedia's text is CC BY-SA, whose terms over statistics drawn from it are unsettled; so
        // are its mirrors', Wikimedia's other sites' and the big Chinese wikis' (some NC too), and
        // 百科's are their sites' own
        val host = HOST.find(url)?.groupValues?.get(1).orEmpty().lowercase().removeSuffix(".")
        if (ENCYCLOPEDIA.containsMatchIn(host)) return false
        var han = 0
        var simplified = 0
        var traditional = 0
        var particles = 0
        for (c in text) {
            if (c in HAN) han++
            when (c) {
                in SIMPLIFIED -> simplified++
                in TRADITIONAL -> traditional++
                in PARTICLES -> particles++
            }
        }
        // cmn_Hani is Taiwan's and Hong Kong's pages too, whose words the model has not got
        return traditional * TRADITIONAL_SHARE <= simplified && (!chatOnly || particles * PER_MILLE >= MIN_PARTICLES * maxOf(1, han))
    }

    /**
     * The lines of each page of [shard] that [accepts] takes (with [chatOnly]), and the page's date
     * (`2023-06-14T...`, or empty), a page at a time, in the shard's order.
     * @throws IOException if DuckDB cannot read it
     */
    fun read(shard: File, chatOnly: Boolean = true, page: (List<String>, String) -> Unit) = select(shard) { statement, path ->
        query(statement, path, chatOnly, page)
    }

    /** A page as a shard holds it: where it was, its text, and when (`2023-06-14T...`, or empty). */
    class Page(val url: String, val text: String, val date: String)

    /**
     * Every page of [shard] as it is, none left out, in the shard's order.
     * @throws IOException if DuckDB cannot read it
     */
    fun pages(shard: File, page: (Page) -> Unit) = select(shard) { statement, path ->
        rows(statement, path) { url, text, date -> page(Page(url, text, date)) }
    }

    /**
     * Writes [pages] to [shard] as `url, text, date`, what [read] and [pages] take, through a file
     * beside it: a shard is there whole or not at all.
     * @throws IOException if DuckDB cannot write it
     */
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
        } catch (e: SQLException) {
            temp.delete()
            throw IOException("$shard: ${e.message}", e)
        }
        if (!temp.renameTo(shard)) throw IOException("cannot write $shard")
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
        // in the file's order (DuckDB's default, made explicit): the mixed model must come out the
        // same every build
        statement.execute("SET preserve_insertion_order = true")
        // DuckDB's own memory is off the JVM's heap and by default most of the machine's, on top of
        // the models mix holds
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
    private val HAN = '一'..'鿿'
    // chars whose traditional form differs, and those forms
    private const val SIMPLIFIED = "们这说时为会国个来对过发经还没么后机书车门问间关开"
    private const val TRADITIONAL = "們這說時為會國個來對過發經還沒麼後機書車門問間關開"
    private const val TRADITIONAL_SHARE = 4
    private const val PARTICLES = "吗呢吧啊呀嘛啦哦哈嗯"
    private const val PER_MILLE = 1000
    private const val MIN_PARTICLES = 5
}
