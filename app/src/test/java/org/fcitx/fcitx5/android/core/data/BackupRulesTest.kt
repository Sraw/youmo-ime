/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.fcitx.fcitx5.android.data.clipboard.CLIPBOARD_DATABASE
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Rules for files/ match nothing in device-protected storage, and a restored descriptor.json would keep sync() from installing what the restore skipped. */
class BackupRulesTest {

    @Test
    fun cloudBackupAndDeviceTransferLeaveOutTheSyncedAssets() {
        val rules = parse("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = excludes(rules.getElementsByTagName(section).item(0) as Element)
            for (path in SYNCED) assertTrue("$section: device_root/$path", ("device_root" to path) in excluded)
        }
    }

    /** Sensitive clips must not leave the phone, and the rules cannot pick rows. */
    @Test
    fun cloudBackupAndDeviceTransferLeaveOutTheClipboard() {
        val rules = parse("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = excludes(rules.getElementsByTagName(section).item(0) as Element)
            // device_database: the one made by a start before the first unlock
            for (domain in listOf("database", "device_database")) {
                for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                    val path = CLIPBOARD_DATABASE + suffix
                    assertTrue("$section: $domain/$path", (domain to path) in excluded)
                }
            }
            // the phone's favourites kept at a restore, sensitive ones too
            for (path in listOf("favorites-to-merge.json", "favorites-to-merge.json.tmp")) {
                assertTrue("$section: database/$path", ("database" to path) in excluded)
            }
        }
    }

    @Test
    fun fullBackupLeavesOutTheSyncedAssets() {
        val excluded = excludes(parse("src/main/res/xml/full_backup_content.xml"))
        for (path in SYNCED) assertTrue("device_root/$path", ("device_root" to path) in excluded)
    }

    // :app unit tests have no merged resources, so the rules are read from the source tree
    private fun parse(path: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement

    private fun excludes(section: Element): Set<Pair<String, String>> {
        val nodes = section.getElementsByTagName("exclude")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .map { it.getAttribute("domain") to it.getAttribute("path") }
            .toSet()
    }

    companion object {
        private val SYNCED = listOf("usr", "descriptor.json", "README.md")
    }
}
