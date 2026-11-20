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

fun KeyboardHarness.toolbarHandwriting(): ToolButton? = KeyboardHarness.all(inputView).filterIsInstance<ToolButton>()
    .firstOrNull { shown(it) && it.contentDescription == service.getString(R.string.handwriting) }

/** One check for every test of a greyed 手写, so the handwriting UATs cannot drift apart. */
fun KeyboardHarness.assertHandwritingGreyed(where: String) {
    val greyed = service.getString(R.string.handwriting_unavailable, service.getString(R.string.handwriting))
    val button = KeyboardHarness.all(inputView).filterIsInstance<ToolButton>().firstOrNull { shown(it) && it.contentDescription == greyed }
    assertNotNull("手写 greyed $where: ${texts(inputView)}", button)
    assertEquals(where, 0.38f, button!!.alpha, 0.01f)
    assertNull("not 手写 as offered $where", toolbarHandwriting())
}
