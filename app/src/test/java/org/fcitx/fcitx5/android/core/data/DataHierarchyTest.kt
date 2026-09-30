/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

// Robolectric for android.util.Base64 in the checksum
@RunWith(RobolectricTestRunner::class)
class DataHierarchyTest {

    private val shipped = DataDescriptor(
        sha256 = "assets-v2",
        files = mapOf("usr" to "", "usr/a.txt" to "aaa", "usr/b.txt" to "bbb"),
        symlinks = mapOf("usr/link" to "usr/a.txt")
    )

    private val nothingInstalled = DataDescriptor("", emptyMap(), emptyMap())

    @Test
    fun aFreshInstallCopiesEveryFileAndCreatesTheLinks() {
        assertEquals(
            setOf(
                FileAction.CreateFile("usr/a.txt"),
                FileAction.CreateFile("usr/b.txt"),
                FileAction.CreateSymlink("usr/link", "usr/a.txt")
            ),
            DataHierarchy.diff(nothingInstalled, shipped).toSet()
        )
    }

    @Test
    fun nothingHappensWhenTheAssetsAreUnchanged() {
        assertEquals(emptyList<FileAction>(), DataHierarchy.diff(DataHierarchy.installed(shipped), shipped))
    }

    @Test
    fun anInstallFromBeforePluginsWereRemovedIsRecognisedAsUpToDate() {
        // the checksum older versions saved: Base64 of the SHA-256 of the merged descriptors'
        // checksums, which with no plugins is just the app's own. Spelled out here so a change to
        // the formula can't pass by also changing the expectation, and force every install to re-sync
        val saved = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(shipped.sha256.encodeToByteArray()), 0
        ).trim()
        val old = DataDescriptor(saved, shipped.files, shipped.symlinks)
        assertEquals(emptyList<FileAction>(), DataHierarchy.diff(old, shipped))
    }

    @Test
    fun changedFilesAreUpdatedAndLeftoversRemoved() {
        // what an older version left behind, e.g. files a plugin used to install
        val old = DataDescriptor(
            sha256 = "assets-v1",
            files = mapOf(
                "usr" to "", "usr/a.txt" to "old", "usr/b.txt" to "bbb",
                "usr/plugin" to "", "usr/plugin/x.dict" to "xxx"
            ),
            symlinks = mapOf("usr/link" to "usr/a.txt", "usr/stale" to "usr/plugin/x.dict")
        )
        assertEquals(
            setOf(
                FileAction.UpdateFile("usr/a.txt"),
                FileAction.DeleteDir("usr/plugin"),
                FileAction.DeleteFile("usr/plugin/x.dict"),
                FileAction.DeleteFile("usr/stale")
            ),
            DataHierarchy.diff(old, shipped).toSet()
        )
    }
}
