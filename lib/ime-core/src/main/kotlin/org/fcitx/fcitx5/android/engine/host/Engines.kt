/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserStore
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The input methods the androidengine addon lists, by name, each with its session made on first
 * use. [load] gives a data file by its path in the app's assets; what the user picks in pinyin is
 * kept under [userDir] (in memory only if null).
 *
 * Everything here runs on one thread, the one fcitx runs on.
 */
class Engines(
    private val load: (String) -> ByteBuffer,
    private val userDir: File?,
    private val shuangpin: ShuangpinScheme = ShuangpinScheme.ZIRANMA,
    private val onError: (IOException) -> Unit = {},
) : Closeable {

    // the files are signed with the app: no need to read them through for their checksums
    private val pinyinData by lazy(LazyThreadSafetyMode.NONE) { PinyinData.load(load(PINYIN_DATA), verify = false) }

    private var store: UserStore? = null
    private val user by lazy(LazyThreadSafetyMode.NONE) {
        UserModel(pinyinData.dictionary, pinyinData.vocabulary).also { model ->
            if (userDir != null) {
                userDir.mkdirs()
                val opened = UserStore(File(userDir, USER_PINYIN), model, onError = onError)
                // a log that cannot be read or moved aside: learn in memory rather than not type
                store = try {
                    opened.apply { open() }
                } catch (e: IOException) {
                    onError(e)
                    null
                }
            }
        }
    }

    private val keyboards = HashMap<String, Keyboard>()
    private val sessions = HashMap<String, Session>()

    /** [event] of [im]'s keyboard; nothing picked is learned unless [learning]. */
    fun onEvent(im: String, event: Int, arg: Int, learning: Boolean = true): Snapshot {
        val keyboard = keyboards[im] ?: Keyboard(session(im)).also { keyboards[im] = it }
        return keyboard.onEvent(event, arg, learning)
    }

    /** Candidates of [im]'s input, from the [from]th, at most [count]; none if it has no session yet. */
    fun candidates(im: String, from: Int, count: Int): List<Choice> = sessions[im]?.candidates(from, count).orEmpty()

    private fun session(im: String): Session = sessions.getOrPut(im) {
        when (im) {
            PINYIN -> PinyinSession(pinyinData, PinyinSegmenter(), user = user)
            SHUANGPIN -> PinyinSession(pinyinData, ShuangpinSegmenter(shuangpin), spell = true, user = user)
            else -> {
                val (file, options) = TABLES[im] ?: throw IllegalArgumentException("no input method $im")
                val table = TableDictionary(CodeTable.load(load("$TABLE_DIR/$file"), verify = false))
                // looking a character up by pinyin learns nothing: it is not how the user writes
                val lookUp = if (options.pinyinKey == null) null else PinyinSession(pinyinData, PinyinSegmenter())
                TableSession(table, options, lookUp)
            }
        }
    }

    override fun close() {
        store?.close()
        store = null
    }

    companion object {
        const val PINYIN = "engine-pinyin"
        const val SHUANGPIN = "engine-shuangpin"

        const val PINYIN_DATA = "engine/pinyin.data"
        const val TABLE_DIR = "engine/table"
        const val USER_PINYIN = "pinyin.user"

        /** Each table input method: its file, and how it behaves. */
        val TABLES: Map<String, Pair<String, TableOptions>> = mapOf(
            "engine-wubi" to ("wbx.data" to TableOptions.WUBI),
            "engine-cangjie" to ("cj.data" to TableOptions.CANGJIE),
            "engine-ziranma" to ("zrm.data" to TableOptions()),
            "engine-erbi" to ("erbi.data" to TableOptions()),
        )
    }
}
