/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * Why the input method changed, fcitx's `InputMethodSwitchedReason` (`fcitx/event.h:42-59`); the
 * order is the app's `SwitchInputMethodEvent.Reason`, which reads it by ordinal.
 */
enum class SwitchReason { Trigger, Deactivate, AltTrigger, Activate, Enumerate, GroupChange, CapabilityChanged, Other }

/**
 * An input method group: the enabled input methods in order and the group's default one, fcitx's
 * `InputMethodGroup`. The first is what a context starts on ([InputState.active] false); the default
 * is what it toggles to.
 */
class ImGroup private constructor(val name: String, val inputMethods: List<String>, val defaultInputMethod: String) {

    /**
     * `InputMethodGroup::setDefaultInputMethod` (`fcitx/inputmethodgroup.cpp:93-114`): the default is
     * never the first while there is a second (it would make toggling a no-op), and one not in the
     * list falls back to the second, or the first of a one-item list.
     */
    fun withDefault(im: String): ImGroup {
        val default = when {
            im in inputMethods -> if (inputMethods.size > 1 && inputMethods[0] == im) inputMethods[1] else im
            inputMethods.size > 1 -> inputMethods[1]
            else -> inputMethods.firstOrNull().orEmpty()
        }
        return ImGroup(name, inputMethods, default)
    }

    /**
     * `InputMethodManager::setGroup` (`fcitx/inputmethodmanager.cpp:336-363`) with a new list, as
     * `setEnabledInputMethods` builds it (`native-lib.cpp:196-206`): input methods not [available] are
     * dropped, and the old default is kept when it is still in the list.
     */
    fun withInputMethods(list: List<String>, available: Set<String>): ImGroup =
        ImGroup(name, list.filter { it in available }, "").withDefault(defaultInputMethod)

    companion object {
        const val KEYBOARD_US = "keyboard-us"

        fun of(name: String, inputMethods: List<String>, defaultInputMethod: String = "") =
            ImGroup(name, inputMethods, "").withDefault(defaultInputMethod)
    }
}

/**
 * The input contexts and which input method each is on: fcitx's `InputContextManager` with
 * `ShareInputState=All`, Android's default (`fcitx/globalconfig.cpp:189-191`), plus the parts of
 * `Instance` that pick and switch the input method.
 *
 * All means the state follows the user: a change to one context's [InputState] is copied to every
 * other (`inputcontextmanager.cpp:378-400`), and a new context starts from an existing one's
 * (`:159-185`). So every app is on the same input method. A context with the Password (or Disable)
 * capability is on `keyboard-us` whatever its state, and back on its own once the field is left
 * ([inputMethod]).
 *
 * [onSwitch] is called for each focused context whose input method changed, with the old and new
 * ones: the host deactivates the old engine (it commits what was typed) and activates the new one,
 * as fcitx's `InputContextSwitchInputMethod` handler does (`fcitx/instance.cpp:1178-1188`).
 */
