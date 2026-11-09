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

/**
 * [name] of src/commonTest/data, or of src/commonMain/layouts (the layouts the app draws, qwerty.xml:
 * one copy, the app's, which the tests and snapshots are of), or of src/commonMain/themes (the built-in
 * themes, pixel.xml).
 */
internal fun testData(name: String): String =
    readText("$TEST_DATA/$name") ?: readText("$TEST_DATA/../../commonMain/layouts/$name")
        ?: readText("$TEST_DATA/../../commonMain/themes/$name") ?: error("no $TEST_DATA/$name")

/** The names of the files in [dir], sorted. */
internal expect fun listNames(dir: String): List<String>
