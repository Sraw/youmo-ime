/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.core

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

    private val knownSubtypes: HashMap<String, InputMethodSubtype> = hashMapOf()

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

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun syncWith(enabled: Array<InputMethodEntry>) {
        // the system takes the first subtype registered for the current one until the user picks
        // another, and the first field switches to it: the keyboard first opened every fresh
        // install in English
        val inputMethods = enabled.sortedBy { it.uniqueName == IM_KEYBOARD }
        knownSubtypes.clear()
        val size = inputMethods.size
        val subtypes = arrayOfNulls<InputMethodSubtype>(size)
        val hashCodes = IntArray(size)
        inputMethods.forEachIndexed { i, im ->
            val subtype = InputMethodSubtypeBuilder()
                .setSubtypeId(im.uniqueName.hashCode())
                .setSubtypeExtraValue(im.uniqueName)
                .setSubtypeNameOverride(im.displayName)
                .setSubtypeMode(MODE_KEYBOARD)
                .setIsAsciiCapable(im.uniqueName == IM_KEYBOARD)
                .build()
            val hashCode = subtype.hashCode()
            subtypes[i] = subtype
            hashCodes[i] = hashCode
            knownSubtypes[im.uniqueName] = subtype
        }
        val imm = appContext.inputMethodManager
        val imiId = InputMethodUtil.componentName
        // although this method has been marked as deprecated,
        // dynamic subtypes have to be "registered" before they can be "enabled"
        @Suppress("DEPRECATION")
        imm.setAdditionalInputMethodSubtypes(imiId, subtypes)
        imm.setExplicitlyEnabledInputMethodSubtypes(imiId, hashCodes)
    }
}
