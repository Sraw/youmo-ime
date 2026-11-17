/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/** The toolbar's 手写 as offered (not greyed), shown, or null. */
fun KeyboardHarness.toolbarHandwriting(): ToolButton? = KeyboardHarness.all(inputView).filterIsInstance<ToolButton>()
    .firstOrNull { shown(it) && it.contentDescription == service.getString(R.string.handwriting) }

/**
 * 手写 where it writes nothing (a password, English): on the bar still, greyed and said so (061-handwriting-toolbar Q2).
 * One check for every test of it, so the handwriting UATs cannot drift apart (review R26 F8462).
 */
fun KeyboardHarness.assertHandwritingGreyed(where: String) {
    val greyed = service.getString(R.string.handwriting_unavailable, service.getString(R.string.handwriting))
    val button = KeyboardHarness.all(inputView).filterIsInstance<ToolButton>().firstOrNull { shown(it) && it.contentDescription == greyed }
    assertNotNull("手写 greyed $where: ${texts(inputView)}", button)
    assertEquals(where, 0.38f, button!!.alpha, 0.01f)
    assertNull("not 手写 as offered $where", toolbarHandwriting())
}
