/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.device.CorePin
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.rerank.MatrixKernel
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** The 高档 gate's threads and what a key's result may hang on. */
class EnginesPickingTest : EnginesFixture() {
    /** Given the user's data and settings, what a key shows hangs on the text before and the keys alone: at 高档 the large model asked at some keys, not at others. */
    @Uat("PY-162")
    @Test
    fun whatAKeyShowsHangsOnTheTextBeforeAndTheKeysAlone() {
        // 好 far ahead of 号, which the gate leaves to the small model; 你 and 拟 close, which it sends to the large
        pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("你", -2f, 0f).entry("你", syl("ni"))
            .unigram("拟", -2.05f, 0f).entry("拟", syl("ni"))
            .unigram("好", -2f, 0f).entry("好", syl("hao"))
            .unigram("号", -6f, 0f).entry("号", syl("hao"))
            .build()
            .toByteArray()
        val im = InputMethodCatalog.PINYIN
        // each prefix typed at once in a new session after the same text, the large model read ahead
        fun replayed(before: String, keys: String): List<String> {
            val fresh = Engines(::load, null, warmUps = inPlace)
            fresh.context(im, before)
            return fresh.type(im, keys).candidates
        }
        // the large model never read ahead, so read at the first key the gate sends it; a commit's context read ahead in place
        val read = ArrayList<String>()
        val engines = Engines({ read += it; load(it) }, null, warmUps = {}, contextReads = inPlace)
        var before = "好"
        engines.context(im, before)
        val sent = ArrayList<Boolean>()
        for (segment in listOf("hao", "nini", "haoni")) {
            for (n in 1..segment.length) {
                val had = InputMethodCatalog.LARGE_MODEL in read
                val typed = engines.onEvent(im, EngineEvent.CHAR, segment[n - 1].code)
                sent += !had && InputMethodCatalog.LARGE_MODEL in read
                // the cursor to the start and back to the end
                engines.onEvent(im, EngineEvent.CURSOR, 0)
                val shown = engines.onEvent(im, EngineEvent.CURSOR, typed.preedit.codePointCount(0, typed.preedit.length))
                assertEquals("$before|${segment.take(n)}", replayed(before, segment.take(n)), shown.candidates)
            }
            before += engines.onEvent(im, EngineEvent.PICK, 0, learning = false).commit
            // as the host tells the field's text after a commit: read ahead before the next key
            engines.context(im, before)
        }
        assertTrue("$sent", sent.take("hao".length).none { it } && sent.any { it })
        assertTrue(replayed("", "hao").containsAll(listOf("好", "号")))
    }

    /** Within the pin a thread's cores are set, and a thread started there would keep them: no work is handed on from it. */
    @Test
    fun nothingWithinThePinHandsWorkOnToAnotherThread() {
        val pinned = ThreadLocal.withInitial { false }
        var entered = 0
        val pin = object : CorePin {
            override fun <T> run(block: () -> T): T {
                val was = pinned.get()
                pinned.set(true)
                entered++
                try {
                    return block()
                } finally {
                    pinned.set(was)
                }
            }
        }
        val kernel = object : MatrixKernel by MatrixKernel.JVM {
            override val pin = pin
        }
        val queued = ArrayList<Runnable>()
        var runQueued = true
        val sentinel = Executor { task ->
            check(!pinned.get()) { "work handed on from within the pin" }
            if (runQueued) task.run() else queued += task
        }
        val im = InputMethodCatalog.PINYIN
        Engines(::load, MemoryFiles(), kernel = kernel, warmUps = sentinel, contextReads = sentinel, background = sentinel).use { e ->
            e.settings = wubiShown
            // the large model not read yet: read at the first key the gate sends it
            runQueued = false
            e.context(im, "好")
            e.type(im, "nihaoni")
            e.onEvent(im, EngineEvent.PICK, 0)
            e.context(im, "好你")
            e.type(im, "ni")
            // read ahead, and dropped on the way down and read anew on the way up
            runQueued = true
            queued.toList().forEach { it.run() }
            e.onEvent(im, EngineEvent.RESET, 0)
            e.settings = EngineSettings(modelLevel = ModelLevel.MEDIUM, tables = wubiShown.tables)
            e.type(InputMethodCatalog.T9, "6464")
            e.onEvent(InputMethodCatalog.T9, EngineEvent.PICK, 0)
            e.settings = wubiShown
            e.type(InputMethodCatalog.SHUANGPIN, "nihk")
            e.onEvent(InputMethodCatalog.SHUANGPIN, EngineEvent.PICK, 0)
            e.type(InputMethodCatalog.WUBI, "kkkk")
            e.onEvent(InputMethodCatalog.WUBI, EngineEvent.PICK, 1)
            e.onEvent(im, EngineEvent.RESET, 0)
            e.onEvent(im, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(0, InkReading(listOf("你", "拟"), doubleArrayOf(0.0, -0.1))))
            e.onEvent(im, EngineEvent.PICK, 0)
        }
        assertTrue(entered > 0)
    }
}
