/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.data.pack.AppData
import org.fcitx.fcitx5.android.handwriting.OrtModel
import org.junit.Test

/** Nothing else opens these models in a test, and `OrtModel` changed for the voice tagger's int64 run. Needs the text pack imported. */
class HandwritingModelsTest {

    @Test
    fun bothModelsOpen() {
        for (name in listOf("char_image", "char_path")) OrtModel.open(AppData.map("handwriting/$name.ort")).close()
    }
}
