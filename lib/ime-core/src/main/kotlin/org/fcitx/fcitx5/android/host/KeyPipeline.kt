/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText

/**
 * The engine's two places in the key pipeline: [keyEvent] at InputMethod
 * (`fcitx/instance.cpp:977-988`, `androidengine.cpp keyEvent`) and [filterKey] at ReservedLast,
 * reached only when nobody filtered the key (`:1013-1024`). [invokeAction] is a code row tap when no
 * temp mode holds it.
 */
interface KeyEngine {
    fun keyEvent(ic: InputContext, e: KeyEvent)
    fun filterKey(ic: InputContext, e: KeyEvent) {}
    fun invokeAction(ic: InputContext, e: InvokeActionEvent) {}
}

/**
 * A PostInputMethod key handler (fullwidth's key conversion, the modules' hotkeys), in order; it
 * sees releases too and ignores them itself, as each fcitx module does.
 */
fun interface PostKeyHook {
    fun onKey(ic: InputContext, e: KeyEvent)
}

/**
 * A ReservedFirst key handler: sees every key first, releases too (the input method name overlay
 * hides here on a press, `fcitx/instance.cpp:970-976`).
 */
fun interface PreKeyHook {
    fun onKey(ic: InputContext, e: KeyEvent)
}

/** `Instance::KeyEventResult` (`fcitx/instance.cpp:1022`): after the engine's `filterKey`, unfiltered keys only. */
fun interface KeyResultListener {
    fun onResult(ic: InputContext, e: KeyEvent)
}

/** A key the engine forwarded (`InputContextForwardKey`, PostInputMethod; `punctuation.cpp:249-254`). */
fun interface ForwardKeyListener {
    fun onForward(ic: InputContext, e: ForwardKeyEvent)
}

/** `Instance::CommitFilter`: rewrites a string before it is committed, in registration order. */
fun interface CommitFilter {
    fun filter(ic: InputContext, text: String): String
}

/** `Instance::OutputFilter`: rewrites preedit, aux, candidate and tip text, in registration order. */
fun interface OutputFilter {
    fun filter(ic: InputContext, text: FormattedText): FormattedText
}

/** Context events modules clear state on (punctuation on reset, `punctuation.cpp:287-302`). */
interface ContextListener {
    fun onReset(ic: InputContext) {}
    fun onFocusIn(ic: InputContext) {}
    fun onFocusOut(ic: InputContext) {}
}

/**
 * Where the pipeline's output goes. [commit], [deleteSurrounding] and [handBack] are sent the moment
 * they happen; [panel] once at the end of the event that changed it (`fcitx/inputcontext.cpp:433-466`,
 * `instance.cpp:1231-1244`), so a key that commits and then clears the preedit shows the commit first.
 */
interface HostOutput {
    fun commit(ic: InputContext, text: String, cursor: Int)
    fun deleteSurrounding(ic: InputContext, before: Int, after: Int) {}

    /**
     * A key the host did not take, for the application: [key] as it came in (not normalized),
     * press and release alike, with the physical key's [timestamp] to replay it, -1 for a forwarded
     * key (`androidfrontend.cpp:385-398`).
     */
    fun handBack(ic: InputContext, key: Key, release: Boolean, timestamp: Int)
    fun panel(ic: InputContext)

    /** The event ended, its panels sent: what else is sent once per event (the status area) goes now. */
    fun flushed() {}
}

/**
 * The key pipeline, fcitx's phases for a key event (`fcitx/instance.cpp:1651-1676`); at each phase
 * the handlers run in order and the first that filters ends it:
 *
 * 1. [KeyEvent] normalizes the key ([Key.normalize]);
 * 2. ReservedFirst: [preHooks];
 * 3. PreInputMethod: an active temp mode takes every key ([TempModeManager.keyEvent]);
 * 4. InputMethod: the [engine];
 * 5. PostInputMethod: temp mode triggers first ([TempModeManager.checkTrigger]), then [postHooks];
 * 6. ReservedLast: the engine's [KeyEngine.filterKey], then [resultListeners];
 * 7. a key nobody accepted, press or release, goes back to the application ([HostOutput.handBack]).
 *
 * Text to commit goes through [commitFilters] in order ([commit]); text to show through
 * [outputFilters] ([filterOutput]).
 *
 * Extension points for the modules are the lists; the order of each list is the registration order
 * fcitx has, see `specs/host.md`.
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

    /** A key from the frontend. Returns the event, with its flags as the pipeline left them. */
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

    /** A key the engine sends on: its listeners see it, then it goes to the application. */
    fun forwardKey(ic: InputContext, key: Key, release: Boolean) {
        val e = ForwardKeyEvent(key, release)
        forwardListeners.forEach { it.onForward(ic, e) }
        output.handBack(ic, key, release, -1)
    }

    /** A tap in the code row: an active temp mode's, else the engine's. */
    fun invokeAction(ic: InputContext, e: InvokeActionEvent): InvokeActionEvent = event {
        tempModes.invokeAction(ic, e)
        if (!e.filtered) engine.invokeAction(ic, e)
        e
    }

    /** `Instance::commitFilter` then `commitString`: [commitFilters] in order, then out at once. */
    fun commit(ic: InputContext, text: String, cursor: Int = -1) {
        output.commit(ic, commitFilters.fold(text) { acc, f -> f.filter(ic, acc) }, cursor)
    }

    fun deleteSurrounding(ic: InputContext, before: Int, after: Int) = output.deleteSurrounding(ic, before, after)

    /** `Instance::outputFilter`: [outputFilters] in order. */
    fun filterOutput(ic: InputContext, text: FormattedText): FormattedText =
        outputFilters.fold(text) { acc, f -> f.filter(ic, acc) }

    /** The panel changed; it is sent once when the current event ends (or now, outside one). */
    fun updatePanel(ic: InputContext) {
        dirtyPanels.add(ic)
        if (depth == 0) flush()
    }

    /** Reset: temp modes leave, then the [contextListeners] (`tempmodemanager.cpp:36-52`, `punctuation.cpp:287-302`). */
    fun reset(ic: InputContext) = event {
        tempModes.reset(ic)
        contextListeners.forEach { it.onReset(ic) }
    }

    fun focusIn(ic: InputContext) = event { contextListeners.forEach { it.onFocusIn(ic) } }

    fun focusOut(ic: InputContext) = event {
        tempModes.reset(ic)
        contextListeners.forEach { it.onFocusOut(ic) }
    }

    /** An input method switch on [ic]: temp modes leave (`tempmodemanager.cpp:47-49`). */
    fun switchedInputMethod(ic: InputContext) = event { tempModes.reset(ic) }

    /**
     * [block] as one event: the panels it marks go out once, when it ends (a candidate picked, a
     * timer's refine, a switch), as the pipeline's own entry points do.
     */
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
