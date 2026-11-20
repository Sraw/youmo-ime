/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.junit.rules.ExternalResource

/** [SceneThemes.source] as a test sets it, the real one put back after the test. */
class SceneThemesRule : ExternalResource() {
    private val real = SceneThemes.source

    fun styled(vararg styles: String) {
        SceneThemes.source = { look, name, dark -> LookTheme.xml(look, name, dark).replace("</theme>", styles.joinToString("\n", postfix = "\n</theme>")) }
    }

    fun real() {
        SceneThemes.source = real
    }

    override fun after() = real()
}

/** A pixel at least each way. */
internal fun drawn(v: View): Bitmap = Bitmap.createBitmap(maxOf(1, v.width), maxOf(1, v.height), Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

/** Written as [dir]/[name].png only where [asked]: not on every run, CI's too. */
internal fun keepPicture(asked: Boolean, v: View, dir: String, name: String) {
    if (!asked) return
    File(File(dir).also { it.mkdirs() }, "$name.png").outputStream().use { drawn(v).compress(Bitmap.CompressFormat.PNG, 100, it) }
}
