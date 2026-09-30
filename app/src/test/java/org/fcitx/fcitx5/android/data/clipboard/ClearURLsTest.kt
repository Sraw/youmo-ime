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
