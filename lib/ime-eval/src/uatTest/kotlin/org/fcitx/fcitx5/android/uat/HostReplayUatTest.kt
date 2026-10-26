/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.host.KeySyms
import org.fcitx.fcitx5.android.host.Refiner
import org.fcitx.fcitx5.android.eval.parity.Header
import org.fcitx.fcitx5.android.eval.parity.Sequence
import org.fcitx.fcitx5.android.eval.parity.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.fcitx.fcitx5.android.Uat
import org.junit.Test

/**
 * uat/ cases on the Kotlin host ([HostReplay]): what [EngineReplay] cannot reach because fcitx did it
 * (the punctuation module, paging, activate and deactivate, the field around the cursor), as the
 * app runs them.
 */
class HostReplayUatTest {

    @Test
    @Uat("PY-01")
    fun spaceCommitsTheFirstSentence() = with(HostReplay()) {
        type("nihao")
        space()
        expectText("你好")
    }

    @Test
    @Uat("PY-02")
    fun aWholeSentence() = with(HostReplay()) {
        type("womenyiqiqugongyuan")
        space()
        expectText("我们一起去公园")
    }

    @Test
    fun aMarkThroughThePunctuationModuleCommitsTheSentenceFirst() = with(HostReplay()) {
        type("nihao")
        type(",")
        expectText("你好，")
        type(".")
        expectText("你好，。")
    }

    @Test
    fun aMarkAfterADigitIsChineseAsInTheApp() = with(HostReplay()) {
        // the app turns HalfWidthPuncAfterLetterOrNumber off (AddonDefaults): the device types 3。
        type("3.")
        expectText("3。")
    }

    @Test
    fun englishLeftOnRanksTheFirstFieldsWordAsASentenceStart() {
        // as a field focused anew: English's session told the field's start, not made by a switch after it
        val first = HostReplay(engines = UatEngines.fresh(), im = Engines.ENGLISH)
        first.type("work")
        val again = HostReplay(engines = UatEngines.fresh(), im = Engines.ENGLISH)
        again.focus("")
        again.type("work")
        assertEquals(again.shown.take(4), first.shown.take(4))
    }

    @Test
    fun aKeyEventTheEditorTookTellsTheFieldAgainUnlessItsReportWasPredicted() = with(HostReplay(engines = UatEngines.fresh(), im = Engines.ENGLISH)) {
        type("what")
        space()
        enter()
        // the editor's new line, a cursor report not predicted: the engine told the field (tellContextLater)
        assertEquals(text, contextTold)
        // the screen's Backspace: its key event's cursor report predicted (EditingSession.backspace), nothing told
        backspace()
        assertEquals("what \n", contextTold)
        // a hardware one is the editor's own key, not predicted: told
        hardware(KeySyms.BACKSPACE)
        assertEquals("what", text)
        assertEquals(text, contextTold)
    }

    @Test
    fun backspacingEnglishsLastLetterTakesItOut() = with(HostReplay(engines = UatEngines.fresh(), im = Engines.ENGLISH)) {
        space()
        type("wo")
        backspace(2)
        expectText(" ")
    }

    @Test
    fun aBackspacedCommitIsNoLongerTheEmptyFieldsContext() = with(HostReplay(engines = UatEngines.fresh())) {
        type("ni")
        space()
        backspace()
        // the memory of the field lost what Backspace took (handleBackspaceKey): nothing to tell
        // when the field is told next
        tapField()
        assertEquals("", contextTold)
    }

    @Test
    fun theContextIsToldAsTheEditorSaidIt() = with(HostReplay(engines = UatEngines.fresh())) {
        // an empty field the editor answered is a field really empty (CommitJournal), as the service tells it
        assertTrue(contextSaid)
        focus("", field = EngineReplay.Field.PASSWORD)
        assertFalse(contextSaid)
    }

