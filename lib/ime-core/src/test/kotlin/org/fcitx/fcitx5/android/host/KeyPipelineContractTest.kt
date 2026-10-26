/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key pipeline's contract (design §1.0). Lines: `fcitx/…` is
 * upstream fcitx5 442edbc9 `src/lib/fcitx/…`, `ca/…` is upstream fcitx5-chinese-addons 0d3fd040 `modules/…`
 * (`lib/ime-core/tools/upstream.sh fcitx5|chinese-addons`).
 */
class KeyPipelineContractTest {

    private val log = mutableListOf<String>()

    private val output = object : HostOutput {
        override fun commit(ic: InputContext, text: String, cursor: Int) {
            log += "commit $text"
        }

        override fun deleteSurrounding(ic: InputContext, before: Int, after: Int) {
            log += "delete $before $after"
        }

        override fun handBack(ic: InputContext, key: Key, release: Boolean, timestamp: Int) {
            log += "back ${key.sym.toString(16)} ${key.states} ${if (release) "up" else "down"} $timestamp"
        }

        override fun panel(ic: InputContext) {
            log += "panel"
        }
    }

    private val contexts = InputContexts(ImGroup.of("Default", listOf("pinyin", ImGroup.KEYBOARD_US)), { _, _, _, _ -> })
    private val ic = contexts.activate(1).also { contexts.focus(it, true) }

    /** An engine that takes the letters it is told to, filtering and accepting them as androidengine does. */
    private open inner class Engine(val takes: (KeyEvent) -> Boolean = { false }) : KeyEngine {
        override fun keyEvent(ic: InputContext, e: KeyEvent) {
            log += "engine ${e.key.sym.toString(16)}"
            if (takes(e)) e.filterAndAccept()
        }

        override fun filterKey(ic: InputContext, e: KeyEvent) {
            log += "filterKey"
        }
    }

    /**
     * Punctuation's "last was a letter or digit" (`ca/punctuation/punctuation.cpp:217-302`), built on
     * the pipeline's hooks as the S2 module will be: commit filter, key result, forwarded key, reset.
     */
    private class LastChar : CommitFilter, KeyResultListener, ForwardKeyListener, ContextListener {
        var last = 0

        override fun filter(ic: InputContext, text: String): String {
            last = text.lastOrNull()?.takeIf { it.isLetterOrDigit() && it.code < 0x80 }?.code ?: 0
            return text
        }

        override fun onResult(ic: InputContext, e: KeyEvent) = seen(e.key, e.release, e.accepted)
        override fun onForward(ic: InputContext, e: ForwardKeyEvent) = seen(e.key, e.release, false)

        private fun seen(key: Key, release: Boolean, accepted: Boolean) {
            if (release || accepted) return
            last = if (key.isLAZ || key.isUAZ || key.isDigit) KeySyms.toUnicode(key.sym) else 0
        }

        override fun onReset(ic: InputContext) {
            last = 0
        }
    }

    private fun press(sym: Int, states: Int = 0, timestamp: Int = -1) = KeyEvent(Key(sym, states), release = false, timestamp = timestamp)

    @Test
    fun `reset clears the punctuation state`() {
        // ca/punctuation/punctuation.cpp:287-302: InputContextReset at PostInputMethod clears lastIsEngOrDigit_
        val punc = LastChar()
        val p = KeyPipeline(Engine(), output, resultListeners = listOf(punc), contextListeners = listOf(punc))
        p.dispatch(ic, press('a'.code))
        assertEquals('a'.code, punc.last)
        p.reset(ic)
        assertEquals(0, punc.last)
    }

    @Test
    fun `a forwarded key updates the last character and goes to the application`() {
        // ca/punctuation/punctuation.cpp:249-254 (ForwardKey at PostInputMethod); native-last:app/src/main/cpp/androidfrontend/androidfrontend.cpp:394-398 (-1)
        val punc = LastChar()
        val p = KeyPipeline(Engine(), output, forwardListeners = listOf(punc))
        punc.last = 'a'.code
        p.forwardKey(ic, Key(0xff51), release = false)
        assertEquals(0, punc.last)
        p.forwardKey(ic, Key('7'.code), release = false)
        assertEquals('7'.code, punc.last)
        assertEquals(listOf("back ff51 0 down -1", "back 37 0 down -1"), log)
    }

