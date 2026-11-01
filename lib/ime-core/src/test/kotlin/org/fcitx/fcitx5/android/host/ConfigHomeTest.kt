/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import java.io.IOException
import java.io.InputStream
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.LogOutput
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.write
import org.fcitx.fcitx5.android.host.ConfigHome.Place
import org.fcitx.fcitx5.android.host.ConfigHome.Problem
import org.fcitx.fcitx5.android.host.ConfigHome.Use
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigHomeTest {

    private val files = MemoryFiles()
    private val external = MemoryFiles()

    // the file at [path], its directories made first where [make]
    private fun UserFiles.at(path: String, make: Boolean = false): LogFile {
        val dir = path.split('/').dropLast(1).fold(this) { at, name -> at.dir(name) }
        if (make) dir.mkdirs()
        return dir.file(path.substringAfterLast('/'))
    }

    private fun UserFiles.put(path: String, text: String) = at(path, make = true).write(text.toByteArray())

    private fun UserFiles.text(path: String): String? = at(path).let { if (it.exists()) String(it.read()) else null }

    private val engineConf = "conf/androidengine.conf"

    @Test
    fun `an older build's external config is moved in whole, the old deleted`() {
        external.put("config/$engineConf", "EnablePrediction=False\n")
        external.put("config/conf/punctuation.conf", "HalfWidthPuncAfterLetterOrNumber=False\n")
        external.put("config/profile", "[Groups/0]\n")
        assertEquals(Place(Use.HOME, writable = true, movedFrom = Use.EXTERNAL), ConfigHome.settle(files, external))
        val home = files.dir(ConfigHome.CONFIG.name)
        assertEquals("EnablePrediction=False\n", home.text(engineConf))
        assertEquals("HalfWidthPuncAfterLetterOrNumber=False\n", home.text("conf/punctuation.conf"))
        assertEquals("[Groups/0]\n", home.text("profile"))
        assertFalse(files.dir(ConfigHome.CONFIG.staged).exists())
        assertFalse(external.dir("config").exists())
        assertFalse(home.file(ConfigHome.MOVED).exists())
    }

    @Test
    fun `an old directory that cannot be deleted leaves the move good, and is deleted at a later start`() {
        external.put("config/$engineConf", "EnablePrediction=False\n")
        assertEquals(Place(Use.HOME, writable = true, movedFrom = Use.EXTERNAL), ConfigHome.settle(files, Undeletable(external)))
        val home = files.dir(ConfigHome.CONFIG.name)
        assertEquals("EnablePrediction=False\n", home.text(engineConf))
        assertTrue(external.dir("config").exists())
        // the external storage away at the next start: nothing deleted, the marker kept
        assertEquals(Place(Use.HOME, writable = true), ConfigHome.settle(files, null))
        assertEquals(Place(Use.HOME, writable = true), ConfigHome.settle(files, Unlistable(external, null)))
        assertTrue(external.dir("config").exists())
        assertTrue(home.file(ConfigHome.MOVED).exists())
        assertEquals(Place(Use.HOME, writable = true), ConfigHome.settle(files, external))
        assertFalse(external.dir("config").exists())
        assertFalse(home.file(ConfigHome.MOVED).exists())
        assertEquals("EnablePrediction=False\n", home.text(engineConf))
    }

    @Test
    fun `a fallback moved in is deleted, the external storage left alone`() {
        files.put("config/$engineConf", "PageSize=7\n")
        external.put("theme/mine.json", "{}")
        assertEquals(Use.FALLBACK, ConfigHome.settle(files, external).movedFrom)
        assertFalse(files.dir("config").exists())
        assertEquals("{}", external.text("theme/mine.json"))
    }

    @Test
    fun `a home made fresh never deletes an older build's directory that appears later`() {
        assertEquals(Place(Use.HOME, writable = true), ConfigHome.settle(files, external))
        external.put("config/$engineConf", "EnablePrediction=False\n")
        files.put("config/$engineConf", "PageSize=7\n")
        assertEquals(Place(Use.HOME, writable = true), ConfigHome.settle(files, external))
        assertEquals("EnablePrediction=False\n", external.text("config/$engineConf"))
        assertEquals("PageSize=7\n", files.text("config/$engineConf"))
    }

    // a home left with its marker as a kill just after the rename leaves it, settled at the next start:
    // the old the marker names deleted (or already gone), the marker then; one naming nothing known deletes nothing
    private class Marker(val why: String, val text: String, val oldExternal: Boolean, val oldFallback: Boolean, val externalLeft: Boolean, val fallbackLeft: Boolean)

    @Test
    fun `a home's marker has the old it names deleted at the next start, then goes`() = assertRows(
        listOf(
            Marker("external, killed before its delete", Use.EXTERNAL.name, oldExternal = true, oldFallback = false, externalLeft = false, fallbackLeft = false),
            Marker("external, its old already gone (a cleaner)", Use.EXTERNAL.name, oldExternal = false, oldFallback = false, externalLeft = false, fallbackLeft = false),
            Marker("fallback, killed before its delete", Use.FALLBACK.name, oldExternal = true, oldFallback = true, externalLeft = true, fallbackLeft = false),
            Marker("a name not ours (torn)", "EXTERN", oldExternal = true, oldFallback = true, externalLeft = true, fallbackLeft = true),
        ),
        { it.why },
    ) { row ->
        val files = MemoryFiles()
        val external = MemoryFiles()
        if (row.oldExternal) external.put("config/$engineConf", "EnablePrediction=False\n")
        if (row.oldFallback) files.put("config/$engineConf", "PageSize=7\n")
        val home = files.dir(ConfigHome.CONFIG.name)
        home.put(engineConf, "EnablePrediction=False\n")
        home.put(ConfigHome.MOVED, row.text)
        assertEquals(Place(Use.HOME, writable = true), ConfigHome.settle(files, external))
        assertEquals(row.externalLeft, external.dir("config").exists())
        assertEquals(row.fallbackLeft, files.dir("config").exists())
        assertFalse(home.file(ConfigHome.MOVED).exists())
    }

    @Test
    fun `once moved the old is never read again, nor moved over what the user set since`() {
        external.put("config/$engineConf", "EnablePrediction=False\n")
        ConfigHome.settle(files, external)
        files.dir(ConfigHome.CONFIG.name).put(engineConf, "EnablePrediction=True\n")
        assertEquals(Place(Use.HOME, writable = true), ConfigHome.settle(files, external))
        // the external storage away or emptied by a cleaner: the settings are where they were
        assertEquals(Place(Use.HOME, writable = true), ConfigHome.settle(files, null))
        external.deleteAll()
        assertEquals(Place(Use.HOME, writable = true), ConfigHome.settle(files, external))
        assertEquals("EnablePrediction=True\n", files.dir(ConfigHome.CONFIG.name).text(engineConf))
    }

    @Test
    fun `the external config is taken over a fallback an older build wrote at a start without it`() {
        external.put("config/$engineConf", "EnablePrediction=False\n")
        files.put("config/$engineConf", "PageSize=7\n")
        assertEquals(Use.EXTERNAL, ConfigHome.settle(files, external).movedFrom)
        assertEquals("EnablePrediction=False\n", files.dir(ConfigHome.CONFIG.name).text(engineConf))
    }

    @Test
    fun `a phone that only had the fallback has it moved`() {
        files.put("config/$engineConf", "PageSize=7\n")
        assertEquals(Place(Use.HOME, writable = true, movedFrom = Use.FALLBACK), ConfigHome.settle(files, external))
        assertEquals("PageSize=7\n", files.dir(ConfigHome.CONFIG.name).text(engineConf))
    }

    @Test
    fun `a new install makes its own empty directory, which marks it settled`() {
        assertEquals(Place(Use.HOME, writable = true), ConfigHome.settle(files, external))
        assertTrue(files.dir(ConfigHome.CONFIG.name).exists())
        // a config an older build left later (a restore from adb, say) is not taken over the settled one
        external.put("config/$engineConf", "EnablePrediction=False\n")
        assertEquals(Place(Use.HOME, writable = true), ConfigHome.settle(files, external))
        assertEquals(null, files.dir(ConfigHome.CONFIG.name).text(engineConf))
    }

    @Test
    fun `with the external storage away nothing is settled nor written, the move tried again later`() {
        assertEquals(Place(Use.NONE, writable = false, Problem.EXTERNAL_AWAY), ConfigHome.settle(files, null))
        files.put("config/$engineConf", "PageSize=7\n")
        // what an older build read at such a start, read again, but not taken for the settings
        assertEquals(Place(Use.FALLBACK, writable = false, Problem.EXTERNAL_AWAY), ConfigHome.settle(files, null))
        assertFalse(files.dir(ConfigHome.CONFIG.name).exists())
        external.put("config/$engineConf", "EnablePrediction=False\n")
        assertEquals(Use.EXTERNAL, ConfigHome.settle(files, external).movedFrom)
        assertEquals("EnablePrediction=False\n", files.dir(ConfigHome.CONFIG.name).text(engineConf))
    }

    @Test
    fun `a copy a killed start left half made is made again`() {
        files.put("${ConfigHome.CONFIG.staged}/$engineConf", "Page")
        files.put("${ConfigHome.CONFIG.staged}/stray", "x")
        external.put("config/$engineConf", "PageSize=9\n")
        ConfigHome.settle(files, external)
        val home = files.dir(ConfigHome.CONFIG.name)
        assertEquals("PageSize=9\n", home.text(engineConf))
        assertEquals(null, home.text("stray"))
    }

    @Test
    fun `a copy that fails leaves no home, the old used and written where it is, the move tried again`() {
        external.put("config/$engineConf", "EnablePrediction=False\n")
        val full = FullFiles(files)
        val place = ConfigHome.settle(full, external)
        assertEquals(Use.EXTERNAL, place.use)
        assertTrue(place.writable)
        assertEquals(Problem.NOT_MOVED, place.problem)
        assertNotNull(place.error)
        assertFalse(files.dir(ConfigHome.CONFIG.name).exists())
        assertFalse(files.dir(ConfigHome.CONFIG.staged).exists())
        assertEquals(Use.EXTERNAL, ConfigHome.settle(files, external).movedFrom)
    }

    @Test
    fun `a copy that reads back otherwise is not taken`() {
        external.put("config/$engineConf", "EnablePrediction=False\n")
        val place = ConfigHome.settle(FullFiles(files, torn = true), external)
        assertEquals(Problem.NOT_MOVED, place.problem)
        assertFalse(files.dir(ConfigHome.CONFIG.name).exists())
    }

    @Test
    fun `nested directories and their files come along byte for byte`() {
        val bytes = byteArrayOf(0, -1, 10, 13, 0x7f)
        val deep = external.dir("config").dir("conf").dir("deeper")
        deep.mkdirs()
        deep.file("raw").write(bytes)
        ConfigHome.settle(files, external)
        assertArrayEquals(bytes, files.dir(ConfigHome.CONFIG.name).dir("conf").dir("deeper").file("raw").read())
    }

    @Test
    fun `fcitx's data and the themes move each on its own, beside the config, under names of their own`() {
        external.put("config/$engineConf", "PageSize=9\n")
        external.put("data/pinyin/customphrase", "dz,1=北京\n")
        external.put("data/punctuation/punc.mb.zh_CN", ", ，\n")
        external.put("theme/mine.json", "{}")
        // what an older build wrote in the app's files at a start without the external storage: not data's home
        files.put("data/pinyin/customphrase", "stale")
        assertEquals(Use.EXTERNAL, ConfigHome.settle(files, external, ConfigHome.DATA).movedFrom)
        assertFalse(files.dir(ConfigHome.CONFIG.name).exists())
        assertFalse(files.dir(ConfigHome.THEME.name).exists())
        val data = files.dir(ConfigHome.DATA.name)
        assertEquals("dz,1=北京\n", data.text("pinyin/customphrase"))
        assertEquals(", ，\n", data.text("punctuation/punc.mb.zh_CN"))
        assertEquals("stale", files.text("data/pinyin/customphrase"))
        assertEquals(Use.EXTERNAL, ConfigHome.settle(files, external, ConfigHome.THEME).movedFrom)
        assertEquals("{}", files.dir(ConfigHome.THEME.name).text("mine.json"))
        assertEquals(Use.EXTERNAL, ConfigHome.settle(files, external).movedFrom)
        assertEquals(setOf("host-config", "host-data", "host-theme", "data"), files.dirs().toSet())
        assertEquals(Use.HOME, ConfigHome.settle(files, null, ConfigHome.DATA).use)
    }

    @Test
    fun `a file larger than a chunk, ending mid-chunk, comes along whole and one differing late is caught`() {
        // a dictionary's size is past what is held at once: streamed, compared a chunk at a time
        val bytes = ByteArray(3 * 65536 + 123) { (it * 31 + it / 7).toByte() }
        val dir = external.dir("data").dir("pinyin").dir("dictionaries")
        dir.mkdirs()
        dir.file("merged.ymd").write(bytes)
        ConfigHome.settle(files, external, ConfigHome.DATA)
        assertArrayEquals(bytes, files.dir("host-data").dir("pinyin").dir("dictionaries").file("merged.ymd").read())
        // the source again: the move just made deleted it
        dir.mkdirs()
        dir.file("merged.ymd").write(bytes)
        val torn = MemoryFiles()
        val place = ConfigHome.settle(FullFiles(torn, torn = true, stagedName = ConfigHome.DATA.staged), external, ConfigHome.DATA)
        assertEquals(Problem.NOT_MOVED, place.problem)
        assertFalse(torn.dir("host-data").exists())
    }

    // one place a process for everyone: an editor that took the old directory while the move failed
    // must not write where the engine, settling later, no longer reads
    @Test
    fun `a home is settled once a process, a failed move too, and tried again by the next`() {
        external.put("data/pinyin/customphrase", "dz,1=北京\n")
        var reachable = false
        val heard = mutableListOf<Pair<ConfigHome.Home, Place>>()
        val ext = { if (reachable) external else Unlistable(external, null) }
        val process = ConfigHome.Settled(files, ext, { false }) { which, place -> heard += which to place }
        val first = process.of(ConfigHome.DATA)
        assertEquals(Place(Use.NONE, writable = false, Problem.EXTERNAL_AWAY), first)
        reachable = true
        assertEquals(first, process.of(ConfigHome.DATA))
        assertFalse(files.dir("host-data").exists())
        // each home its own decision, made as it is asked for
        assertEquals(Use.HOME, process.of(ConfigHome.THEME).use)
        assertEquals(listOf(ConfigHome.DATA to first, ConfigHome.THEME to Place(Use.HOME, writable = true)), heard)
        // the next process
        val next = ConfigHome.Settled(files, ext, { false })
        assertEquals(Use.EXTERNAL, next.of(ConfigHome.DATA).movedFrom)
        assertEquals("dz,1=北京\n", files.dir("host-data").text("pinyin/customphrase"))
    }

    @Test
    fun `an old directory or one in it that cannot be listed is not moved as empty, and tried again`() {
        external.put("config/$engineConf", "EnablePrediction=False\n")
        for (unlistable in listOf("config", "conf")) {
            val place = ConfigHome.settle(files, Unlistable(external, unlistable), ConfigHome.CONFIG)
            assertEquals(Use.EXTERNAL, place.use)
            assertEquals(Problem.NOT_MOVED, place.problem)
            assertFalse(files.dir(ConfigHome.CONFIG.name).exists())
            assertFalse(files.dir(ConfigHome.CONFIG.staged).exists())
        }
        // the external files directory itself not listable: taken as away, nothing made
        assertEquals(Place(Use.NONE, writable = false, Problem.EXTERNAL_AWAY), ConfigHome.settle(files, Unlistable(external, null)))
        assertFalse(files.dir(ConfigHome.CONFIG.name).exists())
        assertEquals(Use.EXTERNAL, ConfigHome.settle(files, external).movedFrom)
    }

    @Test
    fun `a new install without the external storage makes its own directory, an older one waits for it`() {
        assertEquals(Place(Use.HOME, writable = true), ConfigHome.settle(files, null, ConfigHome.CONFIG, fresh = true))
        val older = MemoryFiles()
        older.put("config/$engineConf", "PageSize=7\n")
        assertEquals(
            Place(Use.FALLBACK, writable = false, Problem.EXTERNAL_AWAY),
            ConfigHome.settle(older, null, ConfigHome.CONFIG, fresh = true),
        )
        assertEquals(Place(Use.NONE, writable = false, Problem.EXTERNAL_AWAY), ConfigHome.settle(MemoryFiles(), null))
    }

    @Test
    fun `a half made copy that cannot be deleted is not moved over`() {
        files.put("${ConfigHome.CONFIG.staged}/$engineConf", "Page")
        external.put("config/$engineConf", "PageSize=9\n")
        val place = ConfigHome.settle(Undeletable(files), external)
        assertEquals(Problem.NOT_MOVED, place.problem)
        assertFalse(files.dir(ConfigHome.CONFIG.name).exists())
    }

    // an old config that cannot be moved as it is: no home made, the old kept, and the next start tries again.
    // Each a bug that bit (review F4610, F4619, F4889, F4912, F4964, F4977: one table)
    private class Unmoved(
        val why: String,
        val source: String,
        val text: String,
        val settle: (MemoryFiles, MemoryFiles) -> Place,
        val problem: Problem,
        val use: Use? = null,
        // false where the home is not settled: the host writing its defaults there was the loss (F4999)
        val writable: Boolean? = null,
    )

    @Test
    fun `an old config that cannot be moved is kept, no home made`() = assertRows(
        listOf(
            Unmoved(
                "larger than the room free: every start copied it all again, filling the storage (F4610)",
                "config/$engineConf", "EnablePrediction=False\n", { f, e -> ConfigHome.settle(f, e, room = 1) }, Problem.NOT_MOVED, Use.EXTERNAL,
            ),
            Unmoved(
                "something at its path not listed as a directory, as a stat that failed: taken for none, the settings reset (F4619)",
                "config", "?", { f, e -> ConfigHome.settle(f, e) }, Problem.EXTERNAL_AWAY, Use.NONE, writable = false,
            ),
            Unmoved(
                "listed, its kind unknown (EIO on the external storage's FUSE): an empty home made for good (F4889)",
                "config/$engineConf", "EnablePrediction=False\n", { f, e -> ConfigHome.settle(f, Unstattable(e, "config")) }, Problem.EXTERNAL_AWAY, Use.NONE, writable = false,
            ),
            Unmoved(
                "a file in it of a kind that does not read: not copied, then deleted with the source (F4912)",
                "config/$engineConf", "EnablePrediction=False\n", { f, e -> ConfigHome.settle(f, Unstattable(e, "androidengine.conf")) }, Problem.NOT_MOVED,
            ),
            Unmoved(
                "a read that ends early without throwing: half a file taken as copied (F4964)",
                "config/$engineConf", "EnablePrediction=False\n", { f, e -> ConfigHome.settle(f, ShortRead(e)) }, Problem.NOT_MOVED,
            ),
        ),
        { it.why },
    ) { row ->
        val files = MemoryFiles()
        val external = MemoryFiles()
        external.put(row.source, row.text)
        val place = row.settle(files, external)
        assertEquals(row.problem, place.problem)
        row.use?.let { assertEquals(it, place.use) }
        row.writable?.let { assertEquals(it, place.writable) }
        assertFalse(files.dir(ConfigHome.CONFIG.name).exists())
        assertFalse(files.dir(ConfigHome.CONFIG.staged).exists())
        assertEquals(row.text, external.text(row.source))
    }

    // a bug that bit: a home not made was told with no error, the app logging a null throwable
    @Test
    fun `a home that cannot be made says why`() {
        val place = ConfigHome.settle(Unmakeable(files), external)
        assertEquals(Use.NONE, place.use)
        assertEquals(Problem.NOT_MOVED, place.problem)
        assertFalse(place.writable)
        assertNotNull(place.error)
    }

    // a bug that bit: the move gave every file a new time, so imported lexicons were compiled again and away meanwhile
    @Test
    fun `a moved file keeps its time`() {
        external.put("config/$engineConf", "EnablePrediction=False\n")
        val source = external.at("config/$engineConf")
        assertTrue(source.setLastModified(1_600_000_000_000))
        ConfigHome.settle(files, external)
        assertEquals(1_600_000_000_000, files.dir(ConfigHome.CONFIG.name).at(engineConf).lastModified())
    }

    // review F4889: a listing whose kind is unknown was taken for the old gone, the marker dropped
    @Test
    fun `an old directory of a kind that does not read is not taken for gone, its marker kept`() {
        external.put("config/$engineConf", "EnablePrediction=False\n")
        ConfigHome.settle(files, Undeletable(external))
        val marker = files.dir(ConfigHome.CONFIG.name).file(ConfigHome.MOVED)
        assertTrue(marker.exists())
        ConfigHome.settle(files, Unstattable(external, "config"))
        assertTrue(marker.exists())
        assertEquals("EnablePrediction=False\n", external.text("config/$engineConf"))
        ConfigHome.settle(files, external)
        assertFalse(marker.exists() || external.dir("config").exists())
    }

    // [inner], but [name] anywhere in it listed with its kind unknown, as a stat failing
    private class Unstattable(private val inner: UserFiles, private val name: String) : UserFiles by inner {
        override fun dir(name: String): UserFiles = Unstattable(inner.dir(name), this.name)

        override fun listing() = inner.listing()?.let { l ->
            if (name in l.files || name in l.dirs) UserFiles.Listing(l.files - name, l.dirs - name, l.unknown + name) else l
        }
    }

    // [inner], but each file's stream ends halfway, no error
    private class ShortRead(private val inner: UserFiles) : UserFiles by inner {
        override fun dir(name: String): UserFiles = ShortRead(inner.dir(name))

        override fun file(name: String): LogFile = inner.file(name).let { f ->
            object : LogFile by f {
                override fun input(): InputStream = f.read().let { it.copyOf(it.size / 2) }.inputStream()
            }
        }
    }

    private class Unmakeable(private val inner: UserFiles) : UserFiles by inner {
        override fun dir(name: String): UserFiles = Unmakeable(inner.dir(name))

        override fun mkdirs() = false
    }

    // [inner], but [name] (null: [inner] itself) cannot be listed, as a directory on a storage that fails
    private class Unlistable(private val inner: UserFiles, private val name: String?) : UserFiles by inner {
        override fun dir(name: String): UserFiles = Unlistable(inner.dir(name), this.name?.takeIf { it != name })

        override fun listing() = if (name != null) inner.listing() else null

        override fun files() = if (name != null) inner.files() else emptyList()

        override fun dirs() = if (name != null) inner.dirs() else emptyList()
    }

    private class Undeletable(private val inner: UserFiles) : UserFiles by inner {
        override fun dir(name: String): UserFiles = Undeletable(inner.dir(name))

        override fun deleteAll() = false

        override fun renameTo(dest: UserFiles) = inner.renameTo((dest as Undeletable).inner)
    }

    // [inner]'s files, but a write into STAGED fails (the storage full) or, [torn], reads back short
    private class FullFiles(
        private val inner: UserFiles,
        private val torn: Boolean = false,
        private val staged: Boolean = false,
        private val stagedName: String = ConfigHome.CONFIG.staged,
    ) : UserFiles by inner {
        override fun dir(name: String): UserFiles = FullFiles(inner.dir(name), torn, staged || name == stagedName, stagedName)

        override fun file(name: String): LogFile = if (!staged) inner.file(name) else Failing(inner.file(name), torn)

        override fun renameTo(dest: UserFiles) = inner.renameTo((dest as FullFiles).inner)
    }

    private class Failing(private val inner: LogFile, private val torn: Boolean) : LogFile by inner {
        override fun create(): LogOutput = if (torn) inner.create() else throw IOException("no space left")

        override fun read(): ByteArray = inner.read().let { if (torn) it.copyOf(it.size - 1) else it }

        override fun input(): InputStream = if (torn) read().inputStream() else inner.input()
    }
}
