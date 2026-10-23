/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// minSdk and the pinned SDK, the two ends of the supported range
@Config(sdk = [29, 35])
class ContentResolverTest {

    /** A provider that names each document by the uri's `name` parameter: NULL without one. */
    class Names : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
            addRow(arrayOf<Any?>(uri.getQueryParameter("name")))
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private val resolver = RuntimeEnvironment.getApplication().contentResolver

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(Names::class.java, AUTHORITY)
    }

    /** The name [Names] gives a document whose path ends in doc.scel. */
    private fun named(name: String?): String? {
        val builder = Uri.parse("content://$AUTHORITY/document/doc.scel").buildUpon()
        name?.let { builder.appendQueryParameter("name", it) }
        return resolver.queryFileName(builder.build())
    }

    @Test
    fun aFileWithNoProviderToAskIsNamedByItsPath() {
        assertEquals("sogou.scel", resolver.queryFileName(Uri.parse("file:///sdcard/Download/sogou.scel")))
        assertEquals("1234", resolver.queryFileName(Uri.parse("content://nobody.here/files/1234")))
        assertNull(resolver.queryFileName(Uri.parse("file:///")))
        assertEquals("y.scel", resolver.queryFileName(Uri.parse("file:///x/..%2F..%2Fy.scel")))
        assertNull(resolver.queryFileName(Uri.parse("file:///x/..")))
    }

    @Test
    fun aFileIsNamedByItsProvider() {
        assertEquals("sogou.scel", named("sogou.scel"))
    }

    /** Callers write a temporary file by the name: another app's provider must not pick where. */
    @Test
    fun aProvidersNameCannotReachOutOfTheDirectoryItIsWrittenIn() {
        assertEquals("x.xml", named("../../shared_prefs/x.xml"))
        assertEquals("b", named("a/../b"))
        assertNull(named(".."))
        assertNull(named("."))
    }

    @Test
    fun aProviderThatGivesNoNameOrAnEmptyOneLeavesItToThePath() {
        assertEquals("doc.scel", named(null))
        assertEquals("doc.scel", named(""))
    }

    private companion object {
        const val AUTHORITY = "org.fcitx.fcitx5.android.test.names"
    }
}
