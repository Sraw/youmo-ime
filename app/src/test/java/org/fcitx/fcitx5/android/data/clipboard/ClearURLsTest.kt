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