    @Test
    fun leavingTheFieldDropsPinyinTyping() = with(HostReplay()) {
        type("nihao")
        // pinyin's code is above the candidates, not in the field (androidengine.cpp:380-383, 495)
        assertEquals("", clientPreedit)
        assertTrue(preedit.isNotEmpty())
        focus("")
        // nothing committed: fcitx leaves it to the client (ClientUnfocusCommit, instance.cpp:1131-1138),
        // whose field has nothing composing, and androidengine drops the typing (androidengine.cpp:765-771)
        assertEquals("", left)
        assertTrue(commits.isEmpty())
        expectPreedit("")
    }

    @Test
    fun leavingTheFieldKeepsEnglishsComposingWord() = with(HostReplay(im = Engines.ENGLISH)) {
        type("hel")
        val shown = clientPreedit
        assertTrue("English composes in the field (androidengine.cpp:380-383): [$shown] [$preedit] [$text]", shown.isNotEmpty())
        focus("")
        // the field keeps its composing text as the app finishes it; fcitx commits nothing (instance.cpp:1131-1138)
        assertEquals(shown, left)
        assertTrue(commits.isEmpty())
    }

    @Test
    fun aPagedListPagesInTheHost() = with(HostReplay()) {
        pagedList()
        type("shi")
        val first = page
        assertTrue(hasNextPage)
        pageDown()
        assertTrue("the next page: $page after $first", page.isNotEmpty() && page != first)
    }

    @Test
    fun textAfterTheCursorStays() = with(HostReplay()) {
        focus("我说", after = "了")
        type("nihao")
        space()
        expectText("我说你好了")
    }

    @Test
    fun aPasswordFieldTypesLetters() = with(HostReplay()) {
        focus("", EngineReplay.Field.PASSWORD)
        type("abc")
        expectText("abc")
        expectPreedit("")
    }

    @Test
    fun englishTypesTheWord() = with(HostReplay(im = Engines.ENGLISH)) {
        type("hello")
        space()
        assertEquals("hello ", text)
    }

    @Test
    fun theRefineTimerRunsOnThePause() = with(HostReplay()) {
        "womenyiqiqugongyuan".codePoints().forEach { key(it) }
        // the keys typed with no pause: the router's refine timer set, not run yet
        assertTrue("no refine timer after the keys", clock.pending > 0)
        val refinesBefore = refines
        pause()
        // the timer that ran was the refine's: its guards (focus, the same input method, the engine's list) let it through
        assertTrue("no refine slice sent on the pause", refines > refinesBefore)
        // nothing else ran meanwhile to cancel it: the pause ran it out, slice after slice, till the
        // session stopped refining (each slice that refines sets the next)
        assertEquals("timers left after the pause", 0, clock.pending)
        space()
        assertTrue("committed $text", text.isNotEmpty())
    }

    // the learning design's U-31 (a word corrected, offered again on a tap); no uat/ case of its own
    // (EN-11 taps into the corrected word, which this does not), so no @Uat
    @Test
    fun aTapAfterACorrectionOffersTheWordTypedThroughTheRecallKey() = with(HostReplay(im = Engines.ENGLISH)) {
        type("fed teh ")
        expectText("fed the ")
        tapField()
        assertEquals(listOf("teh"), page)
    }

    @Test
    fun aChatFieldEmptiedAfterSendingKeepsItsContextWhileFresh() = with(HostReplay(freshMillis = FRESH)) {
        focus("", name = "chat")
        type("nihao")
        space()
        // sent: the same field again, empty
        focus("", name = "chat")
        assertEquals("你好", contextTold)
        away(FRESH + 1)
        focus("", name = "chat")
        assertEquals("", contextTold)
    }

    @Test
    fun aPasswordFieldTellsTheEngineNothing() = with(HostReplay()) {
        focus("secret", EngineReplay.Field.PASSWORD)
        assertEquals("", contextTold)
    }

    private companion object {
        // the half hour of ContextMemory, shortened: a case's pause is 5 s
        const val FRESH = 60_000L
        const val SHOWN = 6
    }

