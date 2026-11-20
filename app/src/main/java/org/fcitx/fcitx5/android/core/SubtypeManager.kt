/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.core

import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodSubtype
import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder
import androidx.annotation.RequiresApi
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.inputMethodManager

object SubtypeManager {

    private const val MODE_KEYBOARD = "keyboard"

    private const val IM_KEYBOARD = InputMethodNames.KEYBOARD

    // replaced whole, never changed in place: main reads it while another thread syncs
    @Volatile
    private var knownSubtypes: Map<String, InputMethodSubtype> = emptyMap()

    fun subtypeOf(inputMethod: String): InputMethodSubtype? {
        return knownSubtypes[inputMethod]
    }

    /** Null for a subtype without one (the system's, before [syncWith] on a fresh install): taking that for the keyboard opened every first field in English. */
    fun inputMethodOf(subtype: InputMethodSubtype): String? {
        return subtype.extraValue.ifEmpty { null }
    }

    /** The system takes the first as current until the user picks: the keyboard goes last, so a fresh install does not open in English. */
    fun registrationOrder(enabled: Array<InputMethodEntry>): List<InputMethodEntry> =
        enabled.sortedBy { it.uniqueName == IM_KEYBOARD }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Synchronized
    fun syncWith(enabled: Array<InputMethodEntry>, names: Context = appContext) {
        val inputMethods = registrationOrder(enabled)
        val size = inputMethods.size
        val known = HashMap<String, InputMethodSubtype>(size)
        val subtypes = arrayOfNulls<InputMethodSubtype>(size)
        val hashCodes = IntArray(size)
        inputMethods.forEachIndexed { i, im ->
            val subtype = InputMethodSubtypeBuilder()
                .setSubtypeId(im.uniqueName.hashCode())
                .setSubtypeExtraValue(im.uniqueName)
                .setSubtypeNameOverride(InputMethodNames.of(names, im))
                .setSubtypeMode(MODE_KEYBOARD)
                .setIsAsciiCapable(im.uniqueName == IM_KEYBOARD)
                .build()
            val hashCode = subtype.hashCode()
            subtypes[i] = subtype
            hashCodes[i] = hashCode
            known[im.uniqueName] = subtype
        }
        knownSubtypes = known
        val imm = appContext.inputMethodManager
        val imiId = InputMethodUtil.componentName
        // deprecated, but dynamic subtypes must be "registered" before they can be "enabled"
        @Suppress("DEPRECATION")
        imm.setAdditionalInputMethodSubtypes(imiId, subtypes)
        imm.setExplicitlyEnabledInputMethodSubtypes(imiId, hashCodes)
    }
}
