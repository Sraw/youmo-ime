/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** So a debug install is told apart from a release one in the switcher. */
class InputMethodSubtypeIconTest {

    @Test
    fun theSubtypeHasTheAppsIcon() {
        val application = parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        val subtype = parse("src/main/res/xml/input_method.xml").getElementsByTagName("subtype").item(0) as Element
        assertEquals(application.getAttributeNS(ANDROID_NS, "icon"), subtype.getAttributeNS(ANDROID_NS, "icon"))
    }

    // :app unit tests have no merged resources, so the source files are read (from app/ under Gradle, the repo root in an IDE)
    private fun parse(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(listOf(path, "app/$path").map { File(it) }.first { it.exists() }).documentElement

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