    private inner class Mode : TempMode {
        var active = false
        var resets = 0
        override fun isActive(ic: InputContext) = active

        override fun trigger(ic: InputContext, e: KeyEvent): Boolean {
            // quickphrase.h:35-39: Super+grave
            if (e.release || e.key != Key('`'.code, KeyStates.SUPER)) return false
            active = true
            return true
        }

        override fun keyEvent(ic: InputContext, e: KeyEvent): Boolean {
            log += "mode ${e.key.sym.toString(16)}"
            return e.key.isLAZ
        }

        override fun invokeAction(ic: InputContext, e: InvokeActionEvent) = true

        override fun reset(ic: InputContext) {
            active = false
            resets++
        }
    }

    @Test
    fun `an active temp mode gets every key and the engine none`() {
        // fcitx/tempmodemanager.cpp:63-72: filter, accept only what the mode used
        val mode = Mode().apply { active = true }
        val results = mutableListOf<KeyEvent>()
        val p = KeyPipeline(Engine { true }, output, TempModeManager(listOf(mode)), resultListeners = listOf(KeyResultListener { _, e -> results += e }))
        p.dispatch(ic, press('a'.code))
        p.dispatch(ic, press(KeySyms.RETURN))
        // Return was filtered but not used: back to the application; no ReservedLast for either
        assertEquals(listOf("mode 61", "mode ff0d", "back ff0d 0 down -1"), log)
        assertTrue(results.isEmpty())
        // invoke actions too (:74-82)
        assertTrue(p.invokeAction(ic, InvokeActionEvent(InvokeActionEvent.Action.LeftClick, 0)).accepted)
    }

    @Test
    fun `a temp mode starts at PostInputMethod and leaves on focus out, reset and switch`() {
        // fcitx/tempmodemanager.cpp:23-25,54-61 (trigger, Default phase); :28-52 (reset)
        val mode = Mode()
        val p = KeyPipeline(Engine(), output, TempModeManager(listOf(mode)))
        val e = p.dispatch(ic, press('`'.code, KeyStates.SUPER))
        assertTrue(mode.active)
        assertTrue(e.filtered && e.accepted)
        // the engine saw the trigger first (InputMethod before PostInputMethod), ReservedLast did not
        assertEquals(listOf("engine 60"), log)
        p.focusOut(ic)
        assertFalse(mode.active)
        mode.active = true
        p.reset(ic)
        mode.active = true
        p.switchedInputMethod(ic)
        assertEquals(3, mode.resets)
    }

    @Test
    fun `fullwidth commits a fullwidth space for a key the engine did not take`() {
        // ca/fullwidth/fullwidth.cpp:37-53: accept (not filter) and commit sCornerTrans[key - 32]; space is U+3000
        // the hook commits through the pipeline it is part of
        lateinit var p: KeyPipeline
        val fullwidth = PostKeyHook { ic, e ->
            if (e.release || e.key.states != 0) return@PostKeyHook
            if (e.key.sym == KeySyms.SPACE) {
                e.accept()
                p.commit(ic, "　")
            }
        }
        val results = mutableListOf<Boolean>()
        p = KeyPipeline(Engine(), output, postHooks = listOf(fullwidth), resultListeners = listOf(KeyResultListener { _, e -> results += e.accepted }))
        p.dispatch(ic, press(KeySyms.SPACE))
        // accepted, not filtered: ReservedLast still runs and sees it accepted; nothing handed back
        assertEquals(listOf("engine 20", "commit 　", "filterKey"), log)
        assertEquals(listOf(true), results)
        // the release is not taken: back to the application
        p.dispatch(ic, KeyEvent(Key(KeySyms.SPACE), release = true))
        assertEquals("back 20 0 up -1", log.last())
    }

