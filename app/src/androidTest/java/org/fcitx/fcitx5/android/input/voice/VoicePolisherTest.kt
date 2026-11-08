/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.os.SystemClock
import android.util.Log
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The polish tagger as packaged (the voice pack, imported on the device first), through the app's onnxruntime (OrtModel.runLongs: int64 in, two
 * outputs): what it does to spoken text is the evaluation's; here, that it runs and does something.
 */
class VoicePolisherTest {

    @Test
    fun aFillerGoesAndMarksComeOnTheDevice() {
        VoicePolisher.open().use { polisher ->
            val raw = "嗯我们明天早上九点出发吧你觉得怎么样"
            val start = SystemClock.elapsedRealtime()
            val text = polisher.stream().finish(raw)
            Log.i("VoicePolisherTest", "${raw.length} chars in ${SystemClock.elapsedRealtime() - start} ms: $text")
            assertFalse(text, text.startsWith("嗯"))
            assertTrue(text, text.any { it in "，。？" })
            assertTrue(text, "明天早上九点出发" in text)
        }
    }

    @Test
    fun itStreamsAsTheTextGrows() {
        VoicePolisher.open().use { polisher ->
            val stream = polisher.stream()
            val said = "那个我觉得这个方案还是可以的就是时间上有点紧我们下周再讨论一下吧"
            var shown = VoicePolishStream.Shown("", "")
            for (n in 4..said.length step 4) shown = stream.update(said.take(n))
            // what is 16 characters behind is frozen, the rest still the tagger's to change
            assertTrue(shown.toString(), shown.polished.isNotEmpty())
            assertTrue(stream.finish(said), stream.finish(said).isNotEmpty())
        }
    }
}
