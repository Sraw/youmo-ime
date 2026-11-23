/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.core.os.ConfigurationCompat
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The service's resources in the language picked in the app, kept till the next pick. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = FcitxApplication::class)
class ServiceLocaleResourcesTest {

    @After
    fun phonesAgain() = AppLanguage.set("")

    private fun languageOf(r: Resources) = ConfigurationCompat.getLocales(r.configuration)[0]!!.toLanguageTag()

    @Test
    fun aPickLandingWhileTheOldOnesAreBuiltIsNotOverwrittenByThem() {
        val app = RuntimeEnvironment.getApplication()
        lateinit var resources: ServiceLocaleResources
        var picksMidway = true
        val service = object : ContextWrapper(app) {
            override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
                // the main thread's pick between the fcitx thread's read of the locales and its keeping what it built
                if (picksMidway) {
                    picksMidway = false
                    AppLanguage.set("en")
                    resources.pickedChanged()
                }
                return super.createConfigurationContext(overrideConfiguration)
            }
        }
        resources = ServiceLocaleResources(service)
        AppLanguage.set("zh-TW")
        resources.pickedChanged()
        assertEquals("zh-TW", languageOf(resources.resources(app.resources)))
        assertEquals("en", languageOf(resources.resources(app.resources)))
    }

    @Test
    fun theResourcesAreBuiltOnceAPick() {
        val app = RuntimeEnvironment.getApplication()
        var built = 0
        val service = object : ContextWrapper(app) {
            override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
                built++
                return super.createConfigurationContext(overrideConfiguration)
            }
        }
        val resources = ServiceLocaleResources(service)
        AppLanguage.set("zh-TW")
        resources.pickedChanged()
        val first = resources.resources(app.resources)
        assertEquals(first, resources.resources(app.resources))
        assertEquals(1, built)
    }
}
