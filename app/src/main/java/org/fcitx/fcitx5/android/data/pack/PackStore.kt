/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pack

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.pack.ImportResult
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.pack.PackManifest
import org.fcitx.fcitx5.android.pack.PackRequirement
import org.fcitx.fcitx5.android.pack.PackShelf
import org.fcitx.fcitx5.android.pack.PackStatus
import org.fcitx.fcitx5.android.pack.Refusal
import org.fcitx.fcitx5.android.pack.VerifyDue
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.causes
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

/** In device-protected `files/packs` (read before first unlock, kept across upgrades). */
object PackStore {

    class State(val statuses: Map<PackKind, PackStatus>, val generation: Int) {
        fun status(kind: PackKind): PackStatus = statuses.getValue(kind)

        fun ready(kind: PackKind) = status(kind) is PackStatus.Ready
    }

    private val root: File
        get() = File(appContext.createDeviceProtectedStorageContext().filesDir, "packs")

    // loaded when first asked (FcitxApplication asks off the main thread at start)
    @Volatile
    private var opened: PackShelf? = null

    private val shelf: PackShelf
        get() = opened ?: synchronized(this) {
            opened ?: PackShelf(root, trustedKeys(), requirements()).also {
                // leftovers deleted afterwards on their own thread, so a main-thread wait is the load alone
                it.load(tidy = false)
                Timber.i("packs: %s", PackKind.entries.joinToString { k -> "${k.id} ${describe(it.status(k))}" })
                opened = it
                // not after an import: it would delete the generation the old host may read till [changed] restarts it
                thread(name = "packs-tidy") { tidyAtStart(it) }
            }
        }

    // an import begun since the start: the start's tidy waits for the next start
    @Volatile
    private var imported = false

    @VisibleForTesting
    internal fun tidyAtStart(shelf: PackShelf, begun: () -> Boolean = { imported }) = synchronized(shelf) { if (!begun()) shelf.tidy() }

    private val mutableState by lazy { MutableStateFlow(State(snapshot(), 0)) }

    val state: StateFlow<State>
        get() = mutableState.asStateFlow()

    private val importing = Mutex()

    fun status(kind: PackKind): PackStatus = shelf.status(kind)

    fun ready(kind: PackKind) = status(kind) is PackStatus.Ready

    fun dir(kind: PackKind): File? = shelf.dir(kind)

    fun warmUp() {
        mutableState.value
    }

    @VisibleForTesting
    fun useShelf(shelf: PackShelf) {
        opened = shelf
        mutableState.value = State(snapshot(), mutableState.value.generation + 1)
    }

    /** Off in JVM tests: a thread reading packs 30 s after a test's start would land in another. */
    @VisibleForTesting
    var verifiesInBackground = System.getProperty("youmo.packs.verify") != "off"

    /** The 30-day read (hundreds of MB) waits for charging; the post-import one does not. */
    fun verifyInBackground() {
        if (!verifiesInBackground) return
        thread(name = "packs-verify", priority = Thread.MIN_PRIORITY, isDaemon = true) {
            Thread.sleep(VERIFY_AFTER_MS)
            while (true) {
                val waiting = runCatching { verifyDue(::charging) }
                    .onFailure { Timber.e(it, "resource packs not verified") }.getOrDefault(false)
                if (!waiting) break
                Thread.sleep(CHARGING_POLL_MS)
            }
        }
    }

    /** True if one is left waiting for the charger. */
    @VisibleForTesting
    fun verifyDue(charging: () -> Boolean): Boolean {
        var waiting = false
        val damaged = PackKind.entries.filter { kind ->
            when (shelf.due(kind)) {
                VerifyDue.NONE -> false
                VerifyDue.UNVERIFIED -> shelf.verify(kind)
                VerifyDue.STALE -> if (charging()) shelf.verify(kind) else false.also { waiting = true }
            }
        }
        damaged(damaged)
        return waiting
    }

    @VisibleForTesting
    fun verifyNow(): Boolean {
        return damaged(PackKind.entries.filter { shelf.verify(it) })
    }

    private fun damaged(kinds: List<PackKind>): Boolean {
        if (kinds.isEmpty()) return false
        Timber.w("resource packs damaged: %s", kinds.joinToString { it.id })
        changed(kinds)
        return true
    }

    // per process: a non-pack engine failure (a bug) does not cost a read of hundreds of MB each time
    private val readAfterFailure = HashSet<File>()

    /**
     * A pack damaged since its last read becomes [PackStatus.Damaged] (the gate) rather than an engine failing at each
     * start. Limited so a crash loop does not read hundreds of MB each time; restarted off the host's thread (it waits).
     */
    fun verifyAfterFailure(kind: PackKind, e: Throwable): Boolean {
        if (e.causes().any { it is PackUnavailableException }) return false
        val gen = shelf.dir(kind) ?: return false
        val verifiedAt = shelf.verifiedAt(kind)
        val now = System.currentTimeMillis()
        if (verifiedAt != null && (now - verifiedAt in 0..RECENTLY_MS || verifiedAt in processStartedAt()..now)) {
            Timber.i("resource pack %s read through lately: not again after the host failed", kind.id)
            return false
        }
        if (!synchronized(readAfterFailure) { readAfterFailure.add(gen) }) return false
        val damaged = try {
            shelf.verify(kind)
        } catch (@Suppress("TooGenericExceptionCaught") t: RuntimeException) {
            Timber.e(t, "resource pack %s not verified", kind.id)
            false
        }
        if (!damaged) return false
        Timber.w("resource pack %s damaged, found after the host failed on it", kind.id)
        thread(name = "packs-restart", isDaemon = true) {
            try {
                restartHost()
            } catch (@Suppress("TooGenericExceptionCaught") t: RuntimeException) {
                Timber.e(t, "host not restarted after a pack found damaged")
            }
            // counted after the restart, as changed() does: the field refocuses on the new host
            mutableState.update { State(snapshot(), it.generation + 1) }
        }
        return true
    }

