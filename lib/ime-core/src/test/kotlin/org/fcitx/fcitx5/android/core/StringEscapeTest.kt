/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.FcitxUtils
import org.junit.Assert
import org.junit.Test

class StringEscapeTest {

    // https://github.com/fcitx/fcitx5/blob/5.1.21/test/teststringutils.cpp#L142
    private val data = listOf(
        "\"" to """"\""""",
        "\"\"\n" to """"\"\"\n"""",
        "abc" to """abc""",
        "ab\"c" to """"ab\"c"""",
        "a c" to """"a c"""",
        "工 " to """"工 """",
        "\r\u000b\u000c\n\t\\\" " to """"\r\v\f\n\t\\\" """",
    )

    @Test
    fun testEscapeForValue() {
        data.forEach {
            Assert.assertEquals(FcitxUtils.escapeForValue(it.first), it.second)
        }
    }

    @Test
    fun testUnescapeForValue() {
        data.forEach {
            Assert.assertEquals(it.first, FcitxUtils.unescapeForValue(it.second))
        }
    }

    @Test
    fun escapeThenUnescapeIsIdentity() {
        val samples = listOf(
            "",
            "plain",
            "with space",
            "tab\there",
            "line\nbreak",
            "quote\"inside",
            "back\\slash",
            "你好世界",
            "emoji 👋 and more",
            "  leading and trailing  ",
            "\u000b\u000c",
            "\r\n",
        )
        samples.forEach { original ->
            Assert.assertEquals(
                "round trip failed",
                original,
                FcitxUtils.unescapeForValue(FcitxUtils.escapeForValue(original)),
            )
        }
    }

    @Test
    fun plainWordsAreLeftUnquoted() {
        Assert.assertEquals("abc", FcitxUtils.escapeForValue("abc"))
        Assert.assertEquals("123", FcitxUtils.escapeForValue("123"))
        Assert.assertEquals("你好", FcitxUtils.escapeForValue("你好"))
    }

    @Test
    fun anythingContainingWhitespaceGetsQuoted() {
        Assert.assertTrue(FcitxUtils.escapeForValue("a b").startsWith("\""))
        Assert.assertTrue(FcitxUtils.escapeForValue("a\tb").startsWith("\""))
        Assert.assertTrue(FcitxUtils.escapeForValue("a\nb").startsWith("\""))
    }

    /**
     * An empty value escapes to an empty string, not to a pair of quotes. Anything that stores
     * escaped values in a line-based format therefore loses empty values on re-read -- see
     * [org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseEntryTest].
     */
    @Test
    fun anEmptyStringEscapesToNothing() {
        Assert.assertEquals("", FcitxUtils.escapeForValue(""))
    }

    @Test
    fun unescapingPlainTextIsIdentity() {
        Assert.assertEquals("abc", FcitxUtils.unescapeForValue("abc"))
        Assert.assertEquals("你好", FcitxUtils.unescapeForValue("你好"))
    }
}
