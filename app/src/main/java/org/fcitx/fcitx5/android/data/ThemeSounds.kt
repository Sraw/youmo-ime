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
 * Played by a [SoundPool] on [handler]'s thread: Android 10's `SoundPool.play` runs on its caller and may create an
 * AudioTrack there (a binder call). The pool is made, loaded and played on that thread alone; the main thread reads
 * [loaded] and posts. A new pool per [use] change; a load for an outdated pool is dropped. Each file is rechecked by
 * [SoundFile] (a restored pack was never imported); one not loaded plays the system click.
 */
class ThemeSounds(private val handler: Handler) {

    /** The theme's identity and its files by class; null: none wanted. */
    private var wanted: Pair<Any, Map<SoundKey, String>>? = null

    /** Main thread. */
    var given: Set<SoundKey> = emptySet()
        private set

    // counted per [use]/[release] (main thread): a pool built for an earlier ask is not the one wanted now
    @Volatile
    private var asked = 0

    /** Written whole on the sound thread alone. */
    private class Ready(val asked: Int, val ids: Map<SoundKey, Int>)

    // read once by each reader: what it reads is one pool's
    @Volatile
    private var ready = Ready(0, emptyMap())

    // the sound thread's alone
    private var pool: SoundPool? = null

    @VisibleForTesting
    internal val currentPool: SoundPool? get() = pool
    private val pending = HashMap<Int, SoundKey>()

    val loaded: Set<SoundKey> get() = current()?.ids?.keys.orEmpty()

    // null where the rebuild for the current ask has not run yet
    private fun current(): Ready? = ready.takeIf { it.asked == asked }

    /** [identity]: a theme's file and stamp, so a reimport differs; null none. The same as before changes nothing. */
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

    /** False where not loaded (the caller plays the system's). */
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

    /** The service destroyed: the next [use] makes one again. */
    fun release() = drop()

    private fun drop() {
        wanted = null
        given = emptySet()
        ask(emptyMap())
    }

    // nothing is [loaded] until it is built
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
        // runs on this thread: the pool was made on its looper
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

    // present, within a sound's size limit (checked before reading), and a sound
    private fun checked(path: String): Boolean {
        val f = File(path)
        val problem = when {
            !f.isFile -> "missing"
            f.length() > SoundFile.MAX_BYTES -> "over ${SoundFile.MAX_BYTES} bytes"
            // gone or replaced since (theme deleted or reimported): unreadable, not a crash
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

        /** Its thread lives as long as the process. */
        val shared: ThemeSounds by lazy {
            val thread = HandlerThread("theme-sounds").apply { start() }
            ThemeSounds(Handler(thread.looper))
        }

        /** Wants the selected theme's sounds where 按键音音色 follows the theme; cheap when unchanged, called per press. */
        fun sync(wanted: Boolean) {
            val pack = (ThemeManager.activeTheme as? Theme.Pack)?.takeIf { wanted }
            shared.use(pack?.let { Triple(it.file, it.dir, it.stamp) }) {
                pack?.drawn?.sounds.orEmpty()
            }
        }
    }
}
