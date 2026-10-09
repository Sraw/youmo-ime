/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// Robolectric for the real android.net.Uri and UrlQuerySanitizer the rules are applied with
@RunWith(RobolectricTestRunner::class)
class ClearURLsTest {

    @Test
    fun textThatIsNotAUrlIsLeftAlone() {
        val text = "see https://example.com/?utm_source=x"
        assertEquals(text, clearUrls.transform(text))
    }

    @Test
    fun trackingParametersAreRemovedAndOthersKept() {
        assertEquals(
            "https://example.com/page?id=42#top",
            clearUrls.transform("https://example.com/page?utm_source=news&id=42&fbclid=abc#top")
        )
    }

    @Test
    fun urlWithoutTrackingParametersIsUnchanged() {
        val url = "https://example.com/search?q=fcitx&page=2"
        assertEquals(url, clearUrls.transform(url))
    }

    @Test
    fun aUrlWithNothingToRemoveIsKeptAsCopied() {
        val text = "https://example.com/a b?q=%E4%B8%AD+1&x=a%2526&&y=1%2B1#k=%26 and some text after it"
        assertEquals(text, clearUrls.transform(text))
    }

    @Test
    fun textAfterTheLinkIsNoPartOfIt() {
        // not dropped with the tracker it follows, as when the whole clip was read as one link
        assertEquals(
            "https://example.com/page?id=42 看这个\n第二行",
            clearUrls.transform("https://example.com/page?id=42&utm_source=x 看这个\n第二行")
        )
    }

    @Test
    fun chinesePunctuationStraightAfterTheLinkEndsIt() {
        assertEquals(
            "https://example.com/page?id=42，看这个",
            clearUrls.transform("https://example.com/page?id=42&utm_source=x，看这个")
        )
        assertEquals(
            "https://example.com/page?id=42。」",
            clearUrls.transform("https://example.com/page?id=42&fbclid=abc。」")
        )
        assertEquals(
            "https://example.com/page?id=42”他说",
            clearUrls.transform("https://example.com/page?id=42&utm_campaign=y”他说")
        )
    }

    @Test
    fun chineseTextStraightAfterATrackerIsKept() {
        // removing the tracker would take the text with it, or run the text into id's value
        val text = "https://example.com/page?id=42&utm_source=x看这个"
        assertEquals(text, clearUrls.transform(text))
        val campaign = "https://example.com/page?id=1&utm_campaign=618大促"
        assertEquals(campaign, clearUrls.transform(campaign))
    }

    @Test
    fun aTrackerWhoseValueIsChineseFromItsStartIsRemovedWhole() {
        // no ASCII before the Chinese, so it is the tracker's raw value and not text after the link
        assertEquals(
            "https://example.com/page?id=42",
            clearUrls.transform("https://example.com/page?id=42&utm_term=输入法")
        )
    }

    @Test
    fun chineseTextRunOnFromARewrittenValueKeepsTheClipAsCopied() {
        // the rawRule's or the redirect's result would otherwise run into the text
        val path = "https://www.amazon.com/dp/B0X/ref=sr_1中文"
        assertEquals(path, clearUrls.transform(path))
        val redirect = "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fa&sa=D看这个"
        assertEquals(redirect, clearUrls.transform(redirect))
    }

    @Test
    fun chineseTextRunOnFromAKeptValueStaysInIt() {
        assertEquals(
            "https://example.com/page?id=42看这个",
            clearUrls.transform("https://example.com/page?utm_source=a&id=42看这个")
        )
    }

    @Test
    fun theLettersAndNumbersOfTheCjkBlocksDoNotEndTheLink() {
        // 〇 and 々 are in the CJK punctuation block, １ and Ａ in the full-width forms
        assertEquals(
            "https://example.com/二〇二六年/佐々木?q=二〇二四",
            clearUrls.transform("https://example.com/二〇二六年/佐々木?q=二〇二四&utm_source=x")
        )
        assertEquals(
            "https://example.com/search?q=１２３Ａｂ&page=2",
            clearUrls.transform("https://example.com/search?q=１２３Ａｂ&utm_source=x&page=2")
        )
    }

