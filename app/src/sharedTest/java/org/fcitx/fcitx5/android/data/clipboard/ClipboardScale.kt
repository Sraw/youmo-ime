/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import java.io.File
import java.io.OutputStream
import kotlin.random.Random

/**
 * How long the clipboard takes with many entries (「无上限」, E2), on the JVM (ClipboardScaleBenchmark)
 * and on a device (ClipboardScaleDeviceBenchmark): opening its panel (the count and the first page, as
 * ClipboardWindow loads them), a page deep down the list, the list again after a copy, the check on
 * each copy, a search by a part of the text (the panel has none yet: what one would cost), 清空全部
 * and its undo, and the backup (UserDataManager.export). Each the median of [RUNS] after one run to warm up.
 */
object ClipboardScale {

    const val RUNS = 5

    class Times(val size: Int, val fileKiB: Long, val ms: Map<String, Double>) {
        override fun toString() = "clipboard $size entries (${fileKiB} KiB): " +
            ms.entries.joinToString { (what, t) -> "$what %.1f ms".format(t) }
    }

    // as people copy: most a line or two, one in ten a long text, some pinned, some marked sensitive
    private fun entryText(i: Int, random: Random): String = when (random.nextInt(10)) {
        0 -> "第 $i 段：" + "会议纪要，下周一前提交预算和排期。".repeat(random.nextInt(40, 120))
        1, 2 -> "https://example.com/item/$i?from=clip&ref=${random.nextInt()}"
        3 -> "${random.nextInt(100000, 999999)}"
        else -> "第 $i 条：明天下午三点在会议室见 see you" + "，好的".repeat(random.nextInt(0, 8))
    }

    private fun median(block: () -> Unit): Double {
        block()
        return middle(List(RUNS) {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1e6
        })
    }

    // the median of [RUNS] times; a loop timing a part of each run (open: not the close) passes its own
    private fun middle(runs: List<Double>) = runs.sorted()[RUNS / 2]

    private fun open(context: Context, file: File) =
        Room.databaseBuilder(context, ClipboardDatabase::class.java, file.path).allowMainThreadQueries().build()

    private fun fill(context: Context, file: File, size: Int) = open(context, file).apply {
        val random = Random(size)
        val sql = openHelper.writableDatabase
        sql.beginTransaction()
        sql.compileStatement(
            "INSERT INTO ${ClipboardEntry.TABLE_NAME} (text, pinned, timestamp, type, deleted, sensitive) VALUES (?, ?, ?, 'text/plain', 0, ?)"
        ).use { insert ->
            for (i in 0 until size) {
                insert.bindString(1, entryText(i, random))
                insert.bindLong(2, if (i % 500 == 0) 1 else 0)
                insert.bindLong(3, i.toLong())
                insert.bindLong(4, if (i % 50 == 0) 1 else 0)
                insert.executeInsert()
            }
        }
        sql.setTransactionSuccessful()
        sql.endTransaction()
        close()
    }

    /** The times at [size] entries, in a database under [root] (made, filled and left there). */
    suspend fun measure(context: Context, root: File, size: Int): Times {
        val databases = File(root, "databases").apply { mkdirs() }
        val file = File(databases, CLIPBOARD_DATABASE)
        fill(context, file, size)
        val ms = LinkedHashMap<String, Double>()
        // the panel opening: the file opened, its count, the first three pages (PagingConfig(16))
        var page: PagingSource.LoadResult<Int, ClipboardEntry>? = null
        val openRuns = ArrayList<Double>()
        repeat(RUNS + 1) {
            val start = System.nanoTime()
            val db = open(context, file)
            val dao = db.clipboardDao()
            check(dao.itemCount() == size)
            page = dao.allEntries().load(PagingSource.LoadParams.Refresh(null, 48, false))
            openRuns += (System.nanoTime() - start) / 1e6
            db.close()
        }
        check((page as PagingSource.LoadResult.Page).data.size == minOf(size, 48))
        ms["open"] = middle(openRuns.drop(1))

        val db = open(context, file)
        val dao = db.clipboardDao()
        suspend fun at(position: Int) = dao.allEntries().load(PagingSource.LoadParams.Refresh(position, 16, false))
        // scrolled half way down: what a fling far down loads
        ms["page at half"] = median { runBlocking { at(size / 2) } }
        // a copy while open: inserted, the list loaded again from the top, as Room's invalidation has it
        var n = 0
        ms["after a copy"] = median {
            runBlocking {
                dao.insert(ClipboardEntry(text = "又复制了一条 ${n++}", timestamp = size + n.toLong()))
                dao.itemCount()
                dao.allEntries().load(PagingSource.LoadParams.Refresh(null, 48, false))
            }
        }
        ms["find (each copy)"] = median { runBlocking { check(dao.find("没有这一条") == null) } }
        ms["search"] = median {
            db.openHelper.readableDatabase.query(
                "SELECT id FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 AND text LIKE ?", arrayOf<Any>("%item/${size - 3}?%")
            ).use { while (it.moveToNext()) Unit }
        }
        // 清空全部 as ClipboardManager.deleteAll marks them, in its batches; then its undo
        val clear = ArrayList<Double>()
        val undo = ArrayList<Double>()
        repeat(RUNS + 1) { run ->
            runBlocking {
                val start = System.nanoTime()
                val ids = dao.findUnpinnedIds()
                db.withTransaction { for (b in idBatches(ids)) dao.markAsDeleted(*b) }
                check(dao.itemCount() < size / 100 + 2)
                val marked = System.nanoTime()
                db.withTransaction { for (b in idBatches(ids)) dao.undoDelete(*b) }
                val done = System.nanoTime()
                if (run > 0) {
                    clear += (marked - start) / 1e6
                    undo += (done - marked) / 1e6
                }
            }
        }
        ms["clear all"] = middle(clear)
        ms["undo"] = middle(undo)
        db.close()

        val dirs = UserDataManager.Dirs(
            sharedPrefs = File(root, "shared_prefs"),
            dataBases = databases,
            external = File(root, "external"),
            recentlyUsed = File(root, "recently_used"),
            engine = File(root, "engine"),
            config = File(root, "files/host-config"),
            data = File(root, "external/data"),
            theme = File(root, "external/theme"),
        )
        val nothing = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }
        ms["backup"] = median { UserDataManager.export(nothing, 0L, dirs).getOrThrow() }
        return Times(size, file.length() / 1024, ms)
    }
}
