/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 联想 going on with a whole phrase the user put in whole (U-73), through the engines with the app's data (uat/06-pinyin.md, from B0). */
class PhraseUatTest {
    @Test
    @Uat("PY-106")
    fun aWholeSentenceTypedThreeTimesGoesOnAfterItsStart() = with(EngineReplay()) {
        val whole = typedWhole()
        assertTrue("「$whole」", whole.length >= 5)
        assertTrue("after the first: $page", whole.substring(1) !in afterNi())
        typedWhole()
        assertTrue("after the second: $page", whole.substring(1) !in afterNi())
        typedWhole()
        assertEquals(whole.substring(1), afterNi().first())
        // 你好 typed whole: the rest after it
        focus("")
        type("nihao")
        pick(whole.take(2))
        expectFirst(whole.substring(2))
        // picked, the phrase is put in whole, and what follows its end offered
        pickAt(0)
        expectText(whole)
        assertTrue("after the phrase: $page", page.isNotEmpty() && whole.substring(2) !in page)
    }

    @Test
    @Uat("PY-106")
    fun aPhraseTurnedOffOrDeletedIsNoLongerOffered() = with(EngineReplay()) {
        val whole = (1..3).map { typedWhole() }.last()
        val rest = whole.substring(1)
        afterNi()
        // undone at once (only before typing on, as the tip stays)
        val off = learn(LearningOp.NoPredict(0, rest)) as LearningResult.Done
        assertEquals(LearningResult.Done::class, learn(LearningOp.Undo(off.undo))::class)
        assertEquals(rest, afterNi().first())
        learn(LearningOp.NoPredict(0, rest))
        assertTrue("$page", rest !in afterNi())
        val listed = engines.learning(im, LearningOp.ListItems(LearningOp.Listed.NO_PREDICT)) as LearningResult.Listed
        assertEquals(listOf("" to whole), listed.items.map { it.prev to it.text })
        // taken back from the list, as 恢复联想 does
        engines.learning(im, LearningOp.RemoveItems(listed.items))
        assertEquals(rest, afterNi().first())
        // deleted: from nothing again, three more times to come back
        assertEquals(LearningResult.Done::class, learn(LearningOp.Delete(0, rest))::class)
        assertTrue("$page", rest !in afterNi())
        repeat(2) { typedWhole() }
        assertTrue("$page", rest !in afterNi())
        typedWhole()
        assertEquals(rest, afterNi().first())
    }

    // C53: committed a piece at a time, 联想 goes a word at a time, as before
    @Test
    @Uat("PY-107")
    fun piecesCommittedApartAreNoPhrase() = with(EngineReplay()) {
        repeat(3) {
            focus("")
            type("ni")
            pick("你")
            type("haoya")
            pick("好呀")
            type("qinaide")
            pick("亲爱的")
        }
        val after = afterNi()
        assertEquals("好呀", after.first())
        assertTrue("$after", after.none { it.length > 2 && it.startsWith("好呀") })
    }

    // U-87: a field that learns nothing learns no phrase (a URL's flags are the host's: PhrasesTest); a table offers what pinyin learned
    @Test
    @Uat("PY-108")
    fun noPhraseWhereNothingIsLearnedAndOneInATable() = with(EngineReplay()) {
        repeat(3) { typedWhole(EngineReplay.Field.NO_LEARNING) }
        val whole = typedWhole()
        assertTrue("$page", whole.substring(1) !in afterNi())
        repeat(2) { typedWhole() }
        ime(InputMethodCatalog.WUBI)
        focus("")
        type("wqiy")
        pick("你")
        expectFirst(whole.substring(1))
    }
}

/** 你好呀亲爱的 typed whole in a field focused afresh, space committing it: the text put in. */
internal fun EngineReplay.typedWhole(field: EngineReplay.Field = EngineReplay.Field.TEXT): String {
    focus("", field)
    type("nihaoyaqinaide")
    space()
    return text
}

/** 你 picked in a field focused afresh: what may follow it. */
internal fun EngineReplay.afterNi(): List<String> {
    focus("", EngineReplay.Field.TEXT)
    type("ni")
    pick("你")
    return candidates
}
