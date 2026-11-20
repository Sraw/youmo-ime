/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import androidx.paging.PagingSource
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.backupZip
import org.fcitx.fcitx5.android.core.dirsUnder
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.clipboard.favoritesToMerge
import org.fcitx.fcitx5.android.data.clipboard.keepFavoritesToMerge
import org.fcitx.fcitx5.android.data.clipboard.mergeKeptFavorites
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The clipboard's favourites in a backup: in it less the sensitive ones; at a restore merged with the phone's, which stay. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class BackupFavoritesTest {

    @get:Rule
    val folder = TemporaryFolder()

    // not the app's own, which it writes as the test runs
    private fun install(name: String) = dirsUnder(File(folder.root, name))

    // as this build would write it, or as release [versionCode] did
    private fun metadata(packageName: String, versionCode: Long = BuildConfig.VERSION_CODE.toLong()) =
        """{"packageName":"$packageName","versionCode":$versionCode,"versionName":"1","exportTime":0}""".encodeToByteArray()

    private fun zip(packageName: String, vararg entries: Pair<String, String>, versionCode: Long = BuildConfig.VERSION_CODE.toLong()) =
        backupZip(*entries, versionCode = versionCode, packageName = packageName)

    // the package Robolectric runs as need not be the one the import checks for
    private fun exported(dirs: UserDataManager.Dirs) = ByteArrayOutputStream().also { bytes ->
        val export = ByteArrayOutputStream().also { UserDataManager.export(it, 0L, dirs).getOrThrow() }
        ZipInputStream(export.toByteArray().inputStream()).use { from ->
            ZipOutputStream(bytes).use { zip ->
                generateSequence { from.nextEntry }.forEach {
                    zip.putNextEntry(ZipEntry(it.name))
                    if (it.name == "metadata.json") zip.write(metadata(BuildConfig.APPLICATION_ID)) else from.copyTo(zip)
                }
            }
        }
    }.toByteArray().inputStream()

    private suspend fun clips(dirs: UserDataManager.Dirs, vararg entries: ClipboardEntry) =
        Room.databaseBuilder(appContext, ClipboardDatabase::class.java, File(dirs.dataBases.apply { mkdirs() }, "clbdb").path).build().run {
            entries.forEach { clipboardDao().insert(it) }
            close()
        }

    private fun pageOf(source: PagingSource<Int, ClipboardEntry>) = runBlocking {
        (source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data.map { it.text }
    }

    // the favourites and 最近 at the next open, the phone's kept favourites merged as ClipboardManager.openDatabase does
    private suspend fun opened(dirs: UserDataManager.Dirs): Pair<List<String>, List<String>> {
        val db = Room.databaseBuilder(appContext, ClipboardDatabase::class.java, File(dirs.dataBases, "clbdb").path).build()
        try {
            mergeKeptFavorites(db, dirs.dataBases)
            return pageOf(db.clipboardDao().favoriteEntries()) to pageOf(db.clipboardDao().recentEntries())
        } finally {
            db.close()
        }
    }

    @Test
    @Uat("PR-69")
    fun theFavouritesGoInTheBackupTheSensitiveOnesNot() = runTest {
        val old = install("old")
        clips(
            old,
            ClipboardEntry(text = "公园", favorite = true, timestamp = 200L),
            ClipboardEntry(text = "mima1234", favorite = true, sensitive = true, timestamp = 300L),
        )
        val fresh = install("fresh")
        UserDataManager.import(exported(old), fresh).getOrThrow()
        assertEquals(listOf("公园") to emptyList<String>(), opened(fresh))
    }

    @Test
    @Uat("PR-70")
    fun aRestoreReplaces最近AndMergesTheFavouritesThePhonesSensitiveOnesKept() = runTest {
        val backup = install("backup")
        clips(
            backup,
            ClipboardEntry(text = "备份的最近", timestamp = 100L),
            ClipboardEntry(text = "公园", favorite = true, timestamp = 200L),
            ClipboardEntry(text = "备份的", favorite = true, timestamp = 300L),
        )
        val phone = install("phone")
        clips(
            phone,
            ClipboardEntry(text = "手机的最近", timestamp = 9000L),
            ClipboardEntry(text = "mima1234", favorite = true, sensitive = true, timestamp = 4000L),
            ClipboardEntry(text = "公园", favorite = true, timestamp = 5000L),
            ClipboardEntry(text = "散步", favorite = true, timestamp = 6000L),
            ClipboardEntry(text = "取消了", favorite = true, deleted = true, timestamp = 7000L),
        )
        UserDataManager.import(exported(backup), phone).getOrThrow()
        val kept = favoritesToMerge(phone.dataBases).isFile
        val (favorites, recent) = opened(phone)
        val mergedOnce = !favoritesToMerge(phone.dataBases).exists()
        // cut short and run again, or kept anew from this database: none added
        keepFavoritesToMerge(phone.dataBases)
        val again = opened(phone).first
        assertRows(
            "kept beside the database till it opens" to { assertTrue(kept) },
            "最近 is the backup's" to { assertEquals(listOf("备份的最近"), recent) },
            "the phone's first in their order, 公园 once, the sensitive one and the backup's kept; one unfavourited not" to {
                assertEquals(listOf("散步", "mima1234", "备份的", "公园"), favorites)
            },
            "merged, the file goes" to { assertTrue(mergedOnce) },
            "merged again, nothing more" to { assertEquals(favorites, again) },
        )
    }

    @Test
    @Uat("PR-70")
    fun anOlderBackupsPinIsAFavouriteBesideThePhones() = runTest {
        // as 0.6.2 wrote it: the same schema, the column still pinned
        val backup = install("backup")
        clips(backup)
        Room.databaseBuilder(appContext, ClipboardDatabase::class.java, File(backup.dataBases, "clbdb").path).build().run {
            openHelper.writableDatabase.execSQL(
                "INSERT INTO ${ClipboardEntry.TABLE_NAME} (text, pinned, timestamp, type, deleted, sensitive) VALUES ('钉', 1, 100, 'text/plain', 0, 0)"
            )
            close()
        }
        val phone = install("phone")
        clips(phone, ClipboardEntry(text = "公园", favorite = true, timestamp = 5000L))
        UserDataManager.import(exported(backup), phone).getOrThrow()
        assertEquals(listOf("公园", "钉"), opened(phone).first)
    }

    // the phone's sensitive ones are in no backup: not kept, they would be gone with its database
    @Test
    fun theFavouritesNotKeptFailTheRestoreBeforeAnythingIsReplaced() = runTest {
        val backup = install("backup")
        clips(backup, ClipboardEntry(text = "备份的", favorite = true, timestamp = 100L))
        File(backup.sharedPrefs.apply { mkdirs() }, "mine.xml").writeText("backup's")
        val phone = install("phone")
        clips(phone, ClipboardEntry(text = "mima1234", favorite = true, sensitive = true, timestamp = 4000L))
        val prefs = File(phone.sharedPrefs.apply { mkdirs() }, "mine.xml").apply { writeText("phone's") }
        // its write fails: a directory where it goes
        File(favoritesToMerge(phone.dataBases), "in-the-way").apply { parentFile!!.mkdirs() }.writeText("")
        val result = UserDataManager.import(exported(backup), phone)
        val favorites = Room.databaseBuilder(appContext, ClipboardDatabase::class.java, File(phone.dataBases, "clbdb").path).build()
            .run { try { pageOf(clipboardDao().favoriteEntries()) } finally { close() } }
        assertRows(
            "the restore fails" to { assertTrue(result.isFailure) },
            "the phone's clipboard as it was" to { assertEquals(listOf("mima1234"), favorites) },
            "its settings too" to { assertEquals("phone's", prefs.readText()) },
            "no half-written file left" to {
                assertFalse(File(phone.dataBases, "${favoritesToMerge(phone.dataBases).name}.tmp").exists())
            },
        )
    }

    @Test
    fun theFavouritesKeptAtARestoreNeverGoInABackupNorComeFromOne() {
        val old = install("old")
        File(old.dataBases.apply { mkdirs() }, "favorites-to-merge.json").writeText("[]")
        File(old.dataBases, "favorites-to-merge.json.tmp").writeText("[]")
        val names = ZipInputStream(ByteArrayOutputStream().also { UserDataManager.export(it, 0L, old).getOrThrow() }.toByteArray().inputStream())
            .use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }
        val phone = install("phone")
        UserDataManager.import(
            zip(BuildConfig.APPLICATION_ID, "databases/favorites-to-merge.json" to "[]", "databases/favorites-to-merge.json.tmp" to "[]"),
            phone,
        ).getOrThrow()
        assertRows(
            "it may hold the sensitive ones" to { assertTrue(names.toString(), names.none { "favorites-to-merge" in it }) },
            "one a backup brings is no phone's" to {
                assertEquals(emptyList<String>(), phone.dataBases.list().orEmpty().filter { "favorites-to-merge" in it })
            },
        )
    }

    @Test
    fun aRestoreNotYetOpenedKeepsItsPhonesFavouritesThroughTheNext() = runTest {
        val phone = install("phone")
        clips(phone, ClipboardEntry(text = "第一次的", favorite = true, timestamp = 5000L))
        val first = install("first")
        clips(first, ClipboardEntry(text = "中间", favorite = true, timestamp = 100L))
        val second = install("second")
        clips(second, ClipboardEntry(text = "最后", favorite = true, timestamp = 100L))
        UserDataManager.import(exported(first), phone).getOrThrow()
        // restored again before the app opened the first
        UserDataManager.import(exported(second), phone).getOrThrow()
        assertEquals(listOf("第一次的", "中间", "最后"), opened(phone).first)
    }
}
