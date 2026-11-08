/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pack

import android.content.ContentResolver
import android.net.Uri
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
                it.load()
                Timber.i("packs: %s", PackKind.entries.joinToString { k -> "${k.id} ${describe(it.status(k))}" })
                opened = it
            }
        }

    private val mutableState by lazy { MutableStateFlow(State(snapshot(), 0)) }

    val state: StateFlow<State>
        get() = mutableState.asStateFlow()

    private val importing = Mutex()

    fun status(kind: PackKind): PackStatus = shelf.status(kind)

    fun ready(kind: PackKind) = status(kind) is PackStatus.Ready

    /** The directory of [kind]'s files, it ready; else null. */
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
     * The packs in use read through ([PackShelf.verify]: a start reads their sizes only), on a
     * thread of the lowest priority, [VERIFY_AFTER_MS] after the start: not competing with the
     * keyboard's first look. One found damaged is not used (the keyboard's gate, the page say
     * 已损坏，请重新导入), the engine made anew without it.
     */
    fun verifyInBackground() {
        thread(name = "packs-verify", priority = Thread.MIN_PRIORITY, isDaemon = true) {
            Thread.sleep(VERIFY_AFTER_MS)
            runCatching { verifyNow() }.onFailure { Timber.e(it, "resource packs not verified") }
        }
    }

    /** [verifyInBackground]'s work, now, on this thread: true if a pack was found damaged. */
    @VisibleForTesting
    fun verifyNow(): Boolean {
        val damaged = PackKind.entries.filter { shelf.verify(it) }
        if (damaged.isEmpty()) return false
        Timber.w("resource packs damaged: %s", damaged.joinToString { it.id })
        changed()
        return true
    }

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
            if (result is ImportResult.Imported) changed()
            result
        }
    }

    /** Tests: what restarts the host after a change ([HostDaemon.restartIfRunning]); true once it is up anew. */
    @VisibleForTesting
    var restartHost: () -> Boolean = HostDaemon::restartIfRunning

    // the engine made anew on the new files; whatever reads them looks again (AppData's generation)
    private fun changed() {
        // the host restarted first: its engines (mapped on the old pack, or not made without one)
        // made anew. The state after it, so what the keyboard queues on it (its field focused again)
        // waits for the new host, not the one going
        val restarted = restartHost()
        // an import and a verify may both count: neither count lost
        mutableState.update { State(snapshot(), it.generation + 1) }
        // the generation replaced, once the host is up on the new one (restartIfRunning waits for
        // it) and the rest told to look again: a file of it opened only now would have been gone.
        // A host not up anew in time may still read it: left for the next start's load
        if (restarted) shelf.sweep()
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
}
