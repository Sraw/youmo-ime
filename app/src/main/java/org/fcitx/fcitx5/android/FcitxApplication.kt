/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Environment
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.importedUserDataMarker
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.ui.main.LogActivity
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.Locales
import org.fcitx.fcitx5.android.utils.notificationManager
import org.fcitx.fcitx5.android.utils.setupForest
import org.fcitx.fcitx5.android.utils.startActivity
import org.fcitx.fcitx5.android.utils.userManager
import timber.log.Timber
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class FcitxApplication : Application() {

    val coroutineScope = MainScope() + CoroutineName("FcitxApplication")

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SHUTDOWN) return
            Timber.d("Device shutting down, trying to save fcitx state...")
            val fcitx = HostDaemon.getFirstConnectionOrNull()
                ?: return Timber.d("No active fcitx connection, skipping")
            fcitx.runImmediately { save() }
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_UNLOCKED) return
            if (!isDirectBootMode) return
            Timber.d("Device unlocked, app will exit now and restart to normal mode")
            HostDaemon.getFirstConnectionOrNull()?.also {
                // try to shutdown fcitx gracefully
                HostDaemon.stopFcitx()
            }
            AppUtil.exit()
        }
    }

    private val restartFcitxInstanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RESTART_FCITX_INSTANCE) return
            if (HostDaemon.getFirstConnectionOrNull() != null) {
                Timber.i("Received broadcast '${intent.action}', try to restart fcitx instance ...")
                HostDaemon.restartFcitx()
            } else {
                Timber.i("Received broadcast '${intent.action}', but there's no fcitx instance")
            }
        }
    }

    var isDirectBootMode = false
        private set

    val directBootAwareContext: Context
        get() = if (isDirectBootMode) {
            createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }

    override fun onCreate() {
        super.onCreate()
        // settings screens follow the wallpaper palette on Android 12+; the keyboard has its own themes
        DynamicColors.applyToActivitiesIfAvailable(this)
        if (!userManager.isUserUnlocked) {
            isDirectBootMode = true
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        val ctx = directBootAwareContext

        if (!BuildConfig.DEBUG) {
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                val crashTime = System.currentTimeMillis()
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
                val lastCrashTimePrefKey = "last_crash_time"
                val lastCrashTime = sharedPreferences.getLong(lastCrashTimePrefKey, -1L)
                // make sure it was written to persistent storage
                sharedPreferences.edit(commit = true) {
                    putLong(lastCrashTimePrefKey, crashTime)
                }
                if (crashTime - lastCrashTime <= 10_000L) {
                    // continuous crashes within 10 seconds, maybe in a crash loop. just bail
                    exitProcess(10)
                }
                startActivity<LogActivity> {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(LogActivity.FROM_CRASH, true)
                    // avoid transaction overflow
                    val truncated = e.stackTraceToString().let {
                        if (it.length > MAX_STACKTRACE_SIZE)
                            it.take(MAX_STACKTRACE_SIZE) + "<truncated>"
                        else
                            it
                    }
                    putExtra(LogActivity.CRASH_STACK_TRACE, truncated)
                }
                exitProcess(10)
            }
        }

        instance = this
        // we don't have AppPrefs available yet
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        Timber.setupForest(verbose = sharedPrefs.getBoolean("verbose_log", false))

        Timber.d("isDirectBootMode=$isDirectBootMode")

        AppPrefs.init(sharedPrefs)
        resetHiddenSettings(AppPrefs.getInstance(), isDirectBootMode) { importedUserDataMarker(this) }
        // what 0.4 kept and nothing reads now: a 0.4 backup restored brings it back, gone at the restart after
        if (!isDirectBootMode) AppPrefs.getInstance().dropRetired()
        // record last pid for crash logs
        AppPrefs.getInstance().internal.pid.apply {
            val currentPid = Process.myPid()
            lastPid = getValue()
            Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
            setValue(currentPid)
        }
        ClipboardManager.init(ctx)
        ThemeManager.init(resources.configuration)
        Locales.onLocaleChange(resources.configuration)
        registerReceiver(shutdownReceiver, IntentFilter(Intent.ACTION_SHUTDOWN))
        // the app posts no notifications (and asks for no permission to): an older version's channels go
        deleteNotificationChannels(notificationManager, sharedPrefs)
        thread(name = "native-data-cleanup", priority = Thread.MIN_PRIORITY) {
            deleteNativeData(createDeviceProtectedStorageContext().dataDir)
        }
        // the resource packs read (an import killed midway cleaned up, the signatures checked) before
        // the keyboard first asks: it would wait on this on the main thread
        thread(name = "packs-load") {
            runCatching { PackStore.warmUp() }.onFailure { Timber.e(it, "resource packs not read") }
        }
        if (!isDirectBootMode) {
            AppPrefs.getInstance().syncToDeviceEncryptedStorage()
            ThemeManager.syncToDeviceEncryptedStorage()
            // fcitx's data settled (an older build's moved in) off the main thread before an editor or the
            // host asks: they would wait on its copy, an editor on the main thread. Whoever asks first moves
            // it, the others wait (HostConfigDir), so this changes when, never where. Only with the external
            // storage there: away, a settle copies nothing (it waits for it, or makes an empty home), so
            // there is nothing to get ahead of; and a JVM test, whose storage reads as away till the test
            // sets it up (phoneStorage), has no settle racing that
            val external = getExternalFilesDir(null)
            if (external != null && Environment.getExternalStorageState(external) == Environment.MEDIA_MOUNTED) {
                thread(name = "user-data-settle") {
                    // one that fails is asked again, and told, by whoever needs it next: no crash report from here
                    runCatching { HostConfigDir.inUse(ConfigHome.DATA) }.onFailure { Timber.w(it, "user data not settled early") }
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            restartFcitxInstanceReceiver,
            IntentFilter(ACTION_RESTART_FCITX_INSTANCE),
            PERMISSION_TEST_INPUT_METHOD,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeManager.onSystemPlatteChange(newConfig)
        Locales.onLocaleChange(newConfig)
    }

    companion object {
        private var lastPid: Int? = null
        private var instance: FcitxApplication? = null
        fun getInstance() =
            instance ?: throw IllegalStateException("FcitxApplication has not been created!")

        fun getLastPid() = lastPid

        /**
         * The settings without a UI back to their defaults: once, and after an import of the user's
         * data, whose [marker] then goes (in [directBoot] these are the device's copy, and the
         * import's marker cannot be read)
         */
        internal fun resetHiddenSettings(prefs: AppPrefs, directBoot: Boolean, marker: () -> File) {
            val imported = if (directBoot) null else marker().takeIf { it.exists() }
            prefs.resetHiddenSettings(again = imported != null)
            imported?.delete()
        }

        /**
         * The notification channels an older version made deleted, once, [prefs] noting it: after
         * that the list is empty, and asking the system for it is a call on the main thread at every
         * start, the keyboard's too.
         */
        internal fun deleteNotificationChannels(manager: NotificationManager, prefs: SharedPreferences) {
            if (prefs.getBoolean(NOTIFICATION_CHANNELS_DELETED, false)) return
            manager.notificationChannels.forEach { manager.deleteNotificationChannel(it.id) }
            prefs.edit { putBoolean(NOTIFICATION_CHANNELS_DELETED, true) }
        }

        private const val NOTIFICATION_CHANNELS_DELETED = "notification_channels_deleted"

        /**
         * What 0.3.x's native host copied out of the APK into [dir] (device-protected storage):
         * fcitx's data under `usr/` (7.4 MB from 0.3.2), `learn/`, `README.md`, each listed in `descriptor.json`.
         * Nothing reads them since the Kotlin host, which reads the APK's assets. Only the listed paths
         * go, files first, then their directories if empty; the descriptor last, once each was tried (one
         * that will not go is logged and left), so a start that stops halfway does the rest next time, and
         * its absence makes every later start a no-op.
         */
        internal fun deleteNativeData(dir: File) {
            val descriptor = dir.resolve(NATIVE_DESCRIPTOR)
            if (!descriptor.exists()) return
            val listed = runCatching {
                Json.parseToJsonElement(descriptor.readText()).jsonObject.getValue("files").jsonObject
                    .mapValues { it.value.jsonPrimitive.content }
            }.getOrElse {
                Timber.w(it, "native data descriptor unreadable, left alone")
                return
            }
            // a blank hash is a directory
            val (dirs, files) = listed.entries.partition { it.value.isBlank() }
            files.forEach { dir.resolve(it.key).delete() }
            dirs.map { it.key }.sortedByDescending { it.count { c -> c == '/' } }
                .forEach { dir.resolve(it).delete() } // only when empty
            // tried once: a file that will not go is logged and left, not tried again at every start
            files.filter { dir.resolve(it.key).exists() }.forEach { Timber.w("native data left: %s", it.key) }
            descriptor.delete()
        }

        private const val NATIVE_DESCRIPTOR = "descriptor.json"

        private const val MAX_STACKTRACE_SIZE = 128000

        const val ACTION_RESTART_FCITX_INSTANCE =
            "${BuildConfig.APPLICATION_ID}.action.RESTART_FCITX_INSTANCE"

        /**
         * This permission is requested by com.android.shell, makes it possible to restart
         * fcitx instance from `adb shell am` command:
         * ```sh
         * adb shell am broadcast -a io.github.sraw.youmo.action.RESTART_FCITX_INSTANCE
         * ```
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/packages/Shell/AndroidManifest.xml#67
         *
         * other candidate: android.permission.TEST_INPUT_METHOD requires Android 14
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/packages/Shell/AndroidManifest.xml#628
         */
        const val PERMISSION_TEST_INPUT_METHOD = "android.permission.READ_INPUT_STATE"
    }
}