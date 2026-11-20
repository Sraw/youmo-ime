/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/** fcitx's `InputMethodSwitchedReason`; the app's `SwitchInputMethodEvent.Reason` reads it by ordinal, so keep the order. */
enum class SwitchReason { Trigger, Deactivate, AltTrigger, Activate, Enumerate, GroupChange, CapabilityChanged, Other }

/** fcitx's `InputMethodGroup`: a context starts on the first ([InputState.active] false) and toggles to the default. */
class ImGroup private constructor(val name: String, val inputMethods: List<String>, val defaultInputMethod: String) {

    /** `InputMethodGroup::setDefaultInputMethod`: never the first while there is a second (toggling would do nothing). */
    fun withDefault(im: String): ImGroup {
        val default = when {
            im in inputMethods -> if (inputMethods.size > 1 && inputMethods[0] == im) inputMethods[1] else im
            inputMethods.size > 1 -> inputMethods[1]
            else -> inputMethods.firstOrNull().orEmpty()
        }
        return ImGroup(name, inputMethods, default)
    }

    /** `InputMethodManager::setGroup` as `setEnabledInputMethods` builds it: unavailable ones dropped, the default kept if listed. */
    fun withInputMethods(list: List<String>, available: Set<String>): ImGroup =
        ImGroup(name, list.filter { it in available }, "").withDefault(defaultInputMethod)

    companion object {
        const val KEYBOARD_US = "keyboard-us"

        fun of(name: String, inputMethods: List<String>, defaultInputMethod: String = "") =
            ImGroup(name, inputMethods, "").withDefault(defaultInputMethod)
    }
}

/**
 * fcitx's `InputContextManager` with `ShareInputState=All` (Android's default), plus `Instance`'s switching:
 * one context's [InputState] is copied to all, so every app is on the same input method. A password or
 * disabled field is on `keyboard-us` whatever its state. [onSwitch] gets each focused context that changed.
 */
class InputContexts(
    group: ImGroup,
    private val onSwitch: (ic: InputContext, reason: SwitchReason, oldIm: String, newIm: String) -> Unit,
    /** `InputContextCache`'s size. */
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    var group: ImGroup = group
        private set

    // least recently used first, as the frontend's LRU evicts
    private val contexts = LinkedHashMap<Int, InputContext>(capacity, LOAD_FACTOR, true)

    /** `AndroidFrontend::activeIC_`. */
    var active: InputContext? = null
        private set

    val size get() = contexts.size

    /** `AndroidFrontend::activateInputContext`: a new one copies an existing state and evicts past [capacity]. */
    fun activate(uid: Int): InputContext {
        val ic = contexts[uid] ?: InputContext(uid, contexts.values.firstOrNull()?.state ?: InputState()).also {
            if (contexts.size >= capacity) contexts.remove(contexts.keys.first())
            contexts[uid] = it
        }
        active = ic
        return ic
    }

    /** The cache's order left as it is. */
    operator fun contains(uid: Int) = contexts.containsKey(uid)

    /** Unlike fcitx, only [uid]'s: an unbind after the next bind would leave that app's context unfocused. */
    fun deactivate(uid: Int) {
        val ic = contexts[uid] ?: return
        ic.hasFocus = false
        if (active === ic) active = null
    }

    /** One focus group: focusing one context unfocuses the rest. */
    fun focus(ic: InputContext, focus: Boolean) {
        if (focus) {
            contexts.values.forEach { it.hasFocus = it === ic }
            lastFocused = ic
        } else {
            ic.hasFocus = false
        }
    }

    /** `Instance::inputMethod`: `keyboard-us` in a password or disabled field; else local, or the group's default/first. */
    fun inputMethod(ic: InputContext): String {
        if (ic.disablesInputMethod()) {
            // `keyboard-<group layout>`, always `us` here, whether or not the group lists it
            return ImGroup.KEYBOARD_US
        }
        if (group.inputMethods.isEmpty()) return ""
        val state = ic.state
        if (state.active) {
            return if (state.localIm.isNotEmpty() && state.localIm in group.inputMethods) state.localIm else group.defaultInputMethod
        }
        return group.inputMethods[0]
    }

    /** Focused and crossing the password/disabled line (`shouldSwitchIM`): switched out and in. */
    fun setCapabilities(ic: InputContext, flags: Long) {
        val switches = ic.hasFocus &&
            InputContext.isInputMethodDisabled(ic.capabilities) != InputContext.isInputMethodDisabled(flags)
        if (!switches) {
            ic.capabilities = flags
            return
        }
        val before = ic.capabilities
        val old = inputMethod(ic)
        ic.capabilities = flags
        val new = inputMethod(ic)
        // the old input method leaves under the old flags (a password's text never learnt), the new one comes in under the new
        ic.capabilities = before
        pendingCapabilities = flags
        try {
            onSwitch(ic, SwitchReason.CapabilityChanged, old, new)
        } finally {
            applyCapabilities(ic)
        }
    }

    private var pendingCapabilities: Long? = null

    fun applyCapabilities(ic: InputContext) {
        pendingCapabilities?.let { ic.capabilities = it }
        pendingCapabilities = null
    }

    /** `Instance::canTrigger`. */
    private val canTrigger get() = group.inputMethods.size > 1

    /** `Instance::toggle`: first ↔ default. */
    fun toggle(ic: InputContext, reason: SwitchReason = SwitchReason.Trigger): Boolean {
        if (!canTrigger) return false
        changing(ic, reason) { setState(ic, ic.state.copy(active = !ic.state.active)) }
        return true
    }

    /** `Instance::enumerate` (`EnumerateSkipFirst` false): next, wrapping; any but the first becomes the default. */
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

    /** `Instance::setCurrentInputMethod`: [local] keeps it to [ic]'s state, else it becomes the default; the first means inactive. */
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

    /** `setGroup` semantics; every focused context is deactivated and activated again, as on fcitx's group change. */
    fun setInputMethods(list: List<String>, available: Set<String>) {
        val before = contexts.values.filter { it.hasFocus }.map { it to inputMethod(it) }
        group = group.withInputMethods(list, available)
        before.forEach { (context, old) -> onSwitch(context, SwitchReason.GroupChange, old, inputMethod(context)) }
    }

    val focused: InputContext? get() = contexts.values.firstOrNull { it.hasFocus }

    /** `InputContextManager::mostRecentInputContext`: the focused one, else the last focused still there. */
    val mostRecent: InputContext?
        // not `contexts[uid]`: a get in the access-ordered map would make it the most recently used
        get() = focused ?: lastFocused?.takeIf { last -> contexts.values.any { it === last } }

    private var lastFocused: InputContext? = null

    /** All: one context's new state is every context's (`InputContextManager::propagateProperty`). */
    private fun setState(ic: InputContext, state: InputState) {
        ic.state = state
        contexts.values.forEach { it.state = state }
    }

    /** `CheckInputMethodChanged` around a change: each focused context that changed is switched, [ic] with [reason], others with Other. */
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
