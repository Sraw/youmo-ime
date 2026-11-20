/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMaps
import org.fcitx.fcitx5.android.engine.platform.Blobs
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.EnginesHost
import org.fcitx.fcitx5.android.host.EngineHost
import org.fcitx.fcitx5.android.host.PunctuationTable
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.until
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.util.concurrent.Executor

/** A real engine on small data (no user dictionary, warmed up on the caller's thread). */
fun tinyEngineSide(home: File, blobs: Blobs, wrap: (Engines, EngineHost) -> EngineHost = { _, host -> host }) =
    object : KotlinHost.EngineSide {
        val engines = Engines(blobs = blobs, userDir = null, warmUps = Executor(Runnable::run))
        override val host: EngineHost = wrap(engines, EnginesHost(engines))
        override fun data() = HostConfig(File(home, "data"), writable = true)
        override fun config() = HostConfig(File(home, "config"), writable = true)
        override fun start() = Unit
        override fun close() = Unit
    }

/** Started and ready; [firstRun] since AppPrefs outlives the sandbox. [beforeStart] for what must see the first event (eventFlow keeps no replay). */
fun startedHost(side: KotlinHost.EngineSide, firstRun: Boolean = true, beforeStart: (KotlinHost) -> Unit = {}): KotlinHost {
    if (firstRun) AppPrefs.getInstance().internal.firstRun.setValue(true)
    return KotlinHost(appContext, side).apply {
        beforeStart(this)
        start()
        until("the host ready") { isReady }
    }
}

/** The built-in table is an app asset, not on the JVM classpath, and no app path saves one: put where a user's is read, then reloaded. */
suspend fun KotlinHost.putPunctuationTable(home: File, entries: List<PunctuationMapEntry>) {
    File(home, "data/${PunctuationMaps.DIR}/punc.mb.zh_CN").apply { parentFile!!.mkdirs() }
        .writeBytes(PunctuationTable.write(entries.map { PunctuationTable.Entry(it.key, it.mapping, it.altMapping) }))
    reloadConfig()
}

/** Activates onBindInput's context first: the harness starts input without a binding, and focus needs one. Not started. */
fun keyboardOn(host: KotlinHost, api: HostApi = host) = KeyboardHarness(host = api, ready = host).also { h ->
    h.service.postFcitxJob { activate(1, "org.example.app") }
}

// events arrive on the host's thread, timers included, outside a case's own calls: read as a copy taken under the lock
// (iterating a synchronized list while the host added to it threw ConcurrentModificationException)
class HostLog<T> {
    private val items = ArrayList<T>()

    fun add(item: T) {
        synchronized(items) { items += item }
    }

    fun clear() = synchronized(items) { items.clear() }

    fun list(): List<T> = synchronized(items) { items.toList() }
}
