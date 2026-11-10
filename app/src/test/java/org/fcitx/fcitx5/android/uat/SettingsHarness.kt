/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.viewpager2.widget.ViewPager2
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.comparesScreenshots
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.FakeHostConnection
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The settings app on a [FakeHostApi], as [SettingsUatTest] has it: pages opened by their route,
 * read through their preferences. Engine config a page builds from is put in [api] first
 * (`setImOptions`, `setPunctuationMap`); [intent], what it is opened with. [close] at the end: the daemon seam is global.
 */
class SettingsHarness(
    val api: FakeHostApi = FakeHostApi(),
    private val intent: Intent? = null,
    // the pages' engine: [api], or a real one over it (EngineBackedHostApi)
    private val host: HostApi = api,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // put back at close: a keyboard harness still open beside this one keeps its own (TB-18)
    private val before = HostDaemon.testConnection

    private val lazyController = lazy {
        HostDaemon.testConnection = { FakeHostConnection(scope, api, host) }
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().also { idle() }
    }
    private val controller by lazyController

    val activity: MainActivity get() = controller.get()

    /** The activity made anew, its pages' saved state given back, as a rotation or a theme change does. */
    fun recreate() {
        controller.recreate()
        idle()
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** [intent] sent to the activity up (onNewIntent), as the keyboard's 存为 does with settings open. */
    fun newIntent(intent: Intent) {
        controller.newIntent(intent)
        idle()
    }

    fun s(id: Int, vararg args: Any): String = activity.getString(id, *args)

    private val nav get() = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment

    fun open(route: SettingsRoute) {
        nav.navController.navigate(route)
        idle()
    }

    /** The page's tab [at] (外观's 主题 / 大小与样式), its pages made as a swipe to it would. */
    fun tab(at: Int) {
        KeyboardHarness.all(activity.window.decorView).filterIsInstance<ViewPager2>().first().setCurrentItem(at, false)
        idle()
        activity.window.decorView.let {
            it.measure(View.MeasureSpec.makeMeasureSpec(it.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(it.height, View.MeasureSpec.EXACTLY))
            it.layout(0, 0, it.width, it.height)
        }
        idle()
    }

    val page: Fragment get() = nav.childFragmentManager.primaryNavigationFragment!!

    private fun fragments(fm: FragmentManager): List<Fragment> = fm.fragments.flatMap { listOf(it) + fragments(it.childFragmentManager) }

    /** Every preference shown on the page, in the pages under it too (the tabs of 外观). */
    fun preferences(): List<Preference> = (listOf(page) + fragments(page.childFragmentManager))
        .filterIsInstance<PreferenceFragmentCompat>()
        .flatMap { f -> f.preferenceScreen?.let(::preferences).orEmpty() }

    // a group hidden hides what is in it, as the screen does
    private fun preferences(group: PreferenceGroup): List<Preference> =
        (0 until group.preferenceCount).map(group::getPreference).filter { it.isVisible }.flatMap { p ->
            listOf(p) + ((p as? PreferenceGroup)?.let(::preferences) ?: emptyList())
        }

    fun titles() = preferences().map { it.title.toString() }

    fun pref(title: String): Preference = preferences().firstOrNull { it.title.toString() == title }
        ?: error("no $title in ${titles()}")

    fun pref(title: Int) = pref(s(title))

    /** Every text shown in the activity's window, depth first: a text view's, and those of each key cap the engine draws (SceneKeyCell). */
    fun texts(): List<String> = KeyboardHarness.all(activity.window.decorView)
        .filter { it.visibility == View.VISIBLE && it.isShown }
        .flatMap { v ->
            when (v) {
                is TextView -> sequenceOf(v.text.toString())
                is SceneKeyCell -> v.keyNode?.ops(false).orEmpty().filterIsInstance<Op.Text>().map { it.text }.asSequence()
                else -> emptySequence()
            }
        }.toList()

    fun capture(path: String) {
        idle()
        val root = activity.window.decorView
        if (!comparesScreenshots) {
            // drawn all the same (see SCREENSHOT_SDK)
            root.draw(Canvas(Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
            return
        }
        root.captureRoboImage(
            "src/test/screenshots/$path.png",
            RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)),
        )
    }

    override fun close() {
        // the activity destroyed, its views off the window: else its RecyclerViews stay in GapWorker's list, a
        // ThreadLocal of the main thread, which lives the whole test JVM (every class of a fork). Through their
        // window's ViewRootImpl they held each view that ever asked to be drawn on the next frame
        // (InvalidateOnAnimationRunnable, posted once and never run on the paused looper): the theme page's
        // previews, rebuilt on each theme preference ThemeMakerLayoutTest set, 78 000 views a window, three
        // windows a full run's 3 GB out (S4c's gate, the heap dump of 2026-11-07)
        // the fake connection given back even when the activity's teardown throws: else every later test of the fork has it
        try {
            if (lazyController.isInitialized()) {
                controller.close()
                idle()
            }
        } finally {
            HostDaemon.testConnection = before
            scope.cancel()
        }
    }
}