    @Test
    fun `ctrl period toggles punctuation only on a chinese input method`() {
        // ca/punctuation/punctuation.cpp:255-276: only inWhiteList contexts (the status area has the
        // action, i.e. a Chinese input method); filterAndAccept ends the key
        var enabled = true
        val chinese = { c: InputContext -> contexts.inputMethod(c) != ImGroup.KEYBOARD_US }
        val hotkey = PostKeyHook { c, e ->
            if (!e.release && chinese(c) && e.key == Key('.'.code, KeyStates.CTRL)) {
                enabled = !enabled
                e.filterAndAccept()
            }
        }
        val p = KeyPipeline(Engine(), output, postHooks = listOf(hotkey))
        p.dispatch(ic, press('.'.code, KeyStates.CTRL or KeyStates.CAPS_LOCK))
        assertFalse(enabled)
        assertEquals(listOf("engine 2e"), log)
        contexts.toggle(ic)
        assertEquals(ImGroup.KEYBOARD_US, contexts.inputMethod(ic))
        p.dispatch(ic, press('.'.code, KeyStates.CTRL))
        assertFalse(enabled)
        assertEquals("back 2e 4 down -1", log.last())
    }

    @Test
    fun `a release goes back as it came, with its timestamp`() {
        // native-last:app/src/main/cpp/androidfrontend/androidfrontend.cpp:385-392: an unaccepted key, release included, goes back with the raw key
        val p = KeyPipeline(Engine { !it.release }, output)
        p.dispatch(ic, press('a'.code, KeyStates.SHIFT, timestamp = 41))
        p.dispatch(ic, KeyEvent(Key('a'.code, KeyStates.SHIFT or KeyStates.CAPS_LOCK), release = true, timestamp = 42))
        // the engine matched on the normalized key, the application gets Shift and CapsLock back
        assertEquals(listOf("engine 61", "engine 61", "filterKey", "back 61 3 up 42"), log)
    }

    @Test
    fun `commit filters run punctuation, fullwidth, chttrans in that order`() {
        // registration order: ca/punctuation/punctuation.cpp:217-228, ca/fullwidth/fullwidth.cpp:84-103, ca/chttrans/chttrans.cpp:185-194
        val punc = LastChar()
        val fullwidth = CommitFilter { _, s -> s.map { if (it.code in 0x21..0x7e) (it.code + 0xfee0).toChar() else it }.joinToString("") }
        val chttrans = CommitFilter { _, s -> s.replace("发", "發") }
        val p = KeyPipeline(Engine(), output, commitFilters = listOf(punc, fullwidth, chttrans))
        p.commit(ic, "发a")
        // punctuation saw the string before fullwidth changed its last letter
        assertEquals('a'.code, punc.last)
        assertEquals(listOf("commit 發ａ"), log)
        // fullwidth leaves the space alone (wc > 32, fullwidth.cpp:95)
        p.commit(ic, "a b")
        assertEquals("commit ａ ｂ", log.last())
    }

    @Test
    fun `output filter converts the whole text and cuts it back into its segments`() {
        // ca/chttrans/chttrans.cpp:133-183
        val f = WholeTextFilter({ true }) { it.replace("后来", "後來").replace("里", "裡") }
        val p = KeyPipeline(Engine(), output, outputFilters = listOf(f))
        // a phrase across two segments converts as one, each keeps its flags
        val text = FormattedText(arrayOf("后", "来里"), intArrayOf(8, 0), 2)
        val out = p.filterOutput(ic, text)
        assertEquals(listOf("後", "來裡"), out.strings.toList())
        assertEquals(listOf(8, 0), out.flags.toList())
        assertEquals(2, out.cursor)
        // characters, not chars: a surrogate pair is one, the cursor keeps its count of characters
        val emoji = FormattedText(arrayOf("😀后", "来"), intArrayOf(0, 8), 3)
        val o2 = f.filter(ic, emoji)
        assertEquals(listOf("😀後", "來"), o2.strings.toList())
        assertEquals(3, o2.cursor)
        // shorter: the last segments shrink; longer: past the old length is dropped, as in fcitx
        val shrink = WholeTextFilter({ true }) { "x" }.filter(ic, FormattedText(arrayOf("ab", "c"), intArrayOf(0, 0), 3))
        assertEquals(listOf("x", ""), shrink.strings.toList())
        assertEquals(1, shrink.cursor)
        val grow = WholeTextFilter({ true }) { "wxyz" }.filter(ic, FormattedText(arrayOf("a", "b"), intArrayOf(0, 0), -1))
        assertEquals(listOf("w", "x"), grow.strings.toList())
        assertEquals(-1, grow.cursor)
        // one segment is kept whole; not applying or empty leaves the text alone
        assertEquals(listOf("後來"), f.filter(ic, FormattedText(arrayOf("后来"), intArrayOf(0), 0)).strings.toList())
        val untouched = FormattedText(arrayOf("后"), intArrayOf(0), 1)
        assertTrue(WholeTextFilter({ false }) { "x" }.filter(ic, untouched) === untouched)
        assertTrue(f.filter(ic, FormattedText.Empty) === FormattedText.Empty)
    }

