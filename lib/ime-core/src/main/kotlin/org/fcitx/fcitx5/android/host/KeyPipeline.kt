/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText

/** [keyEvent] at InputMethod, [filterKey] at ReservedLast when nobody filtered the key; [invokeAction] a code row tap. */
interface KeyEngine {
    fun keyEvent(ic: InputContext, e: KeyEvent)
    fun filterKey(ic: InputContext, e: KeyEvent) {}
    fun invokeAction(ic: InputContext, e: InvokeActionEvent) {}
}

/** PostInputMethod; sees releases too and ignores them itself, as each fcitx module does. */
fun interface PostKeyHook {
    fun onKey(ic: InputContext, e: KeyEvent)
}

/** ReservedFirst: every key first, releases too (the name overlay hides here). */
fun interface PreKeyHook {
    fun onKey(ic: InputContext, e: KeyEvent)
}

/** `Instance::KeyEventResult`: after the engine's `filterKey`, unfiltered keys only. */
fun interface KeyResultListener {
    fun onResult(ic: InputContext, e: KeyEvent)
}

/** `InputContextForwardKey`, PostInputMethod. */
fun interface ForwardKeyListener {
    fun onForward(ic: InputContext, e: ForwardKeyEvent)
}

fun interface CommitFilter {
    fun filter(ic: InputContext, text: String): String
}

/** Returns [text] itself when unchanged: unrewritten candidates are then not joined back ([HostCore.filterOutput]). */
fun interface OutputFilter {
    fun filter(ic: InputContext, text: FormattedText): FormattedText
}

interface ContextListener {
    fun onReset(ic: InputContext) {}
    fun onFocusIn(ic: InputContext) {}
    fun onFocusOut(ic: InputContext) {}
}

/** [commit], [deleteSurrounding] and [handBack] go at once; [panel] at the event's end, so a commit shows before a cleared preedit. */
interface HostOutput {
    fun commit(ic: InputContext, text: String, cursor: Int)
    fun deleteSurrounding(ic: InputContext, before: Int, after: Int) {}

    /** As it came in (not normalized); [timestamp] -1 for a forwarded key. */
    fun handBack(ic: InputContext, key: Key, release: Boolean, timestamp: Int)
    fun panel(ic: InputContext)

    fun flushed() {}
}

/**
 * fcitx's key phases; in each the first handler that filters ends it: normalize, ReservedFirst [preHooks],
 * PreInputMethod temp mode, InputMethod [engine], PostInputMethod temp triggers then [postHooks], ReservedLast
 * [KeyEngine.filterKey] then [resultListeners]; an unaccepted key goes back ([HostOutput.handBack]).
 * List order is fcitx's registration order (`specs/host.md`).
 */
@Suppress("LongParameterList")
class KeyPipeline(
    private val engine: KeyEngine,
    private val output: HostOutput,
    private val tempModes: TempModeManager = TempModeManager(emptyList()),
    private val preHooks: List<PreKeyHook> = emptyList(),
    private val postHooks: List<PostKeyHook> = emptyList(),
    private val resultListeners: List<KeyResultListener> = emptyList(),
    private val forwardListeners: List<ForwardKeyListener> = emptyList(),
    private val commitFilters: List<CommitFilter> = emptyList(),
    private val outputFilters: List<OutputFilter> = emptyList(),
    private val contextListeners: List<ContextListener> = emptyList(),
) {
    private val dirtyPanels = LinkedHashSet<InputContext>()
    private var depth = 0

    fun dispatch(ic: InputContext, e: KeyEvent): KeyEvent = event {
        run(ic, e)
        if (!e.accepted) output.handBack(ic, e.origKey, e.release, e.timestamp)
        e
    }

    private fun run(ic: InputContext, e: KeyEvent) {
        for (hook in preHooks) {
            hook.onKey(ic, e)
            if (e.filtered) return
        }
        tempModes.keyEvent(ic, e)
        if (e.filtered) return
        engine.keyEvent(ic, e)
        if (e.filtered) return
        tempModes.checkTrigger(ic, e)
        if (e.filtered) return
        for (hook in postHooks) {
            hook.onKey(ic, e)
            if (e.filtered) return
        }
        engine.filterKey(ic, e)
        resultListeners.forEach { it.onResult(ic, e) }
    }

    fun forwardKey(ic: InputContext, key: Key, release: Boolean) {
        val e = ForwardKeyEvent(key, release)
        forwardListeners.forEach { it.onForward(ic, e) }
        output.handBack(ic, key, release, -1)
    }

    fun invokeAction(ic: InputContext, e: InvokeActionEvent): InvokeActionEvent = event {
        tempModes.invokeAction(ic, e)
        if (!e.filtered) engine.invokeAction(ic, e)
        e
    }

    fun commit(ic: InputContext, text: String, cursor: Int = -1) {
        output.commit(ic, commitFilters.fold(text) { acc, f -> f.filter(ic, acc) }, cursor)
    }

    fun commitAsIs(ic: InputContext, text: String) = output.commit(ic, text, -1)

    fun deleteSurrounding(ic: InputContext, before: Int, after: Int) = output.deleteSurrounding(ic, before, after)

    fun filterOutput(ic: InputContext, text: FormattedText): FormattedText =
        outputFilters.fold(text) { acc, f -> f.filter(ic, acc) }

    fun updatePanel(ic: InputContext) {
        dirtyPanels.add(ic)
        if (depth == 0) flush()
    }

    fun reset(ic: InputContext) = event {
        tempModes.reset(ic)
        contextListeners.forEach { it.onReset(ic) }
    }

    fun focusIn(ic: InputContext) = event { contextListeners.forEach { it.onFocusIn(ic) } }

    fun focusOut(ic: InputContext) = event {
        tempModes.reset(ic)
        contextListeners.forEach { it.onFocusOut(ic) }
    }

    fun switchedInputMethod(ic: InputContext) = event { tempModes.reset(ic) }

    /** The panels it marks go out once, when it ends. */
    fun <T> batch(block: () -> T): T = event(block)

    private inline fun <T> event(block: () -> T): T {
        depth++
        try {
            return block()
        } finally {
            depth--
            if (depth == 0) flush()
        }
    }

    private fun flush() {
        while (dirtyPanels.isNotEmpty()) {
            val ic = dirtyPanels.first()
            dirtyPanels.remove(ic)
            output.panel(ic)
        }
        output.flushed()
    }
}
