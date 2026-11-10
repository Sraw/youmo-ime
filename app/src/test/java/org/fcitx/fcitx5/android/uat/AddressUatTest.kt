/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.res.Configuration
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.directBoot
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.address.AddressLists
import org.fcitx.fcitx5.android.input.address.AddressStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * uat/02-keyboard.md's address suggestions (AddressStrip over AddressSuggestions): what the bar
 * offers in an email's and a URL's field, what a tap leaves there, and what is learned leaving it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class AddressUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private val h = KeyboardHarness()

    private val internal = AppPrefs.getInstance().internal
    private val keyboard = AppPrefs.getInstance().keyboard

    private val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    private val url = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

    @Before
    fun nothingLearned() {
        prefs.set(internal.addressCounts, "")
        prefs.set(internal.rememberedAddresses, "")
        prefs.set(keyboard.emailSuffixes, AddressLists.serialize(AddressLists.DEFAULT_EMAIL))
        prefs.set(keyboard.urlSuffixes, AddressLists.serialize(AddressLists.DEFAULT_URL))
        prefs.set(keyboard.rememberAddresses, true)
        prefs.set(internal.useHabits, true)
    }

    @After
    fun close() = h.close()

    private var fieldId = 0

    // another field focused: what the one before was left with is learned
    private fun next(inputType: Int = InputType.TYPE_CLASS_TEXT, imeOptions: Int = 0) = h.start(inputType, imeOptions, fieldId = ++fieldId)

    // the strip's chips: in a row in a scroll view, which the bar's other buttons are not
    private fun chipViews() = KeyboardHarness.all(h.bar).filterIsInstance<TextView>()
        .filter { h.shown(it) && it.parent?.parent is HorizontalScrollView }

    private fun chips() = chipViews().map { it.text.toString() }.toList()

    private fun tap(shown: String) = h.tap(chipViews().first { it.text.toString() == shown })

    // the field as if typed: its text, the cursor at the end
    private fun typed(text: String) {
        h.field.setText(text)
        h.field.setSelection(text.length)
        h.idle()
    }

    private fun field() = h.field.text.toString().let { it.substring(0, h.field.selectionStart) + "|" + it.substring(h.field.selectionStart) }

    @Test
    @Uat("KB-44")
    fun anEmptyEmailFieldOffersTheSuffixesTheCursorStaysBeforeTheAt() {
        next(email)
        assertEquals(listOf("@qq.com", "@163.com", "@gmail.com", "@outlook.com", "@126.com", "@foxmail.com", "@icloud.com"), chips())
        tap("@gmail.com")
        assertEquals("|@gmail.com", field())
        // a plain field none
        next()
        assertEquals(emptyList<String>(), chips())
    }

    @Test
    @Uat("KB-45")
    fun aNameTypedGetsTheSuffixTheCursorAtTheEnd() {
        next(email)
        typed("zhang")
        tap("@163.com")
        assertEquals("zhang@163.com|", field())
        // the address whole: nothing more
        assertEquals(emptyList<String>(), chips())
    }

    @Test
    @Uat("KB-46")
    fun afterTheAtTheLettersTypedFilter() {
        next(email)
        typed("zhang@g")
        assertEquals(listOf("@gmail.com"), chips())
        tap("@gmail.com")
        assertEquals("zhang@gmail.com|", field())
    }

    @Test
    @Uat("KB-47")
    fun aUrlFieldGetsItsStartsThenTheSuffixes() {
        next(url)
        assertEquals(listOf("https://", "www."), chips())
        tap("https://")
        assertEquals(listOf("www."), chips())
        typed("https://www.example")
        assertEquals(listOf(".com", ".cn", ".com.cn", ".net", ".org"), chips())
        tap(".cn")
        assertEquals("https://www.example.cn|", field())
        assertEquals(emptyList<String>(), chips())
        // a Chinese word is no host: no suffix
        typed("天气")
        assertEquals(emptyList<String>(), chips())
    }

    @Test
    @Uat("KB-48")
    fun theUsersOwnListsAreOffered() {
        AddressStore.setEmail(listOf("work.cn", "qq.com"))
        AddressStore.setUrl(listOf(".io"))
        next(email)
        assertEquals(listOf("@work.cn", "@qq.com"), chips())
        next(url)
        typed("github")
        assertEquals(listOf(".io"), chips())
    }

    @Test
    @Uat("KB-49")
    fun addressesTypedAreRememberedAndTheirSuffixesComeFirst() {
        next(email)
        typed("zhang.san@outlook.com")
        // left: the next field's start
        next(email)
        assertEquals(listOf("zhang.san@outlook.com", "@outlook.com", "@qq.com"), chips().take(3))
        typed("zh")
        assertEquals("zhang.san@outlook.com", chips().first())
        tap("zhang.san@outlook.com")
        assertEquals("zhang.san@outlook.com|", field())
        // turned off: none offered, none remembered, those remembered kept, the suffixes still counted
        prefs.set(keyboard.rememberAddresses, false)
        next(email)
        assertEquals("@outlook.com", chips().first())
        typed("li@126.com")
        next(email)
        // the suffixes alone: a remembered address does not start with @
        assertTrue(chips().toString(), chips().all { it.startsWith("@") })
        assertEquals(listOf("zhang.san@outlook.com"), AddressStore.book().addresses.map { it.address })
        assertEquals(1, AddressStore.book().count("@126.com"))
        // on again: what was remembered offered again
        prefs.set(keyboard.rememberAddresses, true)
        next(email)
        assertEquals("zhang.san@outlook.com", chips().first())
        // a URL's host's suffix only
        next(url)
        typed("https://www.example.org/a?b=c")
        next(url)
        typed("x")
        assertEquals(".org", chips().first())
        assertTrue(internal.rememberedAddresses.getValue(), "example" !in internal.rememberedAddresses.getValue() + internal.addressCounts.getValue())
    }

    @Test
    @Uat("KB-50")
    fun incognitoFieldsAndNoHabitsLearnNothing() {
        next(email, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        // offered, as anywhere
        assertEquals("@qq.com", chips().first())
        typed("secret@gmail.com")
        next(email)
        assertEquals("", internal.rememberedAddresses.getValue())
        assertEquals("", internal.addressCounts.getValue())
        // 不用我的输入习惯: nothing learned, nothing shown of what was
        next(email)
        typed("zhang@163.com")
        next(email)
        assertEquals("zhang@163.com", chips().first())
        prefs.set(internal.useHabits, false)
        next(email)
        assertEquals("@qq.com", chips().first())
        typed("li@126.com")
        next(email)
        assertEquals(listOf("zhang@163.com"), AddressStore.book().addresses.map { it.address })
    }

    @Test
    @Uat("KB-51")
    fun aRestartedFieldOffersNothingOfTheOneBefore() {
        next(email)
        assertEquals("@qq.com", chips().first())
        // a WebView's field told anew as a phone's: the email's suffixes gone
        h.restart(InputType.TYPE_CLASS_PHONE)
        assertEquals(emptyList<String>(), chips())
        h.restart(email)
        assertEquals("@qq.com", chips().first())
        h.restart(InputType.TYPE_CLASS_TEXT)
        assertEquals(emptyList<String>(), chips())
    }

    // the keyboard put away and brought back in the same field
    private fun hideAndShow() {
        h.service.onFinishInputView(true)
        h.idle()
        h.service.onStartInputView(h.info, false)
        h.idle()
        h.relayout()
    }

    @Test
    @Uat("KB-52")
    fun halfAnAddressIsNotLearnedTillTheFieldIsLeft() {
        val half = "zhang@qq.c"
        // the keyboard hidden, the bar made anew by a setting, the screen turned, the app's restart: the same field
        val ways = listOf<Pair<String, () -> Unit>>(
            "hidden" to ::hideAndShow,
            "rebuilt" to {
                // a setting that makes the keyboard anew (recreateInputViewPrefs) and changes nothing here: a
                // space more is still the standard letters (NineKeyLetters.parse); PrefsRule puts it back
                prefs.set(keyboard.t9Letters, keyboard.t9Letters.getValue() + " ")
                h.idle()
                h.relayout()
            },
            "turned" to {
                try {
                    RuntimeEnvironment.setQualifiers("+land")
                    h.service.onConfigurationChanged(Configuration(h.service.resources.configuration))
                    h.idle()
                } finally {
                    RuntimeEnvironment.setQualifiers("+port")
                    h.service.onConfigurationChanged(Configuration(h.service.resources.configuration))
                    h.idle()
                    h.relayout()
                }
            },
            "restarted" to { h.restart() },
        )
        // each way from an empty book: a row failing leaves none to the next
        assertRows(ways, { it.first }) { (_, way) ->
            internal.rememberedAddresses.setValue("")
            internal.addressCounts.setValue("")
            next(email)
            typed(half)
            way()
            assertEquals("", internal.rememberedAddresses.getValue() + internal.addressCounts.getValue())
            typed("zhang@qq.com")
            way()
            assertEquals("", internal.rememberedAddresses.getValue())
            // left for another field: the address as finished, once
            next()
            assertEquals(listOf("zhang@qq.com"), AddressStore.book().addresses.map { it.address })
            assertEquals(1, AddressStore.book().count("@qq.com"))
        }
    }

    @Test
    @Uat("KB-53")
    fun anAddressTheAppFilledInIsNotLearnedWhateverIsSelected() {
        // the app's own: the field starts with it, all of it selected, as a login form's may
        h.start(email, fieldId = ++fieldId) {
            it.setText("me@qq.com")
            it.selectAll()
        }
        assertEquals(emptyList<String>(), chips())
        // the cursor moved, nothing typed: still as it started
        h.field.setSelection(2)
        h.idle()
        h.field.setSelection(h.field.text.length)
        h.idle()
        hideAndShow()
        next()
        assertEquals("", internal.rememberedAddresses.getValue() + internal.addressCounts.getValue())
    }

    @Test
    fun aSelectionTheAppGaveNoPositionOfIsStillASelection() {
        // a WebView's -1, -1: the text selected read, nothing offered that a tap would put over it
        h.start(email, fieldId = ++fieldId) { it.setText("zhang") }
        h.field.selectAll()
        h.service.onUpdateSelection(0, 0, -1, -1, -1, -1)
        h.idle()
        assertEquals(emptyList<String>(), chips())
    }

    @Test
    @Uat("KB-54")
    fun aSuffixHalfTypedIsFinished() {
        next(url)
        typed("baidu.c")
        assertEquals(listOf(".com", ".cn", ".com.cn"), chips())
        tap(".com")
        assertEquals("baidu.com|", field())
        assertEquals(emptyList<String>(), chips())
        // a suffix already there, another half typed: only the longer one it starts
        typed("sina.com.c")
        assertEquals(listOf(".com.cn"), chips())
        tap(".com.cn")
        assertEquals("sina.com.cn|", field())
        // a half typed that no suffix after .cn starts: nothing, no whole suffix either
        typed("sina.cn.c")
        assertEquals(emptyList<String>(), chips())
    }

    @Test
    @Uat("KB-58")
    fun onlyTheAddressAddedToWhatTheFieldStartedWithIsLearned() {
        // a reply to all: the app's address in the field, the user's added after it
        h.start(email, fieldId = ++fieldId) { it.setText("me@qq.com") }
        typed("me@qq.com, li@163.com")
        next()
        assertEquals(listOf("li@163.com"), AddressStore.book().addresses.map { it.address })
        assertEquals(1, AddressStore.book().count("@163.com"))
        assertEquals(0, AddressStore.book().count("@qq.com"))
        next(email)
        assertEquals("li@163.com", chips().first())
    }

    @Test
    @Uat("KB-59")
    fun aUrlFieldsStartingHostIsNotCountedAddedOnesAre() {
        // a page's own URL, only added to: its .org is the app's, not the user's, so .com stays first
        h.start(url, fieldId = ++fieldId) { it.setText("https://www.python.org/doc?a=1") }
        typed("https://www.python.org/doc?a=1&b=2")
        next(url)
        assertEquals(0, AddressStore.book().count(".org"))
        typed("github")
        assertEquals(".com", chips().first())
        // a host the user typed counts
        typed("x.org")
        next(url)
        assertEquals(1, AddressStore.book().count(".org"))
        typed("github")
        assertEquals(".org", chips().first())
    }

    @Test
    @Uat("KB-55")
    fun beforeTheFirstUnlockOrInALongTextNothingIsLearned() {
        // before the first unlock: the settings are the device-protected copy's
        directBoot(true)
        prefs.later { directBoot(false) }
        next(email)
        typed("zhang@163.com")
        next()
        assertEquals("", internal.rememberedAddresses.getValue() + internal.addressCounts.getValue())
        directBoot(false)
        // more than the bar reads, an address at its end: offered nothing, learned nothing
        next(email)
        typed("x".repeat(300) + "zhang")
        assertEquals(emptyList<String>(), chips())
        typed("x".repeat(300) + " zhang@163.com")
        next()
        assertEquals("", internal.rememberedAddresses.getValue() + internal.addressCounts.getValue())
    }

    @Test
    @Uat("KB-56")
    fun fieldsThatShareANameAreToldApartByTheirText() {
        // a Compose screen's or a web page's fields: one view, one name. 邮箱 typed, then 备用邮箱 the app filled in
        val shared = ++fieldId
        h.start(email, fieldId = shared)
        typed("me@qq.com")
        h.start(email, fieldId = shared) { it.setText("old@x.com") }
        h.field.setSelection(h.field.text.length)
        h.idle()
        next()
        assertEquals(listOf("me@qq.com"), AddressStore.book().addresses.map { it.address })
        // a web mail's 收件人 typed, then its 抄送 left empty, then 主题: the address typed learned
        val mail = ++fieldId
        h.start(email, fieldId = mail)
        typed("a@163.com")
        h.start(email, fieldId = mail)
        assertEquals(listOf("me@qq.com", "a@163.com").sorted(), AddressStore.book().addresses.map { it.address }.sorted())
        next()
        assertEquals(2, AddressStore.book().addresses.size)
    }

    @Test
    @Uat("KB-57")
    fun theLastFieldIsLearnedAsTheKeyboardGoesNotWhatTheSettingsForgot() {
        next(email)
        typed("zhang@qq.com")
        // 全部清空 in the settings, then back in the same field: what it held then not learned again
        hideAndShow()
        AddressStore.forget { it.forgetAddresses() }
        h.restart()
        next()
        assertEquals("", internal.rememberedAddresses.getValue())
        // another keyboard picked: the service goes, the field it was in learned
        next(email)
        typed("li@163.com")
        h.destroy()
        assertEquals(listOf("li@163.com"), AddressStore.book().addresses.map { it.address })
    }
}
