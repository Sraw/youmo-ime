/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grouped 双拼 kinds: names the learning record keeps, once in a release never changed. */
class KindsTest {

    private val g18 = NineKeyLetters.G18
    private val nine = NineKeyLetters.NINE_SP

    @Test
    fun groupedShuangpinIsFiledByItsSchemeAndKeys() {
        // contract: spk:<scheme>:<tag>, the scheme by EngineSettings.SCHEMES' name
        assertEquals("spk:Ziranma:q,we,rt,y,u,io,p,a,sd,fg,h,jk,l;,z,xc,v,bn,m", Kinds.shuangpinKeys("Ziranma", g18.tag()))
        for (scheme in EngineSettings.SCHEMES.keys) {
            // keysTag splits on the colon after the scheme: no name may hold one
            assertFalse(scheme, ':' in scheme)
            for (keys in listOf(g18, nine)) {
                val kind = Kinds.shuangpinKeys(scheme, keys.tag())
                assertEquals(keys.tag(), Kinds.keysTag(kind))
                assertTrue(Kinds.isShuangpin(kind))
                assertFalse(kind.startsWith(Kinds.shuangpin("")))
                assertEquals(kind, Kinds.keyboard(kind))
            }
        }
        assertNotEquals(Kinds.shuangpinKeys("Ziranma", g18.tag()), Kinds.shuangpinKeys("Ziranma", nine.tag()))
    }

    @Test
    fun keysTagReadsTheGroupedKindsAlone() {
        assertEquals(NineKeyLetters.G14.tag(), Kinds.keysTag(Kinds.keys(NineKeyLetters.G14.tag())))
        assertNull(Kinds.keysTag(Kinds.shuangpin("Ziranma")))
        assertNull(Kinds.keysTag(Kinds.nineKeys(NineKeyLetters.STANDARD.tag())))
        assertNull(Kinds.keysTag(Kinds.FULL))
        assertNull(Kinds.keysTag("spk:Ziranma"))
    }

    @Test
    fun isShuangpinOnEveryKeyboard() {
        assertTrue(Kinds.isShuangpin(Kinds.shuangpin("MS")))
        assertTrue(Kinds.isShuangpin(Kinds.shuangpinKeys("MS", nine.tag())))
        for (kind in listOf(Kinds.FULL, Kinds.keys(NineKeyLetters.G14.tag()), Kinds.nineKeys(NineKeyLetters.STANDARD.tag()), Kinds.table("engine-wubi"))) {
            assertFalse(kind, Kinds.isShuangpin(kind))
        }
    }

    @Test
    fun theSchemeIsReadFromEveryShuangpinKindAlone() {
        assertRows(
            listOf(
                Triple("18 keys: the scheme before the keys' tag", Kinds.shuangpinKeys("Ziranma", g18.tag()), "Ziranma"),
                Triple("full 双拼: the scheme is all after sp:", Kinds.shuangpin("Xiaohe"), "Xiaohe"),
                Triple("the 14 keys: pinyin, no scheme", Kinds.keys(NineKeyLetters.G14.tag()), null),
                Triple("full pinyin: no scheme", Kinds.FULL, null),
            ),
            { it.first },
        ) { (_, kind, scheme) -> assertEquals(scheme, Kinds.shuangpinScheme(kind)) }
    }

    @Test
    fun aBarTakeOfGroupedShuangpinIsKeptPerScheme() {
        // the same keys read other syllables in another scheme: one scheme's habits never rank another's
        val ziranma = Kinds.takeLayout(Kinds.shuangpinKeys("Ziranma", g18.tag()), g18.tag())
        val xiaohe = Kinds.takeLayout(Kinds.shuangpinKeys("Xiaohe", g18.tag()), g18.tag())
        assertEquals("Ziranma:" + g18.tag(), ziranma)
        assertNotEquals(ziranma, xiaohe)
        // the nine keys' and the 14 keys' as before: their tag
        val standard = NineKeyLetters.STANDARD.tag()
        assertEquals(standard, Kinds.takeLayout(Kinds.nineKeys(standard), standard))
        assertEquals(NineKeyLetters.G14.tag(), Kinds.takeLayout(Kinds.keys(NineKeyLetters.G14.tag()), NineKeyLetters.G14.tag()))
    }
}
