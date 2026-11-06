/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

/** The file at [path] as UTF-8, or null where there is none. */
internal expect fun readText(path: String): String?

internal expect fun writeText(path: String, text: String)

/** Whether this run writes the snapshots anew (-Psnapshots.record=true; the JVM's only). */
internal expect fun recording(): Boolean

/** src/commonTest/data, as the build says (build.gradle.kts: a system property, or node's environment). */
internal expect fun testDataDir(): String

internal val TEST_DATA: String by lazy { testDataDir() }

internal fun testData(name: String): String = readText("$TEST_DATA/$name") ?: error("no $TEST_DATA/$name")
