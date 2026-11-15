/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

/**
 * The UAT case(s) (the uat/ case files) this test asserts the 预期 of, as ime-core's `org.fcitx.fcitx5.android.Uat`
 * (this module cannot see that one): `uat/tools/uat.py jvm` reads both and records the jvmTest results by id.
 */
@Target(AnnotationTarget.FUNCTION)
annotation class Uat(vararg val ids: String)
