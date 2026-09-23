/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding `EditorInfo`'s bit fields. Each bit is read out of a field that packs several
 * others, so each case sets a neighbour too, to catch a mask that is too wide or too narrow.
 *
 * Plain JUnit, no Robolectric: `EditorInfo` is only a bag of public fields, and constructing
 * one does not reach any stubbed framework method.
 */
class EditorInfoTraitsTest {

    private fun info(block: EditorInfo.() -> Unit) = EditorInfo().apply(block).toEditorTraits()

    @Test
    fun aPlainTextFieldHasNoSpecialTraits() {
        assertEquals(EditorTraits(), info { inputType = InputType.TYPE_CLASS_TEXT })
    }

    @Test
    fun typeNullIsRawKeyInputWhateverTheFlags() {
        assertTrue(info { inputType = InputType.TYPE_NULL or InputType.TYPE_TEXT_FLAG_MULTI_LINE }.isRawKeyInput)
        assertFalse(info { inputType = InputType.TYPE_CLASS_NUMBER }.isRawKeyInput)
    }

    @Test
    fun onlyATextUriFieldIsAUri() {
        assertTrue(info {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }.isUri)
        assertFalse(
            "the variation bits mean something else in another class",
            info { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_URI }.isUri,
        )
        assertFalse(
            "URI's bits are a subset of this variation's; the whole field must match",
            info {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            }.isUri,
        )
    }

    @Test
    fun onlyOurOwnPrivateOptionOptsIntoDeleteSurrounding() {
        assertTrue(info { privateImeOptions = FcitxInputMethodService.DeleteSurroundingFlag }.acceptsDeleteSurrounding)
        assertFalse(info { privateImeOptions = "nm,com.example.other" }.acceptsDeleteSurrounding)
        assertFalse(info { privateImeOptions = null }.acceptsDeleteSurrounding)
    }

    @Test
    fun theImeActionIsReadFromItsMaskAlone() {
        val t = info { imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI }
        assertEquals(EditorInfo.IME_ACTION_SEARCH, t.imeAction)
        assertFalse(t.noEnterAction)
    }

    @Test
    fun unspecifiedAndNoneMeanNoAction() {
        assertNull(info { imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED }.imeAction)
        assertNull(info { imeOptions = EditorInfo.IME_ACTION_NONE }.imeAction)
    }

    @Test
    fun noEnterActionIsReadAlongsideAnAction() {
        val t = info { imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION }
        assertTrue(t.noEnterAction)
        assertEquals(EditorInfo.IME_ACTION_SEND, t.imeAction)
    }

    /** A custom action needs both a label to show and an id to perform. */
    @Test
    fun aCustomActionNeedsALabelAndAnId() {
        assertEquals(0x42, info { actionLabel = "Post"; actionId = 0x42 }.customActionId)
        assertNull("no label", info { actionId = 0x42 }.customActionId)
        assertNull("empty label", info { actionLabel = ""; actionId = 0x42 }.customActionId)
        assertNull(
            "unspecified id",
            info { actionLabel = "Post"; actionId = EditorInfo.IME_ACTION_UNSPECIFIED }.customActionId,
        )
    }
}
