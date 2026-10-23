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

/**
 * The backup rules leave out what [DataManager] syncs into its dataDir, in device-protected storage
 * (device_root). Rules for files/ match nothing
 * there, and a restored descriptor.json would keep sync() from installing what the restore skipped.
 */
class BackupRulesTest {

    @Test
    fun cloudBackupAndDeviceTransferLeaveOutTheSyncedAssets() {
        val rules = parse("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = excludes(rules.getElementsByTagName(section).item(0) as Element)
            for (path in SYNCED) assertTrue("$section: device_root/$path", ("device_root" to path) in excluded)
        }
    }

    /** Clips marked sensitive (a password copied) go no further than the phone: the rules cannot pick rows, so none of them. */
    @Test
    fun cloudBackupAndDeviceTransferLeaveOutTheClipboard() {
        val rules = parse("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = excludes(rules.getElementsByTagName(section).item(0) as Element)
            // device_database: the one a start before the first unlock made, in device-protected storage
            for (domain in listOf("database", "device_database")) {
                for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                    val path = CLIPBOARD_DATABASE + suffix
                    assertTrue("$section: $domain/$path", (domain to path) in excluded)
                }
            }
        }
    }

    @Test
    fun fullBackupLeavesOutTheSyncedAssets() {
        val excluded = excludes(parse("src/main/res/xml/full_backup_content.xml"))
        for (path in SYNCED) assertTrue("device_root/$path", ("device_root" to path) in excluded)
    }

    // :app unit tests run without merged resources, so the rules are read from the source tree
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
        // the top of the hierarchy DataManager.sync() installs, and deleteAndSync() removes
        private val SYNCED = listOf("usr", "descriptor.json", "README.md")
    }
}
