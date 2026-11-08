package org.fcitx.fcitx5.android.uat

import android.app.Activity
import android.util.ArrayMap
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A closed harness leaves no activity alive. Both OOMs of a full run (3 GB) were a harness's
 * Android objects kept past its close: the input method's window in ShadowDialog's list (S4a), then
 * (S4c's gate) a [SettingsHarness]'s MainActivity never destroyed, its RecyclerViews in GapWorker's
 * list on the main thread (lives the whole test JVM), through their window holding every view the
 * theme page's previews ever made; and each [KeyboardHarness]'s field activity, in ActivityThread's
 * list till the test ends, with its themes (a quarter MB each in Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class HarnessLeakTest {

    // ActivityThread's activities not destroyed: what holds an activity, its window and themes till the test ends
    private fun liveActivities(): List<Activity> {
        val thread = RuntimeEnvironment.getActivityThread()
        val f = thread.javaClass.getDeclaredField("mActivities").apply { isAccessible = true }
        val records = (f.get(thread) as ArrayMap<*, *>).values
        return records.mapNotNull { r ->
            r!!.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(r) as Activity?
        }.filterNot { it.isDestroyed }
    }

    @Test
    fun aClosedKeyboardHarnessLeavesNoActivity() {
        val before = liveActivities().size
        repeat(3) { KeyboardHarness().use { it.start() } }
        assertEquals(before, liveActivities().size)
    }

    @Test
    fun aClosedSettingsHarnessDestroysItsActivity() {
        val st = SettingsHarness()
        val activity = st.activity
        val decor = activity.window.decorView
        assertTrue(decor.isAttachedToWindow)
        st.close()
        assertTrue(activity.isDestroyed)
        // off its window: a RecyclerView detached leaves GapWorker's list
        assertFalse(decor.isAttachedToWindow)
        assertEquals(emptyList<Activity>(), liveActivities())
    }
}