    @Test
    fun `key event result fires only for keys nobody filtered`() {
        // fcitx/instance.cpp:1013-1024 (ReservedLast) and :1665-1673 (stop at the first filter)
        val seen = mutableListOf<Int>()
        val p = KeyPipeline(Engine { it.key.sym == 'a'.code }, output, resultListeners = listOf(KeyResultListener { _, e -> seen += e.key.sym }))
        p.dispatch(ic, press('a'.code))
        p.dispatch(ic, press('b'.code))
        assertEquals(listOf('b'.code), seen)
        assertEquals(listOf("engine 61", "engine 62", "filterKey", "back 62 0 down -1"), log)
    }

    @Test
    fun `a post hook that filters stops the hooks after it`() {
        // fcitx/instance.cpp:1665-1670: within a phase too, the first filter ends the key
        val p = KeyPipeline(
            Engine(), output,
            postHooks = listOf(PostKeyHook { _, e -> log += "hook1"; e.filter() }, PostKeyHook { _, _ -> log += "hook2" }),
        )
        p.dispatch(ic, press('a'.code))
        // filtered but not accepted: handed back
        assertEquals(listOf("engine 61", "hook1", "back 61 0 down -1"), log)
    }

    @Test
    fun `reserved first sees presses and releases before anything`() {
        // fcitx/instance.cpp:970-976: the name overlay hides on a press
        var hidden = 0
        val overlay = PreKeyHook { _, e -> if (!e.release) hidden++ }
        val p = KeyPipeline(Engine(), output, preHooks = listOf(overlay, PreKeyHook { _, e -> e.filterAndAccept() }, PreKeyHook { _, _ -> log += "never" }))
        p.dispatch(ic, press('a'.code))
        p.dispatch(ic, KeyEvent(Key('a'.code), release = true))
        assertEquals(1, hidden)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `commits go out at once, the panel once at the end of the event`() {
        // fcitx/inputcontext.cpp:433-466, fcitx/instance.cpp:1231-1244: commit immediately, panel coalesced
        lateinit var pipe: KeyPipeline
        val engine = object : KeyEngine {
            override fun keyEvent(ic: InputContext, e: KeyEvent) {
                pipe.updatePanel(ic)
                pipe.commit(ic, "你")
                pipe.deleteSurrounding(ic, 1, 0)
                pipe.updatePanel(ic)
                e.filterAndAccept()
            }

            override fun invokeAction(ic: InputContext, e: InvokeActionEvent) {
                pipe.updatePanel(ic)
                e.accept()
            }
        }
        pipe = KeyPipeline(engine, output)
        pipe.dispatch(ic, press('a'.code))
        assertEquals(listOf("commit 你", "delete 1 0", "panel"), log)
        log.clear()
        assertTrue(pipe.invokeAction(ic, InvokeActionEvent(InvokeActionEvent.Action.LeftClick, 1)).accepted)
        pipe.focusIn(ic)
        // outside an event the panel goes at once
        pipe.updatePanel(ic)
        assertEquals(listOf("panel", "panel"), log)
    }
}
