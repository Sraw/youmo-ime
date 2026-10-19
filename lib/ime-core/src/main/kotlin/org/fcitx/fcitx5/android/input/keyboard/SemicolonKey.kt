/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme

/** Whether the letters show a `;` key: in 双拼 whose [scheme] types a final on it (微软, 紫光). */
fun semicolonKey(ime: String, scheme: ShuangpinScheme): Boolean = ime == Engines.SHUANGPIN && scheme.types(';')
