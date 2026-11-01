/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.address

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressLearningTest {

    private val l = AddressLearning()

    @Test
    fun aFieldIsLearnedFromAsAnotherStarts() {
        assertNull(l.start("mail", AddressField.EMAIL, learns = true, text = ""))
        l.seen("a@qq.com")
        assertEquals(AddressLearning.Left(AddressField.EMAIL, "a@qq.com"), l.start("name", null, learns = true, text = null))
        // and once
        assertNull(l.start("mail", AddressField.EMAIL, learns = true, text = "a@qq.com"))
    }

    @Test
    fun theSameFieldStartedAgainIsNotLeft() {
        l.start("mail", AddressField.EMAIL, learns = true, text = "")
        l.seen("a@q")
        // the keyboard shown again, the screen turned, the app's restart: the same field, half typed
        assertNull(l.start("mail", AddressField.EMAIL, learns = true, text = "a@q"))
        l.seen("a@qq.com")
        assertEquals("a@qq.com", l.start("url", AddressField.URL, learns = true, text = "")?.text)
    }

    @Test
    fun aFieldTheUserDidNotChangeIsNotLearned() {
        // filled in by the app: as it started, whatever was selected or where the cursor went
        l.start("mail", AddressField.EMAIL, learns = true, text = "me@qq.com")
        l.seen("me@qq.com")
        assertNull(l.start("other", null, learns = true, text = null))
        // more than was read, or emptied
        l.start("mail", AddressField.EMAIL, learns = true, text = "")
        l.seen(null)
        assertNull(l.left())
        l.seen("  ")
        assertNull(l.left())
    }

    @Test
    fun nothingIsKeptOfAFieldNotLearnedFrom() {
        assertNull(l.start("mail", AddressField.EMAIL, learns = false, text = "a@qq.com"))
        l.seen("b@qq.com")
        assertNull(l.left())
        assertNull(l.start("other", null, learns = true, text = null))
    }

    @Test
    fun aFieldOfTheSameNameWithOtherTextIsAnotherField() {
        // a Compose screen's 邮箱 and 备用邮箱, one name: the second prefilled by the app
        l.start("compose", AddressField.EMAIL, learns = true, text = "")
        l.seen("me@qq.com")
        assertEquals("me@qq.com", l.start("compose", AddressField.EMAIL, learns = true, text = "old@x.com")?.text)
        // left untouched: the app's address not learned
        assertNull(l.start("other", null, learns = true, text = null))
    }

    @Test
    fun anEmptyFieldOfTheSameNameKeepsTheAddressTypedBefore() {
        // a web mail's 收件人, then its 抄送 left empty
        l.start("web", AddressField.EMAIL, learns = true, text = "")
        l.seen("a@x.com")
        assertEquals("a@x.com", l.start("web", AddressField.EMAIL, learns = true, text = "")?.text)
        assertNull(l.start("web#subject", null, learns = true, text = null))
    }

    @Test
    fun aFieldNotReadStartsAsItIsFirstRead() {
        l.start("mail", AddressField.EMAIL, learns = true, text = null, read = false)
        // the app's text, read at last: how it started, not a change
        l.seen("me@qq.com")
        assertNull(l.left())
        // a read that failed changes nothing
        l.seen(null, read = false)
        assertNull(l.left())
        l.seen("me@qq.com, b@qq.com")
        // the same field restarted, nothing read: still it
        assertNull(l.start("mail", AddressField.EMAIL, learns = true, text = null, read = false))
        assertEquals("me@qq.com, b@qq.com", l.end()?.text)
        assertNull(l.end())
    }

    @Test
    fun whatAFieldHeldAsTheAddressesWereForgottenIsNotLearnedAgain() {
        l.start("mail", AddressField.EMAIL, learns = true, text = "")
        l.seen("me@qq.com")
        // 全部清空, then back in the same field, typing on
        assertNull(l.start("mail", AddressField.EMAIL, learns = true, text = "me@qq.com", cleared = 1))
        l.seen("me@qq.com, b@qq.com")
        assertEquals("me@qq.com, b@qq.com", l.start("other", AddressField.URL, learns = true, text = "", cleared = 1)?.text)
        // forgotten again: another field started, the one before not learned
        l.seen("qq.com")
        assertNull(l.start("mail", AddressField.EMAIL, learns = true, text = "", cleared = 2))
        l.seen("c@qq.com")
        assertNull(l.end(cleared = 3))
    }

    @Test
    fun whatTheFieldStartedWithGoesAlongNotToBeLearned() {
        l.start("m2", AddressField.EMAIL, learns = true, text = "a@x.com")
        l.seen("a@x.com, c@qq.com")
        assertEquals(AddressLearning.Left(AddressField.EMAIL, "a@x.com, c@qq.com", started = "a@x.com"), l.end())
    }
}
