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
import org.fcitx.fcitx5.android.pack.PackRequirement
import org.fcitx.fcitx5.android.pack.PackShelf
import org.fcitx.fcitx5.android.pack.PackStatus
import org.fcitx.fcitx5.android.pack.Refusal
import org.fcitx.fcitx5.android.pack.VerifyDue
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * The app's resource packs (dev/design/060-asset-packs.md): ime-core's [PackShelf] in the
 * device-protected `files/packs` (read before the first unlock, kept by an upgrade), trusting the
 * keys this build was made with (`BuildConfig.PACK_KEYS`: a release build the release key only) and
 * requiring what its code reads (`PACK_<ID>_FORMAT`, `PACK_<ID>_MIN_VERSION`, app/packs.properties).
 *
 * Read through [AppData]; shown and imported on the 资源包 page (PacksFragment); without the text
 * pack the keyboard asks for it (PackGateView), without the voice pack voice input does (VoiceFeature).
 */
object PackStore {

    /** What is there: each pack's status, and [generation], counted up by every import that changed one. */
    class State(val statuses: Map<PackKind, PackStatus>, val generation: Int) {
        fun status(kind: PackKind): PackStatus = statuses.getValue(kind)

        fun ready(kind: PackKind) = status(kind) is PackStatus.Ready
    }

    private val root: File
        get() = File(appContext.createDeviceProtectedStorageContext().filesDir, "packs")

    // loaded when first asked (FcitxApplication asks off the main thread at the start): the
    // leftovers of an import killed midway deleted, each manifest's signature checked
    @Volatile
    private var opened: PackShelf? = null

    private val shelf: PackShelf
        get() = opened ?: synchronized(this) {
            opened ?: PackShelf(root, trustedKeys(), requirements()).also {
                // leftovers deleted after, on their own thread: a main-thread wait here is the load alone
                it.load(tidy = false)
                Timber.i("packs: %s", PackKind.entries.joinToString { k -> "${k.id} ${describe(it.status(k))}" })
                opened = it
                thread(name = "packs-tidy") { it.tidy() }
            }
        }

    private val mutableState by lazy { MutableStateFlow(State(snapshot(), 0)) }

    val state: StateFlow<State>
        get() = mutableState.asStateFlow()

    private val importing = Mutex()

    fun status(kind: PackKind): PackStatus = shelf.status(kind)

    fun ready(kind: PackKind) = status(kind) is PackStatus.Ready

    /** The directory of [kind]'s files, if ready; else null. */
    fun dir(kind: PackKind): File? = shelf.dir(kind)

    /** Loads the shelf now: on a worker at the app's start, so the keyboard's first look need not wait on it. */
    fun warmUp() {
        mutableState.value
    }

    /**
     * Tests: [shelf] in place of the device's, as if just loaded (the keyboard's tests one with both
     * packs, KeyboardHarness; the gate's, one without the text pack). The engine is not restarted.
     */
    @VisibleForTesting
    fun useShelf(shelf: PackShelf) {
        opened = shelf
        mutableState.value = State(snapshot(), mutableState.value.generation + 1)
    }

    /**
     * Whether [verifyInBackground] runs: not in the JVM tests (app/build.gradle.kts sets
     * `youmo.packs.verify=off`), where a thread reading packs 30 s after a test's start would land
     * in another's; PacksUatTest calls [verifyDue] itself.
     */
    @VisibleForTesting
    var verifiesInBackground = System.getProperty("youmo.packs.verify") != "off"

    /**
     * The packs in use read through when due ([PackShelf.due]: the first start after an import,
     * then every 30 days; a start reads their sizes only), on a thread of the lowest priority,
     * [VERIFY_AFTER_MS] after the start: not competing with the keyboard's first look. Hundreds of
     * MB read, so the 30 days' read waits for the phone to be charging (checked every
     * [CHARGING_POLL_MS] while the process lives); the first one after an import does not wait. One
     * found damaged is not used (the keyboard's gate, the page say 已损坏，请重新导入), the engine
     * made anew without it.
     */
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

    /**
     * [verifyInBackground]'s one pass, now, on this thread: each pack due read through, one due
     * only for its age only if [charging]. True if one is left waiting for the charger.
     */
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

    /** Every pack in use read through, due or not, now, on this thread: true if one was found damaged. */
    @VisibleForTesting
    fun verifyNow(): Boolean {
        return damaged(PackKind.entries.filter { shelf.verify(it) })
    }

    // [kinds] found damaged by a read through: said, and the rest told; true if any
    private fun damaged(kinds: List<PackKind>): Boolean {
        if (kinds.isEmpty()) return false
        Timber.w("resource packs damaged: %s", kinds.joinToString { it.id })
        changed(kinds)
        return true
    }

    // generations already read through after a failure of the host on them, this process: an
    // engine's failure that is no pack's (a bug) does not cost a read of hundreds of MB at each
    private val readAfterFailure = HashSet<File>()

