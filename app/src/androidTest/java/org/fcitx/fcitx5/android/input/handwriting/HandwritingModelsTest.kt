/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.data.pack.AppData
import org.fcitx.fcitx5.android.handwriting.OrtModel
import org.junit.Test

/**
 * The handwriting models open on the device: `OrtModel` takes one input and float outputs only
 * since the voice tagger's int64 run was added (ort-jni.cpp), and nothing else opens them in a test.
 * From the text pack, imported on the device first (uat.py's packs; specs/testing.md).
 */
class HandwritingModelsTest {

    @Test
    fun bothModelsOpen() {
        for (name in listOf("char_image", "char_path")) OrtModel.open(AppData.map("handwriting/$name.ort")).close()
    }
}
