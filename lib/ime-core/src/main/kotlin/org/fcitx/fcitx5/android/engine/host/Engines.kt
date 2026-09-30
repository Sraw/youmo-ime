/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import org.fcitx.fcitx5.android.engine.user.LibimeImport
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserStore
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The input methods the androidengine addon lists, by name, each with its session made on first
 * use. [load] gives a data file by its path in the app's assets; what the user picks in pinyin is
 * kept under [userDir] (in memory only if null). When that log is made afresh, what libime's
 * pinyin learned ([legacy], if the user had it) is read into it first.
 *
 * Everything here runs on one thread, the one fcitx runs on.
 */
class Engines(
    private val load: (String) -> ByteBuffer,
    private val userDir: File?,
    private val onError: (IOException) -> Unit = {},
    private val legacy: () -> LibimeImport.Legacy? = { null },
) : Closeable {

    // the files are signed with the app: no need to read them through for their checksums
    private val pinyinData by lazy(LazyThreadSafetyMode.NONE) { PinyinData.load(load(PINYIN_DATA), verify = false) }

    // floats rather than int8 as stored: 17 MB rather than 4.5, and on ART two and a half times faster;
    // dropped when turned off, loaded (or tried) again when turned on
    private var sentenceModel: SentenceModel? = null
    private var sentenceModelTried = false

    private fun sentenceModel(): SentenceModel? {
        if (sentenceModelTried) return sentenceModel
        sentenceModelTried = true
        sentenceModel = try {
            SentenceModel.load(load(SENTENCE_MODEL), unpack = true)
        } catch (e: IOException) {
            onError(e)
            null
        } catch (e: IllegalArgumentException) {
            // pinyin reads as well without it as before it was added
            onError(IOException("cannot read $SENTENCE_MODEL", e))
            null
        }
        return sentenceModel
    }

    private var store: UserStore? = null
    private val user by lazy(LazyThreadSafetyMode.NONE) {
        UserModel(pinyinData.dictionary, pinyinData.vocabulary).also { model ->
            if (userDir != null) {
                userDir.mkdirs()
                val file = File(userDir, USER_PINYIN)
                // a log that cannot be read or moved aside: learn in memory rather than not type
                store = try {
                    UserStore(file, model, onError = onError).apply { open(seed = ::importLegacy) }
                } catch (e: IOException) {
                    onError(e)
                    null
                }
            }
        }
    }

    /**
     * What libime learned, into a log with nothing in it yet. Whatever fails is reported and
     * pinyin goes on without it. Files that cannot be read leave the log empty, for the next
     * start to try again; a failure while learning them keeps what was learned before it.
     */
    private fun importLegacy(model: UserModel) {
        try {
            val old = legacy() ?: return
            LibimeImport.learn(
                model, pinyinData.dictionary, pinyinData.vocabulary,
                old.dictionary.asSequence(), old.history.asSequence(), old.decode,
            )
        } catch (e: IOException) {
            onError(e)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // old data, through native converters: no failure of theirs should stop typing
            onError(IOException("cannot import libime's data", e))
        } catch (e: LinkageError) {
            onError(IOException("cannot import libime's data", e))
        }
    }

    private val keyboards = HashMap<String, Keyboard>()
    private val sessions = HashMap<String, Session>()

    /** What the user set; the sessions are made again for a change, what was typed dropped. */
    var settings = EngineSettings()
        set(value) {
            if (value == field) return
            field = value
            keyboards.clear()
            sessions.clear()
            if (!value.sentenceModel) {
                sentenceModel = null
                sentenceModelTried = false
            }
        }

    /** [event] of [im]'s keyboard; nothing picked is learned unless [learning]. */
    fun onEvent(im: String, event: Int, arg: Int, learning: Boolean = true): Snapshot {
        val keyboard = keyboards[im] ?: Keyboard(session(im)).also { keyboards[im] = it }
        return keyboard.onEvent(event, arg, learning)
    }

    /** Candidates of [im]'s input, from the [from]th, at most [count]; none if it has no session yet. */
    fun candidates(im: String, from: Int, count: Int): List<Choice> = sessions[im]?.candidates(from, count).orEmpty()

    private fun session(im: String): Session = sessions.getOrPut(im) {
        val s = settings
        when (im) {
            PINYIN -> PinyinSession(
                pinyinData, PinyinSegmenter(s.fuzzy, s.typos, neighbours = s.typos),
                pageSize = s.pageSize, user = user, prediction = s.prediction, reranker = reranker(),
            )
            SHUANGPIN -> PinyinSession(
                pinyinData, ShuangpinSegmenter(s.scheme, s.fuzzy, s.typos), spell = true,
                pageSize = s.pageSize, user = user, prediction = s.prediction, reranker = reranker(),
            )
            else -> {
                val (file, options) = TABLES[im] ?: throw IllegalArgumentException("no input method $im")
                val table = TableDictionary(CodeTable.load(load("$TABLE_DIR/$file"), verify = false))
                // looking a character up by pinyin learns nothing: it is not how the user writes
                val lookUp = if (options.pinyinKey == null) null else PinyinSession(pinyinData, PinyinSegmenter(), prediction = false)
                TableSession(table, options.copy(pageSize = s.pageSize), lookUp)
            }
        }
    }

    /** A reranker of the session's own, over the one model: each keeps what it ran for its input. */
    private fun reranker(): Reranker? = if (settings.sentenceModel) sentenceModel()?.let { Reranker(it) } else null

    override fun close() {
        store?.close()
        store = null
    }

    companion object {
        const val PINYIN = "engine-pinyin"
        const val SHUANGPIN = "engine-shuangpin"

        const val PINYIN_DATA = "engine/pinyin.data"
        const val SENTENCE_MODEL = "engine/sentence-model.safetensors"
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
