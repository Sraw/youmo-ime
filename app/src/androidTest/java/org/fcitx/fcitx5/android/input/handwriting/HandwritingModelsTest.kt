package org.fcitx.fcitx5.android.input.handwriting

import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.handwriting.OrtModel
import org.junit.Test

/**
 * The handwriting models open on the device: `OrtModel` takes one input and float outputs only
 * since the voice tagger's int64 run was added (ort-jni.cpp), and nothing else opens them in a test.
 */
class HandwritingModelsTest {

    @Test
    fun bothModelsOpen() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        for (name in listOf("char_image", "char_path")) OrtModel.open(assets, "handwriting/$name.ort").close()
    }
}