    // UAT PY-64's steps, as the device ran them (带来 first): the app tells the engine the field only on
    // a cursor report it did not expect, not after its own commits, so 「点了吗。」 typed before is not
    // a context the engine is told; a replay that told it after every commit ranked 的脸 first
    @Test
    fun aSentenceTypedBeforeIsNotToldAfterTheAppsOwnCommits() = with(HostReplay()) {
        type("ta"); pick("他"); type("dl"); pick("带领"); type("ma"); space()
        expectText("他带领吗")
        backspace(3)
        type("dianlema"); pick("点了吗"); type(".")
        type("ta"); pick("他"); type("dl")
        val first = candidates.take(SHOWN)
        pick("点了"); type(".")
        type("ta"); pick("他"); type("dl")
        val second = candidates.take(SHOWN)
        assertEquals("first of $first", "带来", first.first())
        assertTrue("点了 before 带领 in $first", first.indexOf("点了") < first.indexOf("带领"))
        assertEquals("first of $second", "点了", second.first())
    }

    // UAT PY-75's 前提: 带来 kept first through dl, space, 。 three times
    @Test
    fun aWordCommittedThenAMarkKeepsItsPlace() = with(HostReplay()) {
        repeat(3) {
            type("dl")
            expectFirst("带来")
            space()
            type(".")
        }
    }

    @Test
    fun theQuickInputPromptIsInTheDevicesLanguage() = with(HostReplay(engines = UatEngines.fresh(), strings = checkNotNull(ResourceHostStrings.device()))) {
        quick()
        // values-zh-rCN's host_quick_phrase_aux, its quotes keeping the space
        assertEquals("快速输入: ", row)
        backspace()
        unicode()
        assertEquals("Unicode：(输入编码或者描述来搜索 Unicode 字符)", row)
    }

    @Test
    fun aCandidateShownTraditionalIsPickedAsShown() = with(HostReplay(engines = UatEngines.fresh())) {
        toggle("chttrans")
        type("qidebu")
        val shown = page.first()
        assertTrue("$shown converted", shown != candidates.first())
        pickShown(0)
        assertEquals(shown, text)
    }

    @Test
    fun anInputMethodSwitchedToIsToldTheField() = with(HostReplay(engines = UatEngines.fresh())) {
        type("nihao")
        space()
        assertEquals("", contextTold)
        // FcitxInputMethodService's IMChangeEvent: the session switched to told the field (FieldPolicy)
        ime(Engines.ENGLISH)
        assertEquals(text, contextTold)
    }

    @Test
    fun anotherAppsBarIsCoveredOnASwitchOrAHardwareReturn() {
        val header = Header(buildJsonObject { putJsonArray("ims") { add(Engines.PINYIN); add(Engines.ENGLISH) } })
        fun last(vararg steps: Step) = ParityReplay.run(header, Sequence("s", Engines.PINYIN, "text", steps.toList())).states.last()
        val typed = Step("type", "nihao")
        // what the other app showed, until the host updates it
        assertEquals("ni hao", last(typed, Step("app")).preedit)
        assertEquals("", last(typed, Step("app"), Step("ime", Engines.ENGLISH)).preedit)
        assertEquals("", last(typed, Step("app"), Step("hw", "Return")).preedit)
        assertEquals("ni hao", last(typed, Step("app"), Step("hw", "BackSpace")).preedit)
        // the floating window a hardware Return brought up gone as the screen's keyboard is used: the bar as it was
        assertEquals("ni hao", last(typed, Step("app"), Step("hw", "Return"), Step("app")).preedit)
        // typed on the screen after a hardware Return: the bar back, what it shows left on the next app's
        assertEquals("ma", last(typed, Step("hw", "Return"), Step("type", "ma"), Step("app")).preedit)
        // a field of the same app: the bar as it was too, the input method the same
        assertEquals("ni hao", last(typed, Step("app"), Step("field", "text")).preedit)
        // a password field's English is no language key's: no overlay of its name on the bar
        assertEquals("ni hao", last(typed, Step("app"), Step("field", "password")).preedit)
    }

    @Test
    fun anotherAppsEmptyFieldIsToldNothingOfTheFirst() = with(HostReplay(engines = UatEngines.fresh(), im = Engines.ENGLISH)) {
        // English left on: the first field focused twice, both times the same field
        type("keyboard")
        space()
        focus("", app = 2)
        // a field of its own: nothing remembered written in it (ContextMemory)
        assertEquals("", contextTold)
    }

