/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.store.RecordStore
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Keeps a [UserModel] in [file], a [UserLog] kept by a [RecordStore]: [open] replays it, then
 * each sentence learned, or words forgotten, is appended as one write, and the log is compacted
 * to the model's counts now and then.
 */
class UserStore internal constructor(
    file: File,
    private val model: UserModel,
    compactAt: Long,
    onError: (IOException) -> Unit,
    openAppend: (File) -> OutputStream,
) : Closeable {
    constructor(
        file: File,
        model: UserModel,
        compactAt: Long = DEFAULT_COMPACT_AT,
        onError: (IOException) -> Unit = {},
    ) : this(file, model, compactAt, onError, { FileOutputStream(it, true) })

    private val store = RecordStore(file, UserLog.FORMAT, compactAt, onError, openAppend)

    /**
     * Loads the log into the model, and keeps what the model learns from now on. Once only.
     * A log with nothing learned in it is first filled by [seed], as [RecordStore.open] says.
     */
    fun open(seed: ((UserModel) -> Unit)? = null) {
        store.open(
            replay = { type, input -> UserLog.replay(type, input, model) },
            counts = { write ->
                model.forEachCount(
                    { entry, count -> write(UserLog.word(entry, count)) },
                    { first, second, count -> write(UserLog.pair(first, second, count)) },
                )
            },
            seed = seed?.let { { it(model) } },
        )
        model.journal = object : UserModel.Journal {
            override fun record(prev: UserModel.Entry?, sentence: List<UserModel.Entry>) = store.append(UserLog.sentence(prev, sentence))
            override fun forgot(words: List<UserModel.Entry>) = store.append(UserLog.forgot(words))
        }
    }

    /** Rewrites the log as the model's counts. */
    internal fun compact() = store.compact()

    override fun close() {
        model.journal = null
        store.close()
    }

    companion object {
        const val DEFAULT_COMPACT_AT = RecordStore.DEFAULT_COMPACT_AT
        const val UNREADABLE = RecordStore.UNREADABLE
    }
}