    @VisibleForTesting
    var processStartedAt: () -> Long = {
        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
    }

    private fun charging(): Boolean =
        appContext.getSystemService(Context.BATTERY_SERVICE)?.let { (it as BatteryManager).isCharging } == true

    /** [replaceNewer] is asked before copying (hundreds of MB by a wrong tap). A stream that opens once is refused with a pack there. */
    suspend fun import(
        resolver: ContentResolver,
        uri: Uri,
        progress: (Long, Long) -> Unit = { _, _ -> },
        replaceNewer: suspend (PackManifest, PackManifest) -> Boolean = { _, _ -> true },
    ): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                // the head only, read again by the import; with nothing installed the file is opened once
                val manifest = if (PackKind.entries.none { shelf.installed(it) != null }) {
                    null
                } else {
                    resolver.openInputStream(uri)?.use { shelf.manifestOf(it.buffered(BUFFER)) }
                }
                // one this build cannot use is not offered: the import below refuses it at once
                val newer = manifest?.takeIf(shelf::usable)?.let { shelf.installed(it.kind) }?.takeIf { it.version > manifest.version }
                if (manifest != null && newer != null && !replaceNewer(manifest, newer)) {
                    return@withContext ImportResult.KeptNewer(manifest, newer).also { Timber.i("pack import: %s", describe(it)) }
                }
                val input = resolver.openInputStream(uri) ?: return@withContext ImportResult.Refused(Refusal.Unreadable("cannot open"))
                input.use { import(it, progress) }
            } catch (e: IOException) {
                ImportResult.Refused(Refusal.Unreadable(e.message ?: e.javaClass.simpleName))
            } catch (e: SecurityException) {
                ImportResult.Refused(Refusal.Unreadable(e.message ?: e.javaClass.simpleName))
            }
        }

    suspend fun import(input: InputStream, progress: (Long, Long) -> Unit = { _, _ -> }): ImportResult = importing.withLock {
        withContext(Dispatchers.IO) {
            imported = true
            val result = shelf.import(input.buffered(BUFFER), progress)
            Timber.i("pack import: %s", describe(result))
            if (result is ImportResult.Imported) {
                // imported whatever comes of telling the rest: in use from the next start
                try {
                    changed(listOf(result.manifest.kind))
                } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                    Timber.e(e, "after a pack import")
                }
            }
            result
        }
    }

    @VisibleForTesting
    var restartHost: () -> Boolean = HostDaemon::restartIfRunning

    private fun changed(kinds: List<PackKind>) {
        // the host restarts first, so engines are remade on the new pack. The host reads no voice pack: no restart to lose composing
        val restartsHost = PackKind.TEXT in kinds
        val restarted = restartsHost && try {
            restartHost()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            Timber.e(e, "host not restarted after a pack change")
            false
        }
        // an import and a verify may both count: neither lost
        mutableState.update { State(snapshot(), it.generation + 1) }
        // a host not up in time may still read the replaced generation, so it is left for the next start's load
        if (restarted || !restartsHost) shelf.sweep()
    }

    private fun snapshot() = PackKind.entries.associateWith { shelf.status(it) }

    private fun trustedKeys(): List<ByteArray> =
        BuildConfig.PACK_KEYS.split(',').filter { it.isNotBlank() }.map { Base64.decode(it, Base64.DEFAULT) }

    private fun requirements() = mapOf(
        PackKind.TEXT to PackRequirement(BuildConfig.PACK_TEXT_FORMAT, BuildConfig.PACK_TEXT_MIN_VERSION),
        PackKind.VOICE to PackRequirement(BuildConfig.PACK_VOICE_FORMAT, BuildConfig.PACK_VOICE_MIN_VERSION),
    )

    // for the log: no paths, no user names
    private fun describe(status: PackStatus) = when (status) {
        PackStatus.Missing -> "missing"
        is PackStatus.Ready -> "v${status.manifest.version} (format ${status.manifest.format})"
        is PackStatus.NeedsUpdate -> "v${status.manifest.version} needs v${status.needed.minVersion} format ${status.needed.format}"
        is PackStatus.NeedsNewerApp -> "v${status.manifest.version} format ${status.manifest.format}, newer than this app's ${status.needed.format}"
        is PackStatus.Damaged -> "v${status.manifest.version} damaged"
    }

    private fun describe(result: ImportResult) = when (result) {
        is ImportResult.Imported -> "${result.manifest.kind.id} v${result.manifest.version} imported"
        is ImportResult.AlreadyThere -> "${result.manifest.kind.id} v${result.manifest.version} already there"
        is ImportResult.Refused -> "refused: ${result.why.javaClass.simpleName}"
        is ImportResult.KeptNewer -> "${result.manifest.kind.id} v${result.manifest.version} not taken over v${result.installed.version}"
    }

    private const val BUFFER = 1 shl 16
    private const val VERIFY_AFTER_MS = 30_000L
    private const val CHARGING_POLL_MS = 15L * 60 * 1000
    private const val RECENTLY_MS = 10L * 60 * 1000
}
