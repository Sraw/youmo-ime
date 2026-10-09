/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The backup rules leave out what [DataManager] syncs into its dataDir: device-protected storage
 * (device_root) from Android 7, the data root (root) on Android 6. Rules for files/ match nothing
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

    @Test
    fun fullBackupLeavesOutTheSyncedAssetsOnEveryVersion() {
        val excluded = excludes(parse("src/main/res/xml/full_backup_content.xml"))
        for (domain in listOf("device_root", "root")) {
            for (path in SYNCED) assertTrue("$domain/$path", (domain to path) in excluded)
        }
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
