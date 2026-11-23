/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Build
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.ThisDevice
import org.fcitx.fcitx5.android.device.CoreFacts
import org.fcitx.fcitx5.android.device.CpuFacts
import org.fcitx.fcitx5.android.device.DeviceProfile
import org.fcitx.fcitx5.android.device.DeviceProfiles
import org.fcitx.fcitx5.android.device.Midr
import org.fcitx.fcitx5.android.device.ProfileStore
import org.fcitx.fcitx5.android.host.ModelChoice
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN")
class DeviceSummaryTest {

    // the 6T: Kryo 385 Silver and Gold, the Gold the faster
    private val sixT = CpuFacts(
        arm64 = true, hwcap = 0xff, hwcap2 = 0,
        cores = (0..7).map {
            CoreFacts(it, if (it < 4) Midr.of(0x51, 7, 0x803, 0xc) else Midr.of(0x51, 6, 0x802, 0xd), null, if (it < 4) 1766400L else 2803200L)
        },
    )

    private val context get() = RuntimeEnvironment.getApplication()

    @After
    fun forget() {
        ThisDevice.profiles = DeviceProfiles(Store(), "", { ThisDevice.TEST_FACTS }, null)
    }

    private class Store : ProfileStore {
        override fun read() = emptyMap<String, String>()
        override fun write(values: Map<String, String?>) = true
    }

    @Test
    @Uat("ST-199")
    fun theAboutPageSaysTheLevelTheCoresAndTheKernel() {
        assertEquals("中档 · 8 核（4 大）· 无 dotprod · 内核 MLAL", deviceSummary(context, DeviceProfile.of(sixT)))
        val offline = sixT.copy(cores = sixT.cores.map { if (it.id == 7) it.copy(midr = null) else it })
        assertEquals("中档 · 8 核（4 大）· 无 dotprod · 内核 MLAL · 检测时 1 核离线", deviceSummary(context, DeviceProfile.of(offline)))
        val unread = DeviceProfile.of(CpuFacts(true, 0, 0, emptyList()))
        assertEquals("中档 · 无 dotprod · 内核 MLAL · 读不到处理器信息", deviceSummary(context, unread))
        // the kernel that runs, not the profile's, where the library did not load
        assertEquals("中档 · 8 核（4 大）· 无 dotprod · 内核 JVM", deviceSummary(context, DeviceProfile.of(sixT), "JVM"))
        // possible read, no MIDR anywhere: unread, as the settings say, never all eight offline
        val noMidr = sixT.copy(cores = sixT.cores.map { it.copy(midr = null) })
        assertEquals("中档 · 8 核（4 大）· 无 dotprod · 内核 MLAL · 读不到处理器信息", deviceSummary(context, DeviceProfile.of(noMidr)))
        ThisDevice.profiles = DeviceProfiles(Store(), "", { noMidr }, null)
        assertEquals("自动：根据 CPU 性能推荐（本机：中）", InputMethodSettings.modelLevelLabel(context, ModelChoice.AUTO.key))
    }

    @Test
    @Config(qualifiers = "en")
    fun englishCountsOneCoreAsOne() {
        val one = DeviceProfile.of(CpuFacts(true, 0, 0, listOf(sixT.cores[0])))
        assertEquals("Level low · 1 core · no dotprod · kernel MLAL", deviceSummary(context, one))
        val offline = DeviceProfile.of(sixT.copy(cores = sixT.cores.map { if (it.id == 7) it.copy(midr = null) else it }))
        assertEquals("Level medium · 8 cores (4 big) · no dotprod · kernel MLAL · 1 core offline when checked", deviceSummary(context, offline))
    }

    // every JVM test gets the same profile whatever the build machine's /sys and /proc say
    @Test
    fun robolectricIsKnownByItsFingerprint() {
        // contract: Robolectric's fingerprint, the literal ThisDevice tells it by
        assertEquals("robolectric", Build.FINGERPRINT)
        ThisDevice.forget()
        assertEquals(DeviceProfile.of(ThisDevice.TEST_FACTS), ThisDevice.profile)
    }

    @Test
    fun theLevelSaysWhatAutoIsHere() {
        ThisDevice.profiles = DeviceProfiles(Store(), "", { sixT }, null)
        assertEquals("自动：根据 CPU 性能推荐（本机：中）", InputMethodSettings.modelLevelLabel(context, ModelChoice.AUTO.key))
        assertEquals("高档：拿不准时用大模型复核，最准", InputMethodSettings.modelLevelLabel(context, ModelChoice.HIGH.key))
    }
}
