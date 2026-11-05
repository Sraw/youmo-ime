/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceDisfluencyTest {

    private fun clean(text: String) = VoiceDisfluency.clean(text)

    @Test
    fun fillerSoundsGoTheirMarksMerged() {
        assertEquals("我觉得吧就是不用把", clean("我觉得吧嗯就是不用把"))
        assertEquals("可以设计一款", clean("可以呃设计一款"))
        assertEquals("好的，然后", clean("好的，嗯，然后"))
        assertEquals("首先，我们", clean("首先，呃呃，我们"))
        assertEquals("at the start nothing was said before", "我觉得可以", clean("嗯，我觉得可以"))
        assertEquals("我觉得可以", clean("嗯，呃，我觉得可以"))
        assertEquals("the stronger mark of the two", "是吗？然后", clean("是吗？嗯，然后"))
        assertEquals("就这样。", clean("就这样，嗯。"))
        assertEquals("a question's mark first", "去不去？好", clean("去不去。嗯？好"))
        assertEquals("at the end only a mark that ends", "好的", clean("好的，嗯"))
        assertEquals("a pause's after the filler only is its own", "把那个报名费", clean("把那个嗯，报名费"))
        assertEquals("OK, good", clean("OK, 嗯, good"))
        assertEquals("OK good", clean("OK 嗯 good"))
        assertEquals("OK good", clean("OK嗯good"))
    }

    @Test
    fun whatSaysSomethingStays() {
        assertEquals("嗯嗯，好的", clean("嗯嗯，好的"))
        assertEquals("哦，原来如此", clean("哦，原来如此"))
        assertEquals("对啊", clean("对啊"))
        assertEquals("订单总额为三十亿", clean("订单总额为三十亿"))
        assertEquals("金额。", clean("金额。"))
        assertEquals("额外的", clean("额外的"))
    }

    @Test
    fun aLoneEOrWuIsAFiller() {
        assertEquals("我想", clean("额，我想"))
        assertEquals("然后，我想", clean("然后，额，我想"))
        assertEquals("好", clean("唔，唔，好"))
        assertEquals("好的，我想", clean("好的，唔，我想"))
        assertEquals("唔该", clean("唔该"))
        assertEquals("one after another", "好", clean("，呃额，好"))
    }

    @Test
    fun aStammeredWordOnce() {
        assertEquals("这个产品", clean("这个这个产品"))
        assertEquals("这个产品", clean("这个这个这个产品"))
        assertEquals("我觉得", clean("我我觉得"))
        assertEquals("我觉得", clean("我，我觉得"))
        assertEquals("但是他没来", clean("但是但是他没来"))
        assertEquals("我们去", clean("我我们去"))
        assertEquals("我觉得", clean("我。我觉得"))
        assertEquals("这个不行", clean("这个，这个不行"))
    }

    @Test
    fun aClausesEndAndTheNextsStartAreNotAStammer() {
        for (said in listOf("我爱你，你爱我吗", "我喜欢他。他很好", "他打了我，我很生气", "就是这个，这个不行", "现在，在电脑上", "其他，他说"))
            assertEquals(said, clean(said))
        assertEquals("我爱你，你爱我", clean("我爱你，你你爱我"))
        // with no mark between, taken as a stammer: in the evaluation sets they all were (然后我我觉得)
        assertEquals("然后我觉得", clean("然后我我觉得"))
        assertEquals("我告诉你别去", clean("我告诉你你别去"))
    }

    @Test
    fun aWordsEndAndTheSameWordAfterAreNotAStammer() {
        assertEquals("人们现在在电脑上写", clean("人们现在在电脑上写"))
        assertEquals("其他他们", clean("其他他们"))
        assertEquals("现在在电脑上", clean("现在在在电脑上"))
        assertEquals("用拖把把地拖干净", clean("用拖把把地拖干净"))
        assertEquals("其它它们", clean("其它它们"))
        // and the next word's start: 要要求 is 要 and 要求
        for (said in listOf("我要要求退款", "我们就就业问题讨论", "他被被告起诉"))
            assertEquals(said, clean(said))
        assertEquals("我要要求退款", clean("我要要要求退款"))
        assertEquals("要要求", clean("要要求"))
    }

    @Test
    fun aWordDoubledToMeanSomethingStays() {
        for (said in listOf("看看", "刚刚到", "对对对", "好好学习", "可以可以", "什么什么的", "我的我的", "的的确确", "从从容容", "六六六", "妈妈", "等等"))
            assertEquals(said, clean(said))
        // a phrase said again is left: as often meant (讨论讨论, 你好你好, 不是不是) as stammered (跟我们跟我们)
        assertEquals("跟我们跟我们说", clean("跟我们跟我们说"))
        assertEquals("no, no", "不不", clean("不不"))
        assertEquals("不不。", clean("不不。"))
        assertEquals("不，不，我是说", clean("不，不，我是说"))
        assertEquals("麻烦让让", clean("麻烦让让"))
        assertEquals("我去会会他", clean("我去会会他"))
        assertEquals("我不去", clean("我不不去"))
    }

    @Test
    fun nothingToDoLeavesTheTextAsIs() {
        assertEquals("今天天气很好。", clean("今天天气很好。"))
        assertEquals("", clean(""))
    }
}
