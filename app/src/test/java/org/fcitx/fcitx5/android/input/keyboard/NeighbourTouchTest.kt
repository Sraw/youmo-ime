/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.keyboardOn
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.pinyin.nineSpCode
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.SlipCost
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.LayoutSource
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import org.fcitx.fcitx5.android.until as waitUntil

/**
 * A typing key's tap goes to the engine with where it fell and the keys around it (061-neighbour-correction
 * §2): the letters of the full keyboard, 九键's digit keys with letters; a click no touch made (TalkBack)
 * and the keys around them (九键's 1 and 0) with none.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class NeighbourTouchTest {

    @get:Rule
    val prefs = PrefsRule()

    private var h: KeyboardHarness? = null
    private var host: KotlinHost? = null
    private var home: File? = null

    @After
    fun close() {
        EngineState.shuangpinScheme = ShuangpinScheme.XIAOHE
        h?.close()
        host?.stop()
        home?.deleteRecursively()
    }

    // [k] tapped [dx], [dy] of its size from its middle
    private fun KeyboardHarness.tapOff(k: ShownKey, dx: Float, dy: Float = 0f) {
        val dp = service.resources.displayMetrics.density
        touch(k, at(k, dx = dx * k.width / dp, dy = dy * k.height / dp))
    }

    private fun near(t: Touch) = t.near.map { it.char }.toSet()

    @Test
    fun aLetterTappedGoesWithWhereItFellAndTheLettersAroundIt() {
        val h = KeyboardHarness().also { h = it }.start()
        h.tapOff(h.keyNamed("g"), -0.4f, 0.2f)
        val (key, touch) = h.api.touches.single()
        assertEquals("g", key)
        assertEquals(-0.4f, touch.dx, 0.03f)
        assertEquals(0.2f, touch.dy, 0.03f)
        assertEquals(setOf('f', 'h', 't', 'y', 'v'), near(touch))
        assertTrue(touch.near('f')!!.nx < 0f && touch.near('t')!!.ny < 0f)
        // a click no touch made: no place
        h.tap(h.keyNamed("g"))
        assertEquals(1, h.api.touches.size)
    }

    // a layout of one's own may make a letter wider than its neighbours (061-custom-layout): the keys around it are
    // where that layout drew them, in the wide key's size, and a tap at its edge reads the narrow key beside it no
    // likelier than the key hit (the cost kept at most 0, 061-neighbour-correction §3)
    @Test
    fun aWideLetterOfAnOwnLayoutHasItsNeighboursWhereTheyAreDrawn() {
        val builtIn = SceneLayouts.source
        SceneLayouts.source = { ctx ->
            val own = BuiltInLayouts(ctx)
            object : LayoutSource {
                override val key = "wide-g"

                override fun text(id: String) =
                    own.text(id)?.let { if (id == "qwerty") it.replace("<key id=\"g\" ", "<key id=\"g\" width=\"2\" ") else it }
            }
        }
        try {
            val h = KeyboardHarness().also { h = it }.start()
            val g = h.keyNamed("g")
            assertEquals(2f, g.width.toFloat() / h.keyNamed("h").width, 0.05f)
            h.tapOff(g, 0.45f)
            val touch = h.api.touches.single().second
            // h's middle is 1.5 keys from g's, g two keys wide: 0.75 of g, and half its width
            val right = touch.near('h')!!
            assertEquals(0.75f, right.nx, 0.03f)
            assertEquals(0.5f, right.nw, 0.03f)
            assertEquals(-0.75f, touch.near('f')!!.nx, 0.03f)
            assertTrue(SlipCost().of(touch, right, legal = false) <= 0f)
        } finally {
            SceneLayouts.source = builtIn
        }
    }

    // 微软 双拼 types ing on `;`, the middle row's tenth key: l's neighbour, and a tap on it goes with its place
    @Test
    fun microsoftsSemicolonIsATypingKey() {
        EngineState.shuangpinScheme = ShuangpinScheme.MICROSOFT
        val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)
        val h = KeyboardHarness(im = shuangpin).also { h = it }.start()
        h.idle()
        h.relayout()
        h.tapOff(h.keyNamed("l"), 0.4f)
        assertTrue(';' in near(h.api.touches.single().second))
        h.tapOff(h.keyNamed("semicolon"), -0.4f)
        val (key, touch) = h.api.touches.last()
        assertEquals(2, h.api.touches.size)
        assertTrue(key, ';' in key)
        assertTrue('l' in near(touch))
    }

    @Test
    @Uat("T9-66")
    fun theNineKeysDigitsGoWithTheirPlaceButNotOneNorZero() {
        val h = KeyboardHarness(keyboards = KeyboardHarness.NINE_KEYS).also { h = it }.start()
        h.tapOff(h.keyNamed("5"), 0.3f)
        val touch = h.api.touches.single().second
        assertEquals(setOf('2', '4', '6', '8'), near(touch))
        assertTrue(touch.near('6')!!.nx > 0f)
        h.tapOff(h.keyNamed("1"), 0.3f)
        h.tapOff(h.keyNamed("0"), 0.3f)
        assertEquals(1, h.api.touches.size)
    }

    // English on the nine keys spelling a word: each digit with its place too, as the sym English reads it
    // (061-neighbour-english)
    @Test
    @Uat("EN-31")
    fun englishsNineKeysDigitsGoWithTheirPlace() {
        val h = KeyboardHarness(im = KeyboardHarness.ENGLISH, keyboards = ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS))
            .also { h = it }.start()
        h.tapOff(h.keyNamed("5"), 0.3f)
        val (key, touch) = h.api.touches.single()
        assertTrue(key, key.startsWith("sym="))
        assertEquals(0.3f, touch.dx, 0.03f)
        assertEquals(setOf('2', '4', '6', '8'), near(touch))
    }

    // 061-neighbour-groups: on the grouped keyboards the keys around a tap are the codes the engine reads (its input's
    // Keyboard.keyedChar), whatever a key is sent as: pinyin's 18 keys' were cut to another character before
    @Test
    @Uat("PY-142", "SP-48", "SP-49")
    fun aGroupKeysTapGoesWithTheGroupsAroundItAsTheEngineReadsThem() {
        val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)
        val made = listOf(
            Triple("拼音 18", KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS18)) to NineKeyLetters.G18,
            Triple("双拼 18", shuangpin, ImKeyboards.Chosen(shuangpin = ImKeyboard.KEYS18)) to NineKeyLetters.G18,
            Triple("双拼 14", shuangpin, ImKeyboards.Chosen(shuangpin = ImKeyboard.KEYS14)) to NineKeyLetters.G14,
        )
        for ((board, letters) in made) {
            val (what, im, keyboards) = board
            val h = KeyboardHarness(im = im, keyboards = keyboards).also { h = it }.start()
            h.idle()
            h.relayout()
            h.tapOff(h.keyNamed(if (letters == NineKeyLetters.G18) "r3c4" else "r3c3"), 0.45f)
            val touch = h.api.touches.single().second
            assertTrue("$what ${touch.near}", touch.near(letters.key('m'))!!.nx > 0f)
            assertTrue("$what ${touch.near}", touch.near.all { it.char in letters.codes })
            h.close()
            this.h = null
        }
    }

    // 双拼's nine keys: a digit's tap goes with the digits around it as their NINE_SP codes, as the engine reads them
    @Test
    @Uat("SP-50")
    fun shuangpinsNineKeysDigitsGoWithTheirCodesAround() {
        val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)
        val h = KeyboardHarness(im = shuangpin, keyboards = ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS)).also { h = it }.start()
        h.idle()
        h.relayout()
        h.tapOff(h.keyNamed("5"), 0.3f)
        val touch = h.api.touches.single().second
        assertEquals("2468".map(::nineSpCode).toSet(), near(touch))
        h.tapOff(h.keyNamed("1"), 0.3f)
        assertEquals(1, h.api.touches.size)
    }

    // 大千: a symbol key's tap goes with the symbol keys around it by their codes; a tone key's none, nor is a tone key
    // among the keys around (ㄉ has ㄅ to its left, ˇ to its right no neighbour). 注音九键 reads no slip: no tap goes
    // with a place
    @Test
    @Uat("PY-150")
    fun dachensSymbolKeysGoWithTheSymbolKeysAround() {
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        val nine = KeyboardHarness(keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_NINE)).also { h = it }.start()
        nine.idle()
        nine.relayout()
        nine.tapOff(nine.keyNamed("r2c2"), 0.3f)
        nine.tapOff(nine.keyNamed("r1c1"), 0.3f)
        assertEquals(0, nine.api.touches.size)
        assertTrue(nine.api.calls.toString(), nine.api.calls.count { it.startsWith("sendKey(") } >= 2)
        nine.close()
        h = null
        val dachen = KeyboardHarness(keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_FULL)).also { h = it }.start()
        dachen.idle()
        dachen.relayout()
        dachen.tapOff(dachen.keyNamed("2"), 0.3f)
        val d = dachen.api.touches.single().second
        assertTrue("${d.near}", d.near(ZhuyinKeys.symbolCode('ㄅ'))!!.nx < 0f)
        assertTrue("${d.near}", d.near.all { it.char in ZhuyinKeys.DACHEN.codes })
        dachen.tapOff(dachen.keyNamed("3"), 0.3f)
        assertEquals(1, dachen.api.touches.size)
    }

    // through the host to the engine: the key's payload, the engine's Keyboard reads its touch
    @Test
    @Uat("PY-137")
    fun theHostHandsTheTouchToTheEngineWithTheKey() {
        val dir = File(appContext.filesDir, "neighbour-touch").apply { deleteRecursively() }.also { home = it }
        val engine = ScriptedEngine(dir)
        val fcitx = startedHost(engine).also { host = it }
        val h = keyboardOn(fcitx).also { h = it }.start()
        h.tapOff(h.keyNamed("s"), -0.45f)
        waitUntil("the key in", { "${engine.payloads}" }, step = { h.idle() }) { engine.payloads.isNotEmpty() }
        val key = engine.payloads.single() as Action.Key
        assertEquals('s', key.char)
        assertEquals(-0.45f, key.touch!!.dx, 0.03f)
        assertTrue('a' in near(key.touch!!))
        h.tap(h.keyNamed("s"))
        waitUntil("the click in", { "${engine.payloads}" }, step = { h.idle() }) { engine.payloads.size == 2 }
        assertNull(engine.payloads[1])
    }
}
