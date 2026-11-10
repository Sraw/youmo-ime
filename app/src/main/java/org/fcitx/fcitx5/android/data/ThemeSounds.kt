/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.os.Trace
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.scene.theme.SoundFile
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import timber.log.Timber
import java.io.File

/**
 * The selected theme's key sounds (060-theme-sounds-icons §1.4), played by a [SoundPool] on its own
 * thread ([handler]'s): Android 10's `SoundPool.play` runs on its caller and, a channel taking another
 * sample, makes an AudioTrack there (a binder call), where the system's click (`playSoundEffect`) is a
 * oneway call. The pool is made, loaded, called back (its listener runs on the looper it was made on)
 * and played on that thread alone; the main thread reads [loaded] and posts.
 *
 * A pool is made anew for every change of what is wanted ([use]: another theme, the same one imported
 * again, the settings asking for none); a load finished for a pool no longer the current one is dropped.
 * Each file is held to [SoundFile] again before it is loaded: a pack restored from a backup was never
 * imported. One that fails, or has not loaded yet, is not in [loaded]: its class plays the system's click.
 */
class ThemeSounds(private val handler: Handler) {

    /** What [use] was last asked for: the theme's identity, its files' paths by class (null: none wanted). */
    private var wanted: Pair<Any, Map<SoundKey, String>>? = null

    /** The classes the theme wanted gives (main thread). */
    var given: Set<SoundKey> = emptySet()
        private set

    // the asks of [use] and [release], one more each (main thread): a pool built for an earlier ask is not
    // the one wanted now
    @Volatile
    private var asked = 0

    /** The samples ready to play in the pool built for the ask [asked]: written whole on the sound thread alone. */
    private class Ready(val asked: Int, val ids: Map<SoundKey, Int>)

    // read once by each reader: what it reads of the ask and its samples is one pool's
    @Volatile
    private var ready = Ready(0, emptyMap())

    // the sound thread's alone
    private var pool: SoundPool? = null

    /** The pool now (the tests'). */
    @VisibleForTesting
    internal val currentPool: SoundPool? get() = pool
    private val pending = HashMap<Int, SoundKey>()

    /** The classes ready to play now. */
    val loaded: Set<SoundKey> get() = current()?.ids?.keys.orEmpty()

    // the samples of the pool built for the ask now, null where its rebuild has not run yet
    private fun current(): Ready? = ready.takeIf { it.asked == asked }

    /**
     * Wants the sounds [files] of the theme [identity] (a theme's file and its stamp: one imported again is
     * another), null none; the same as before changes nothing.
     */
    fun use(identity: Any?, files: () -> Map<SoundKey, String>) {
        val now = wanted
        if (identity == null) {
            if (now != null) drop()
            return
        }
        if (now != null && now.first == identity) return
        val sounds = files()
        wanted = identity to sounds
        given = sounds.keys
        ask(sounds)
    }

    /** Plays [key]'s sample at [volume] on the sound thread; false where it is not loaded (the caller plays the system's). */
    fun play(key: SoundKey, volume: Float): Boolean {
        val of = current() ?: return false
        val id = of.ids[key] ?: return false
        handler.post {
            // a pool made or asked for since: its samples are others
            if (ready.asked != of.asked || asked != of.asked) return@post
            Trace.beginSection("SoundPool.play")
            try {
                pool?.play(id, volume, volume, 1, 0, 1f)
            } finally {
                Trace.endSection()
            }
        }
        return true
    }

    /** No pool held (the service destroyed): the next [use] makes one again. */
    fun release() = drop()

    private fun drop() {
        wanted = null
        given = emptySet()
        ask(emptyMap())
    }

    // a pool anew for [sounds]: until it is built nothing is [loaded]
    private fun ask(sounds: Map<SoundKey, String>) {
        val n = ++asked
        handler.post { rebuild(n, sounds) }
    }

    private fun rebuild(n: Int, sounds: Map<SoundKey, String>) {
        pool?.release()
        pool = null
        pending.clear()
        ready = Ready(n, emptyMap())
        if (sounds.isEmpty()) return
        val p = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        pool = p
        // run on this thread: the pool was made on its looper
        p.setOnLoadCompleteListener { from, id, status ->
            if (from !== pool) return@setOnLoadCompleteListener
            val key = pending.remove(id) ?: return@setOnLoadCompleteListener
            if (BuildConfig.DEBUG) Timber.d("theme sound ${key.word} ${sounds[key]?.substringAfterLast('.')} status=$status")
            if (status == 0) ready = Ready(n, ready.ids + (key to id)) else Timber.w("Theme sound ${sounds[key]} did not load: status $status")
        }
        for ((key, path) in sounds) {
            if (!checked(path)) continue
            pending[p.load(path, 1)] = key
        }
    }

    // the file there, no longer than a sound may be (compared before it is read), and a sound
    private fun checked(path: String): Boolean {
        val f = File(path)
        val problem = when {
            !f.isFile -> "missing"
            f.length() > SoundFile.MAX_BYTES -> "over ${SoundFile.MAX_BYTES} bytes"
            // gone or replaced since it was looked at (the theme deleted or imported again): unreadable, not a crash
            else -> runCatching { f.readBytes() }.fold(
                { SoundFile.check(f.name, it).firstOrNull()?.message },
                { "unreadable: ${it.message}" },
            )
        }
        if (problem != null) Timber.w("Theme sound $path not loaded: $problem")
        return problem == null
    }

    companion object {
        private const val MAX_STREAMS = 4

        /** The app's: its thread lives as long as the process. */
        val shared: ThemeSounds by lazy {
            val thread = HandlerThread("theme-sounds").apply { start() }
            ThemeSounds(Handler(thread.looper))
        }

        /**
         * [shared] made to want the selected theme's sounds where 按键音 plays and 按键音音色 follows the
         * theme ([wanted]), none otherwise. Cheap where nothing changed: called with each press.
         */
        fun sync(wanted: Boolean) {
            val pack = (ThemeManager.activeTheme as? Theme.Pack)?.takeIf { wanted }
            shared.use(pack?.let { Triple(it.file, it.dir, it.stamp) }) {
                pack?.drawn?.sounds.orEmpty()
            }
        }
    }
}
