/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

/**
 * The UAT case(s) (the uat/ case files) this test asserts the 预期 of, on the JVM instead of a device.
 * `uat/tools/uat.py jvm` finds them in the sources and records their JUnit results by id.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Uat(vararg val ids: String)
