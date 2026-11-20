/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each bit is read out of a packed field, so each case sets a neighbour too: a mask too wide or narrow shows. */
class EditorInfoTraitsTest {

    private fun info(block: EditorInfo.() -> Unit) = EditorInfo().apply(block).toEditorTraits()

    @Test
    fun aFieldOfThisAppIsSensitiveWhenThePackageIsGiven() {
        val own = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; packageName = "io.github.sraw.youmo" }
        assertTrue(own.toEditorTraits("io.github.sraw.youmo").isSensitive)
        assertFalse(own.toEditorTraits("other.app").isSensitive)
        assertFalse(own.toEditorTraits().isSensitive)
    }

    @Test
    fun aPlainTextFieldHasNoSpecialTraits() {
        assertEquals(EditorTraits(), info { inputType = InputType.TYPE_CLASS_TEXT })
    }

    @Test
    fun aMessageFieldSendsAndTakesLinesAsItSays() {
        val chat = info { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; imeOptions = EditorInfo.IME_ACTION_SEND }
        assertTrue(chat.sends)
        assertTrue(chat.isMultiLine)
        assertTrue(info { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE }.isMultiLine)
        assertTrue(info { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE }.isShortMessage)
        assertTrue(info { inputType = InputType.TYPE_CLASS_TEXT; actionLabel = "发送"; actionId = EditorInfo.IME_ACTION_SEND }.sends)
        assertFalse(info { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_MULTI_LINE }.isMultiLine)
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
    fun eitherEmailVariationIsAnEmailField() {
        assertTrue(info { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }.isEmail)
        assertTrue(info { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS }.isEmail)
        assertFalse(info { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }.isEmail)
    }

    @Test
    fun aPasswordHiddenOrShownIsAPasswordNotAFieldThatOnlyAsksNotToBeLearnedFrom() {
        // the code stays out of a password's field, a shown one too
        assertTrue(info { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }.isPassword)
        assertTrue(info { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }.isPassword)
        assertTrue(info { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }.isPassword)
        assertFalse(info {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }.isPassword)
        assertFalse(info { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }.isPassword)
    }

    @Test
    fun aPasswordOrAFieldNotToLearnFromIsSensitive() {
        assertTrue(info { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }.isSensitive)
        assertTrue(info {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }.isSensitive)
        assertFalse(info { inputType = InputType.TYPE_CLASS_TEXT }.isSensitive)
    }

    @Test
    fun onlyOurOwnPrivateOptionOptsIntoDeleteSurrounding() {
        assertTrue(info { privateImeOptions = DeleteSurroundingFlag }.acceptsDeleteSurrounding)
        assertFalse(info { privateImeOptions = "nm,com.example.other" }.acceptsDeleteSurrounding)
        assertFalse(info { privateImeOptions = null }.acceptsDeleteSurrounding)
    }

    @Test
    fun ourOptionAmongOthersStillOptsIn() {
        assertTrue(info { privateImeOptions = "nm,$DeleteSurroundingFlag" }.acceptsDeleteSurrounding)
        assertTrue(info { privateImeOptions = "$DeleteSurroundingFlag, nm" }.acceptsDeleteSurrounding)
        assertFalse(
            "a longer option that merely starts with ours",
            info { privateImeOptions = "$DeleteSurroundingFlag.x,nm" }.acceptsDeleteSurrounding,
        )
    }

    @Test
    fun numberPhoneAndDateFieldsAreNumbers() {
        assertTrue(info { inputType = InputType.TYPE_CLASS_NUMBER }.isNumber)
        assertTrue(info { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }.isNumber)
        assertTrue(info { inputType = InputType.TYPE_CLASS_PHONE }.isNumber)
        assertTrue(info { inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE }.isNumber)
        assertFalse(info { inputType = InputType.TYPE_CLASS_TEXT }.isNumber)
    }

    @Test
    fun aSearchActionMakesASearchField() {
        assertTrue(info { imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI }.isSearch)
        assertFalse(info { imeOptions = EditorInfo.IME_ACTION_GO }.isSearch)
        assertFalse(info { inputType = InputType.TYPE_CLASS_TEXT }.isSearch)
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

    @Test
    fun aNumbersPhonesOrDatesFieldSaysWhichNumbers() {
        val n = InputType.TYPE_CLASS_NUMBER
        val d = InputType.TYPE_CLASS_DATETIME
        fun kind(type: Int) = info { inputType = type }.number
        assertEquals(NumberKind.INTEGER, kind(n))
        assertEquals(NumberKind.SIGNED, kind(n or InputType.TYPE_NUMBER_FLAG_SIGNED))
        assertEquals(NumberKind.DECIMAL, kind(n or InputType.TYPE_NUMBER_FLAG_DECIMAL))
        assertEquals(NumberKind.SIGNED_DECIMAL, kind(n or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL))
        assertEquals(NumberKind.PIN, kind(n or InputType.TYPE_NUMBER_VARIATION_PASSWORD or InputType.TYPE_NUMBER_FLAG_DECIMAL))
        assertEquals(NumberKind.PHONE, kind(InputType.TYPE_CLASS_PHONE))
        assertEquals(NumberKind.DATE, kind(d or InputType.TYPE_DATETIME_VARIATION_DATE))
        assertEquals(NumberKind.TIME, kind(d or InputType.TYPE_DATETIME_VARIATION_TIME))
        assertEquals(NumberKind.DATETIME, kind(d))
        assertNull(kind(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
    }
}