    /**
     * The host failed on [kind]'s pack ([e]: an engine that could not load, its start that threw):
     * its files read through now, on the caller's thread (the host's), before the keyboard asks
     * for them again, so a pack damaged since its last read is [PackStatus.Damaged] (the gate, not
     * an engine failing at each start, a process ending at each start). Not for a pack missing
     * ([PackUnavailableException]); once a generation a process; nor for one read through in the
     * last [RECENTLY_MS] or since this process started ([processStartedAt]): a start failing at
     * each start (a crash loop) does not read hundreds of MB at each. Found damaged, what reads the
     * pack looks again by [state], and the host is restarted ([restartHost]) on a thread of its
     * own: its engines loaded before stop using the damaged files. Not on this one, the host's
     * (restartIfRunning waits for the host it would be stopping). True if found damaged.
     */
    fun verifyAfterFailure(kind: PackKind, e: Throwable): Boolean {
        if (generateSequence(e) { it.cause }.take(MAX_CAUSES).any { it is PackUnavailableException }) return false
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
            // counted after the restart, as changed() does: the keyboard's field focused again on the new host, not the one going
            mutableState.update { State(snapshot(), it.generation + 1) }
        }
        return true
    }

    /** Tests: when this process started, epoch ms (as the clock reads now: the boot's elapsed time the base). */
    @VisibleForTesting
    var processStartedAt: () -> Long = {
        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
    }

    private fun charging(): Boolean =
        appContext.getSystemService(Context.BATTERY_SERVICE)?.let { (it as BatteryManager).isCharging } == true

    /** [uri] (a file the user picked) imported; [progress] told on the worker as it copies. */
    suspend fun import(resolver: ContentResolver, uri: Uri, progress: (Long, Long) -> Unit = { _, _ -> }): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                val input = resolver.openInputStream(uri) ?: return@withContext ImportResult.Refused(Refusal.Unreadable("cannot open"))
                input.use { import(it, progress) }
            } catch (e: IOException) {
                ImportResult.Refused(Refusal.Unreadable(e.message ?: e.javaClass.simpleName))
            } catch (e: SecurityException) {
                ImportResult.Refused(Refusal.Unreadable(e.message ?: e.javaClass.simpleName))
            }
        }

    /** [input] imported, one import at a time; the settings page's, and the debug build's UAT import. */
    suspend fun import(input: InputStream, progress: (Long, Long) -> Unit = { _, _ -> }): ImportResult = importing.withLock {
        withContext(Dispatchers.IO) {
            val result = shelf.import(input.buffered(BUFFER), progress)
            Timber.i("pack import: %s", describe(result))
            if (result is ImportResult.Imported) {
                // imported whatever comes of telling the rest: the pack is in use from the next start
                try {
                    changed(listOf(result.manifest.kind))
                } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                    Timber.e(e, "after a pack import")
                }
            }
            result
        }
    }

    /** Tests: what restarts the host after a change ([HostDaemon.restartIfRunning]); true once it is up anew. */
    @VisibleForTesting
    var restartHost: () -> Boolean = HostDaemon::restartIfRunning

    // [kinds] changed: the engine made anew on the new files if the text pack is among them; whatever
    // reads the packs looks again ([state]'s generation, or [dir] asked again)
    private fun changed(kinds: List<PackKind>) {
        // the host restarted first: its engines (mapped on the old pack, or not made without one)
        // made anew. The state after it, so what the keyboard queues on it (its field focused again)
        // waits for the new host, not the one going. A restart that throws is no reason not to count.
        // The host reads no voice pack: a voice pack's change does not restart it (and lose what is being composed)
        val restartsHost = PackKind.TEXT in kinds
        val restarted = restartsHost && try {
            restartHost()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            Timber.e(e, "host not restarted after a pack change")
            false
        }
        // an import and a verify may both count: neither count lost
        mutableState.update { State(snapshot(), it.generation + 1) }
        // the generation replaced, once the host is up on the new one (restartIfRunning waits for
        // it, if restarted) and the rest told to look again: a file of it opened only now would have been gone.
        // A host not up anew in time may still read it: left for the next start's load
        if (restarted || !restartsHost) shelf.sweep()
    }

    private fun snapshot() = PackKind.entries.associateWith { shelf.status(it) }

    private fun trustedKeys(): List<ByteArray> =
        BuildConfig.PACK_KEYS.split(',').filter { it.isNotBlank() }.map { Base64.decode(it, Base64.DEFAULT) }

    private fun requirements() = mapOf(
        PackKind.TEXT to PackRequirement(BuildConfig.PACK_TEXT_FORMAT, BuildConfig.PACK_TEXT_MIN_VERSION),
        PackKind.VOICE to PackRequirement(BuildConfig.PACK_VOICE_FORMAT, BuildConfig.PACK_VOICE_MIN_VERSION),
    )

    // for the log: no paths, no names of the user's
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
    }

    private const val BUFFER = 1 shl 16
    private const val VERIFY_AFTER_MS = 30_000L
    private const val CHARGING_POLL_MS = 15L * 60 * 1000
    private const val MAX_CAUSES = 8
    private const val RECENTLY_MS = 10L * 60 * 1000
}