    @Test
    fun aMalformedStepStopsOnlyItsSequence() {
        val header = Header(buildJsonObject { putJsonArray("ims") { add(Engines.PINYIN) } })
        // a hardware key the replay has no name for: F4127's catch, not the shard's run
        val out = ParityReplay.run(header, Sequence("s", Engines.PINYIN, "text", listOf(Step("type", "nihao"), Step("hw", "NoSuchKey"))))
        assertTrue("not stopped", out.error != null)
        // the steps before it kept
        assertEquals(listOf("ni hao"), out.states.map { it.preedit })
    }

    @Test
    fun aToggleUnderSuggestionsGoesByTheKeyboardPutAway() {
        val header = Header(buildJsonObject { putJsonArray("ims") { add(Engines.PINYIN) } })
        val states = ParityReplay.run(header, Sequence("s", Engines.PINYIN, "text", listOf(Step("type", "zaiwen"), Step("space"), Step("toggle", "fullwidth")))).states
        assertTrue("suggestions after the commit", states[1].cands.isNotEmpty())
        // uat.py's expand_toolbar: Back and a tap on the field, which the suggestions do not outlast
        assertEquals(emptyList<String>(), states[2].cands)
    }

    @Test
    fun aSingleLineFieldsEnterPutsTheKeyboardAwayAndATapBringsItBackAtTheEnd() {
        val header = Header(buildJsonObject { putJsonArray("ims") { add(Engines.PINYIN); add(Engines.ENGLISH) } })
        val steps = listOf(Step("type", "test"), Step("enter"), Step("hw", "Left"), Step("type", "x"))
        val states = ParityReplay.run(header, Sequence("s", Engines.ENGLISH, "url", steps)).states
        // the arrow moves the cursor in the field with no keyboard; the letter's tap on the field puts it at the end first
        assertEquals(3 to 3, states[2].sel)
        assertEquals("testx", states[3].text)
    }

    @Test
    fun theKeyboardBackFromUnderTheFloatingWindowIsTappedForAtTheFieldsEnd() {
        val header = Header(buildJsonObject { putJsonArray("ims") { add(Engines.PINYIN); add(Engines.ENGLISH) } })
        val steps = listOf(Step("type", "test"), Step("space"), Step("hw", "Left"), Step("hw", "Return"), Step("type", "x"))
        val states = ParityReplay.run(header, Sequence("s", Engines.ENGLISH, "text", steps)).states
        // the Return typed where the arrow left the cursor, before the space
        assertEquals("test\n ", states[3].text)
        // the letter after the field's end, where the tap put the cursor
        assertEquals("test\n x", states[4].text)
    }

    @Test
    fun keysTypedTogetherAreTappedApartAsTheDevicesDriverTapsThem() = with(HostReplay(engines = UatEngines.fresh(), im = Engines.T9)) {
        val start = clock.uptimeMillis()
        type("94")
        // 200 ms between the two taps, as an `input tap` each takes: the refine's pause runs out between
        // them; then the user's pause
        assertTrue(200L > Refiner.PAUSE_MILLIS)
        assertEquals(200L + 5_000L, clock.uptimeMillis() - start)
    }

    @Test
    fun quickInputCommitsWhatIsTypedFirst() = with(HostReplay(engines = UatEngines.fresh())) {
        type("ni")
        val first = candidates.first()
        quick()
        // PendingInputPolicy: Chinese composing, the first candidate committed
        assertEquals(first, text)
    }

    @Test
    fun unicodeResetsEnglishsKnowledgeOfTheField() {
        val opened = HostReplay(engines = UatEngines.fresh(), im = Engines.ENGLISH)
        opened.unicode()
        opened.backspace(3)
        opened.type("work")
        val reset = HostReplay(engines = UatEngines.fresh(), im = Engines.ENGLISH)
        reset.core.reset()
        reset.type("work")
        // the reset before the mode (commitAndReset): English ranks as where nothing is known
        assertEquals(reset.shown.take(5), opened.shown.take(5))
    }
}
