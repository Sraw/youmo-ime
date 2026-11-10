/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.data.pack.AppData
import org.fcitx.fcitx5.android.handwriting.OrtModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * The polish tagger (060-voice-tagger.md: ELECTRA-small, int8, 21 MB): its model mapped from the
 * voice pack (`voice/polish-tagger.ort` through AppData, [OrtModel]) and its lists (`polish-tagger.json`: the
 * characters, the ops, the marks after and the number classes). A [VoicePolishStream.Tagger] for [stream], run on the
 * thread that asks, the recognizer's; [close] when done.
 */
class VoicePolisher private constructor(
    private val model: OrtModel,
    private val vocab: VoicePolishVocab,
    private val tags: VoicePolishTags,
) : VoicePolishStream.Tagger, AutoCloseable {

    /** A new hold's: K and the window as measured (VoicePolishStream's). */
    fun stream() = VoicePolishStream(tags, this)

    override fun labels(text: IntArray): VoicePolishStream.Labels {
        if (text.isEmpty()) return VoicePolishStream.Labels(IntArray(0), IntArray(0))
        val ids = vocab.encode(text)
        // by position, as exported (op, after, num): 44, 28 and 9 wide, so out of order, rows() fails loudly
        val (op, after, num) = model.runLongs(ids, longArrayOf(1, ids.size.toLong()))
        return VoicePolishStream.Labels(
            rows(op, text.size, tags.ops.size),
            rows(after, text.size, tags.afters.size),
            rows(num, text.size, tags.nums.size),
        )
    }

    // the text's rows of [out] ([1, n, width]): the export leaves [CLS] and [SEP]'s out already (voice-eval tagger.py)
    private fun rows(out: OrtModel.Output, n: Int, width: Int): IntArray {
        check(out.shape.contentEquals(longArrayOf(1, n.toLong(), width.toLong()))) { "output of shape ${out.shape.toList()}, not [1, $n, $width]" }
        return VoicePolishVocab.best(out.values, n, width)
    }

    override fun close() = model.close()

    companion object {
        private const val DIR = "voice"
        // two of the phone's cores: some 70 ms a step on a Snapdragon 845 (060-voice-tagger.md §3)
        private const val THREADS = 2

        /** Read in from the voice pack (AppData); off the main thread. */
        fun open(): VoicePolisher {
            val json = JSONObject(AppData.open("$DIR/polish-tagger.json").bufferedReader().use { it.readText() })
            val vocab = VoicePolishVocab(json.getJSONArray("vocab").strings(), json.getLong("cls"), json.getLong("sep"), json.getLong("unk"))
            val tags = VoicePolishTags(json.getJSONArray("ops").strings(), json.getJSONArray("after").strings(), json.getJSONArray("num").strings())
            return VoicePolisher(OrtModel.open(AppData.map("$DIR/polish-tagger.ort"), THREADS), vocab, tags)
        }

        private fun JSONArray.strings() = List(length()) { getString(it) }
    }
}
