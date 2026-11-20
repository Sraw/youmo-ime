/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.TextMetrics
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import java.io.File

/** keyboard-scene's example layouts and themes (`scene.data`) and the app's layouts beside them. */
object SceneData {
    private val dir = File(System.getProperty("scene.data") ?: error("scene.data not set"))

    fun read(name: String) = File(dir, name).takeIf { it.exists() }?.readText() ?: File(dir, "../../commonMain/layouts/$name").readText()

    fun theme(name: String): Theme = (ThemeReader.theme(read(name)) as Parsed.Ok).value

    val qwerty: Layout by lazy { (LayoutReader.layout(read("qwerty.xml")) as Parsed.Ok).value }

    /** Every character half its size wide: only that both sides measure alike matters. */
    val measurer = TextMeasurer { text, _, _, size ->
        val w = text.length * size / 2
        TextMetrics(w, Box(0.0, -size * 0.7, w, 0.0), -size, size * 0.3)
    }

    val portrait = Env(1080.0, 720.0, 2.625, input = Input(langKey = true), texts = Texts(imeName = "拼音", returnLabel = "换行"))
}
