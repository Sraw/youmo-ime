/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/** fcitx's `CapabilityFlag` bits the host reads. */
object Capability {
    /** Client preedit. */
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

/** fcitx's `InputContextProperty`: made on first ask. State following the user across apps belongs in [InputContexts]. */
class IcProperty<T : Any>(val create: () -> T)

/** One per app uid, as the frontend makes them. */
class InputContext internal constructor(val uid: Int, internal var state: InputState) {
    var hasFocus: Boolean = false
        internal set
    var capabilities: Long = 0
        internal set

    /** `InputContext::inputPanel`. */
    val panel = Panel()

    /** `InputContext::setEnablePreedit`: off masks the Preedit capability, typing shown above the candidates. */
    var preeditEnabled: Boolean = true

    /** `calculateFlags`. */
    val effectiveCapabilities: Long
        get() = if (preeditEnabled) capabilities else capabilities and (Capability.PREEDIT or Capability.FORMATTED_PREEDIT).inv()

    fun has(flag: Long) = effectiveCapabilities and flag != 0L

    // `InputContextPrivate::lastPreeditUpdateIsEmpty_`: two empty client preedits are sent once
    internal var lastPreeditUpdateIsEmpty = true

    private val properties = HashMap<IcProperty<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> propertyFor(property: IcProperty<T>): T =
        properties.getOrPut(property) { property.create() } as T

    internal fun disablesInputMethod() = isInputMethodDisabled(capabilities)

    companion object {
        /** `isInputMethodDisabled`, with `AllowInputMethodForPassword` false. */
        internal fun isInputMethodDisabled(flags: Long) =
            flags and Capability.DISABLE != 0L || flags and Capability.PASSWORD != 0L
    }
}

/** The part of fcitx's `InputState` that picks the input method; [localIm] chosen for this context only. */
data class InputState(val active: Boolean = false, val localIm: String = "")
