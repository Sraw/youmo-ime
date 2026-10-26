/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/** The capability bits the host reads (`fcitx-utils/capabilityflags.h:25,27,79`). */
object Capability {
    /** The editor shows the preedit itself (client preedit). */
    const val PREEDIT = 1L shl 1
    const val FORMATTED_PREEDIT = 1L shl 4
    const val CLIENT_UNFOCUS_COMMIT = 1L shl 5
    const val PASSWORD = 1L shl 3
    const val EMAIL = 1L shl 7
    const val URL = 1L shl 12
    const val NO_SPELL_CHECK = 1L shl 17
    const val SENSITIVE = 1L shl 36
    const val DISABLE = 1L shl 40
    const val COMMIT_STRING_WITH_CURSOR = 1L shl 41
    const val PASSWORD_OR_SENSITIVE = PASSWORD or SENSITIVE
}

/**
 * Per-context state a module keeps, fcitx's `InputContextProperty`: [create] makes one the first time
 * a context asks for it. Each module holds its key as a value (`val state = IcProperty { PuncState() }`)
 * and reads it with [InputContext.propertyFor]; state that must follow the user across apps belongs in
 * [InputContexts], not here.
 */
class IcProperty<T : Any>(val create: () -> T)

/**
 * One editor's input context: the frontend makes one per app uid (`androidfrontend.cpp:469-483`).
 * Holds what fcitx's `InputContext` holds and the host needs: focus, capabilities, the
 * input-method state ([InputContexts] owns it) and the modules' [IcProperty]s.
 */
class InputContext internal constructor(val uid: Int, internal var state: InputState) {
    var hasFocus: Boolean = false
        internal set
    var capabilities: Long = 0
        internal set

    /** What the panel shows (`InputContext::inputPanel`). */
    val panel = Panel()

    /**
     * `InputContext::setEnablePreedit` (`fcitx/inputcontext.cpp:243-257`): off, the Preedit
     * capability is masked off, so what is typed shows above the candidates rather than in the app.
     */
    var preeditEnabled: Boolean = true

    /** The capabilities as the engine reads them, [preeditEnabled] applied (`calculateFlags`, `inputcontext.cpp:69-75`). */
    val effectiveCapabilities: Long
        get() = if (preeditEnabled) capabilities else capabilities and (Capability.PREEDIT or Capability.FORMATTED_PREEDIT).inv()

    fun has(flag: Long) = effectiveCapabilities and flag != 0L

    // `InputContextPrivate::lastPreeditUpdateIsEmpty_` (`inputcontext_p.h:178`): two empty client preedits are sent once
    internal var lastPreeditUpdateIsEmpty = true

    private val properties = HashMap<IcProperty<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> propertyFor(property: IcProperty<T>): T =
        properties.getOrPut(property) { property.create() } as T

    internal fun disablesInputMethod(allowForPassword: Boolean) =
        isInputMethodDisabled(capabilities, allowForPassword)

    companion object {
        /** `isInputMethodDisabled` (`fcitx/instance.cpp:136-141`). */
        internal fun isInputMethodDisabled(flags: Long, allowForPassword: Boolean) =
            flags and Capability.DISABLE != 0L ||
                (flags and Capability.PASSWORD != 0L && !allowForPassword)
    }
}

/**
 * fcitx's `InputState` (`fcitx/instance_p.h:39-90`), the part that picks the input method: [active]
 * (on the group's default input method rather than its first) and [localIm] (an input method chosen
 * for this context only). The rest of fcitx's `InputState` is the hotkey and xkb machinery this host
 * does not have (§1.4 of the design).
 */
data class InputState(val active: Boolean = false, val localIm: String = "")
