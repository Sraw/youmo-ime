/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android 13's per-app languages are the ones the app's own picker offers ([AppLanguage]), not
 * every values-* directory: those hold partial translations too, mostly English once picked.
 */
class LocalesConfigTest {

    @Test
    fun theSystemOffersTheLanguagesTheAppDoes() {
        // :app unit tests run without merged resources, so the config is read from the source tree
        val config = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/res/xml/locales_config.xml")).documentElement
        val nodes = config.getElementsByTagName("locale")
        val listed = (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
        assertEquals(AppLanguage.tags.filter { it.isNotEmpty() }.toSet(), listed.toSet())
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