    @Test
    fun keptParametersKeepTheirEscapes() {
        assertEquals(
            "https://example.com/search?q=a%26b%2Bc%20d&next=https%3A%2F%2Fexample.org%2F%3Fa%3D1",
            clearUrls.transform(
                "https://example.com/search?q=a%26b%2Bc%20d&utm_source=x&next=https%3A%2F%2Fexample.org%2F%3Fa%3D1"
            )
        )
    }

    @Test
    fun nonAsciiValuesSurviveARemoval() {
        assertEquals(
            "https://example.com/搜索?q=中文+输入",
            clearUrls.transform("https://example.com/搜索?q=中文+输入&fbclid=abc")
        )
    }

    @Test
    fun fragmentParametersAreFilteredWithoutDecoding() {
        assertEquals(
            "https://example.com/page#section=a%26b",
            clearUrls.transform("https://example.com/page#section=a%26b&utm_campaign=x")
        )
    }

    @Test
    fun redirectTargetsAreDecodedOncePerLayerOfEncoding() {
        assertEquals(
            "https://example.com/s?q=a%26b",
            clearUrls.transform("https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fs%3Fq%3Da%2526b&sa=D")
        )
        assertEquals(
            "https://example.com/a",
            clearUrls.transform("https://www.google.com/url?q=https%253A%252F%252Fexample.com%252Fa&sa=D")
        )
    }

    @Test
    fun aRedirectTargetInAQueryValueIsDecodedOnceThoughItsSchemeIsPlain() {
        assertEquals(
            "https://example.com/p?a=1&b=2",
            clearUrls.transform("https://www.google.com/url?q=https://example.com/p?a%3D1%26b%3D2&sa=D")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            clearUrls.transform("https://www.google.com/url?q=https://www.youtube.com/watch?v%3Dabc&sa=D")
        )
        assertEquals(
            "https://example.com/p?a=1",
            clearUrls.transform("https://www.googleadservices.com/pagead/aclk?sa=L&adurl=https://example.com/p?a%3D1&ved=0")
        )
    }

    @Test
    fun aRedirectTargetThatWasNeverEncodedIsKeptAsItIs() {
        assertEquals(
            "https://example.com/s?q=a%26b%2Bc",
            clearUrls.transform("https://href.li/?https://example.com/s?q=a%26b%2Bc")
        )
        assertEquals(
            "https://example.com/a?b=%2B1%26c",
            clearUrls.transform("https://links.govdelivery.com/track?type=click&enid=ZWFz&https://example.com/a?b=%2B1%26c")
        )
    }

    @Test
    fun anEncodedRedirectTargetIsDecodedHoweverItsSchemeReads() {
        assertEquals(
            "https://example.com/s?q=a%2Bb",
            clearUrls.transform("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com%2Fs%3Fq%3Da%252Bb&h=AT0")
        )
        assertEquals(
            "https://example.com/a?b=1",
            clearUrls.transform("https://abc.r.us-east-1.awstrack.me/L0/https:%2F%2Fexample.com%2Fa%3Fb=1/1/0100-xyz")
        )
        assertEquals(
            "https://example.com/a",
            clearUrls.transform("https://abc.r.us-east-1.awstrack.me/L0/https:%252F%252Fexample.com%252Fa/1/0100-xyz")
        )
    }

    @Test
    fun redirectLinksAreUnwrapped() {
        assertEquals(
            "https://example.com/a?b=1",
            clearUrls.transform("https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1&sa=D")
        )
    }

    @Test
    fun providerExceptionsAreHonoured() {
        // docs.google.com is on the google provider's exception list, so its "ved" stays
        val url = "https://docs.google.com/document/d/x/edit?ved=abc"
        assertEquals(url, clearUrls.transform(url))
        assertEquals("https://www.google.com/search?q=a", clearUrls.transform("https://www.google.com/search?q=a&ved=abc"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rulesWithoutProvidersAreRejected() {
        ClearURLs("{}")
    }

    companion object {
        private lateinit var clearUrls: ClearURLs

        // :app unit tests run without merged resources, so read the bundled rules from the source tree
        @BeforeClass
        @JvmStatic
        fun loadRules() {
            clearUrls = ClearURLs(File("src/main/res/raw/clearurls_rules.json").readText())
        }
    }
}
