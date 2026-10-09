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

    private const val IM_KEYBOARD = "keyboard-us"

    // replaced whole, never changed in place: the main thread reads it while another thread syncs
    @Volatile
    private var knownSubtypes: Map<String, InputMethodSubtype> = emptyMap()

    fun subtypeOf(inputMethod: String): InputMethodSubtype? {
        return knownSubtypes[inputMethod]
    }

    /**
     * The fcitx input method of one of our subtypes; null for one without (the system's, before
     * [syncWith] has run on a fresh install), which says nothing of what the user picked: taking it
     * for the keyboard opened every first field in English.
     */
    fun inputMethodOf(subtype: InputMethodSubtype): String? {
        return subtype.extraValue.ifEmpty { null }
    }

    /**
     * The order subtypes are registered in: the system takes the first for the current one until
     * the user picks another, and the first field switches to it, so the keyboard first opened
     * every fresh install in English. The keyboard goes last, the rest keep their order.
     */
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
        // although this method has been marked as deprecated,
        // dynamic subtypes have to be "registered" before they can be "enabled"
        @Suppress("DEPRECATION")
        imm.setAdditionalInputMethodSubtypes(imiId, subtypes)
        imm.setExplicitlyEnabledInputMethodSubtypes(imiId, hashCodes)
    }
}