class InputContexts(
    group: ImGroup,
    private val onSwitch: (ic: InputContext, reason: SwitchReason, oldIm: String, newIm: String) -> Unit,
    /** `AllowInputMethodForPassword`, false in fcitx and here. */
    private val allowImForPassword: Boolean = false,
    /** `InputContextCache`'s size (`androidfrontend/inputcontextcache.h`). */
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    var group: ImGroup = group
        private set

    // least recently used first, as the frontend's LRU evicts
    private val contexts = LinkedHashMap<Int, InputContext>(capacity, LOAD_FACTOR, true)

    /** The context keys go to (`AndroidFrontend::activeIC_`), null between [deactivate] and [activate]. */
    var active: InputContext? = null
        private set

    val size get() = contexts.size

    /**
     * `AndroidFrontend::activateInputContext` (`androidfrontend.cpp:469-483`): the uid's context, made
     * if it is new. A new one copies its state from an existing context (All), and pushes the least
     * recently used one out past [capacity].
     */
    fun activate(uid: Int): InputContext {
        val ic = contexts[uid] ?: InputContext(uid, contexts.values.firstOrNull()?.state ?: InputState()).also {
            if (contexts.size >= capacity) contexts.remove(contexts.keys.first())
            contexts[uid] = it
        }
        active = ic
        return ic
    }

    /** Whether [uid] has a context (not pushed out); the cache's order left as it is. */
    operator fun contains(uid: Int) = contexts.containsKey(uid)

    /**
     * `AndroidFrontend::deactivateInputContext` (`androidfrontend.cpp:485-490`): an unknown [uid]
     * changes nothing; a known one leaves nothing focused and none active, whichever context that
     * was (an unbind arriving after the next bind).
     */
    fun deactivate(uid: Int) {
        if (uid !in this) return
        focused?.hasFocus = false
        active = null
    }

    /** Focus in or out; one focus group, so focusing one context unfocuses the rest. */
    fun focus(ic: InputContext, focus: Boolean) {
        if (focus) {
            contexts.values.forEach { it.hasFocus = it === ic }
            lastFocused = ic
        } else {
            ic.hasFocus = false
        }
    }

    /**
     * `Instance::inputMethod` (`fcitx/instance.cpp:1735-1768`): `keyboard-us` (the group's layout
     * keyboard) in a password or disabled field; otherwise the local input method or the group's
     * default when active, the group's first when not.
     */
    fun inputMethod(ic: InputContext): String {
        if (ic.disablesInputMethod(allowImForPassword)) {
            // `keyboard-<group layout>`, always `us` here (`native-lib.cpp:199`), and an entry
            // whether or not the group lists it
            return ImGroup.KEYBOARD_US
        }
        if (group.inputMethods.isEmpty()) return ""
        val state = ic.state
        if (state.active) {
            return if (state.localIm.isNotEmpty() && state.localIm in group.inputMethods) state.localIm else group.defaultInputMethod
        }
        return group.inputMethods[0]
    }

    /**
     * New capabilities for a context. Focused, and the change turns the password/disabled fallback on
     * or off (`shouldSwitchIM`, `fcitx/instance.cpp:144-149`): the old input method is switched out
     * and the new one in (`:725-764`), so leaving a password field brings the user's input method back.
     */
    fun setCapabilities(ic: InputContext, flags: Long) {
        val switches = ic.hasFocus &&
            InputContext.isInputMethodDisabled(ic.capabilities, allowImForPassword) !=
            InputContext.isInputMethodDisabled(flags, allowImForPassword)
        if (!switches) {
            ic.capabilities = flags
            return
        }
        val before = ic.capabilities
        val old = inputMethod(ic)
        ic.capabilities = flags
        val new = inputMethod(ic)
        // fcitx's CapabilityAboutToChange then CapabilityChanged (`inputcontext.cpp:222-236`): the
        // old input method leaves under the old flags (a password field's text is never learnt, a
        // plain field's is), the new one comes in under the new; [applyCapabilities] in between
        ic.capabilities = before
        pendingCapabilities = flags
        try {
            onSwitch(ic, SwitchReason.CapabilityChanged, old, new)
        } finally {
            applyCapabilities(ic)
        }
    }

    private var pendingCapabilities: Long? = null

    /** A capability switch's new flags put in place, once the old input method has left; else nothing. */
    fun applyCapabilities(ic: InputContext) {
        pendingCapabilities?.let { ic.capabilities = it }
        pendingCapabilities = null
    }

    /** `Instance::canTrigger` (`fcitx/instance.cpp:2192-2195`): switching needs two input methods. */
    private val canTrigger get() = group.inputMethods.size > 1

    /** `Instance::toggle(ic, reason)` (`fcitx/instance.cpp:2231-2242`): first ↔ default. */
    fun toggle(ic: InputContext, reason: SwitchReason = SwitchReason.Trigger): Boolean {
        if (!canTrigger) return false
        changing(ic, reason) { setState(ic, ic.state.copy(active = !ic.state.active)) }
        return true
    }

    /**
     * `Instance::enumerate(ic, forward)` (`fcitx/instance.cpp:2311-2350`, `EnumerateSkipFirst` false):
     * the next input method in the group, wrapping; any but the first becomes the group's default.
     */
    fun enumerate(ic: InputContext, forward: Boolean): Boolean {
        if (!canTrigger) return false
        val list = group.inputMethods
        val idx = list.indexOf(inputMethod(ic)).takeIf { it >= 0 } ?: return false
        val next = (idx + if (forward) 1 else list.size - 1) % list.size
        changing(ic, SwitchReason.Enumerate) {
            if (next != 0) {
                group = group.withDefault(list[next])
                setState(ic, InputState(active = true, localIm = ""))
            } else {
                setState(ic, ic.state.copy(active = false))
            }
        }
        return true
    }

    /**
     * `Instance::setCurrentInputMethod` (`fcitx/instance.cpp:2102-2160`): [name] for [ic] (or, with no
     * context, the group's default only). [local] keeps it to this context's state; otherwise it
     * becomes the group's default. The first input method means inactive.
     */
    fun setCurrentInputMethod(ic: InputContext?, name: String, local: Boolean) {
        if (!canTrigger) return
        val idx = group.inputMethods.indexOf(name)
        if (idx < 0) return
        if (ic == null) {
            if (!local && idx != 0) changing(null, SwitchReason.Other) { group = group.withDefault(name) }
            return
        }
        if (inputMethod(ic) == name) return
        changing(ic, SwitchReason.Other) {
            if (idx != 0) {
                if (!local) group = group.withDefault(name)
                setState(ic, InputState(active = true, localIm = if (local) name else ""))
            } else {
                setState(ic, ic.state.copy(active = false))
            }
        }
    }

    /**
     * New enabled input methods (the input method list page, first run): `setGroup` semantics
     * ([ImGroup.withInputMethods]). The current group changed, every focused context is switched,
     * its input method changed or not: fcitx deactivates each on `CurrentGroupAboutToChange` and
     * activates it on `CurrentGroupChanged` (`fcitx/instance.cpp:687-711`), so what was typed is
     * committed and the session reset. No `InputContextSwitchInputMethod` event is posted for it.
     */
    fun setInputMethods(list: List<String>, available: Set<String>) {
        val before = contexts.values.filter { it.hasFocus }.map { it to inputMethod(it) }
        group = group.withInputMethods(list, available)
        before.forEach { (context, old) -> onSwitch(context, SwitchReason.GroupChange, old, inputMethod(context)) }
    }

    /** The focused context, if any (one focus group). */
    val focused: InputContext? get() = contexts.values.firstOrNull { it.hasFocus }

    /**
     * `InputContextManager::mostRecentInputContext`: the focused one, else the last focused that is
     * still there; what `toggle()` and `enumerate()` without a context act on (`instance.cpp:2176-2190`).
     */
    val mostRecent: InputContext?
        // not `contexts[uid]`: a get in the access-ordered map would make it the most recently used
        get() = focused ?: lastFocused?.takeIf { last -> contexts.values.any { it === last } }

    private var lastFocused: InputContext? = null

    /** All: one context's new state is every context's (`InputContextManager::propagateProperty`). */
    private fun setState(ic: InputContext, state: InputState) {
        ic.state = state
        contexts.values.forEach { it.state = state }
    }

    /**
     * fcitx's `CheckInputMethodChanged` (`fcitx/instance.cpp:632-656`), held around a change for every
     * focused context (`foreachFocused`, `:2122-2130`): those whose input method differs afterwards
     * are switched; [ic] with [reason], the others with Other (all with [reason] when there is no [ic]).
     */
    private inline fun changing(ic: InputContext?, reason: SwitchReason, change: () -> Unit) {
        val before = contexts.values.filter { it.hasFocus }.map { it to inputMethod(it) }
        change()
        before.forEach { (context, old) ->
            val new = inputMethod(context)
            if (new != old) onSwitch(context, if (ic == null || context === ic) reason else SwitchReason.Other, old, new)
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 80
        private const val LOAD_FACTOR = 0.75f
    }
}
