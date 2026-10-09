/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Opening a downloaded dictionary offers the app for every kind it imports, a word pack too. */
class DictionaryIntentFilterTest {

    @Test
    fun theViewFilterNamesEveryDictionaryExtension() {
        val data = viewFilter().getElementsByTagName("data")
        val attributes = (0 until data.length).map { data.item(it) as Element }
        val patterns = attributes.map { it.getAttributeNS(ANDROID_NS, "pathPattern") }
        val suffixes = attributes.map { it.getAttributeNS(ANDROID_NS, "pathSuffix") }
        for (type in PinyinDictionary.Type.entries) {
            assertTrue(type.ext, """.*\\.${type.ext}""" in patterns)
            assertTrue(type.ext, ".${type.ext}" in suffixes)
        }
    }

    // :app unit tests run without the merged manifest, so the source one is read
    private fun viewFilter(): Element {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement
        val filters = manifest.getElementsByTagName("intent-filter")
        return (0 until filters.length).map { filters.item(it) as Element }.single { filter ->
            val actions = filter.getElementsByTagName("action")
            (0 until actions.length).any {
                (actions.item(it) as Element).getAttributeNS(ANDROID_NS, "name") == "android.intent.action.VIEW"
            }
        }
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
