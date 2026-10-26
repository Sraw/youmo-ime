/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval.parity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.fcitx.fcitx5.android.engine.EngineBehaviour
import java.io.File

/**
 * What a checkout's engine is, as a recording follows it (fcitx-replacement §7.2): the behaviour version
 * ([EngineBehaviour.VERSION], bumped by hand when ranking, decoding or the host's handling of a key
 * changes), a digest of the data and settings the app ships in its sources ([CONFIG_DIRS]) and the data
 * releases the app's build fetches. Not the source tree: comments, renames and refactors change nothing a
 * key does. A recording of another engine still compares, labelled ([ENGINE_CHANGED]).
 */
object Provenance {
    const val DATA_PLUGIN = "build-logic/convention/src/main/kotlin/EngineDataPlugin.kt"

    /**
     * The engine's data and settings that are in the tree: fcitx's pkgdata (punctuation, quick phrases,
     * Unicode), learn/, and what EngineDataPlugin compiles into the engine data from the tree (lexicon/'s
     * corrections, Latin words and emoji edits; OpenCC's s2tw dictionaries).
     */
    val CONFIG_DIRS = listOf("app/src/main/host-assets", "app/src/main/assets", "lexicon", "lib/ime-dict-tool/opencc")

    /**
     * Not part of the engine though under [CONFIG_DIRS]: what the native host's install left in checkouts
     * that built it (ignored, and kept out of the APK by app/build.gradle.kts' ignoreAssetsPatterns), else
     * one tree had two digests by checkout.
     */
    private val NOT_CONFIG = listOf("app/src/main/assets/usr/", "app/src/main/assets/descriptor.json")

    /**
     * Of lexicon/, only what [DATA_PLUGIN] reads: add.tsv reaches the app through the words-* release
     * ([dataReleases]), reject.tsv, README.md and the scripts never do, and a stray file there labelled every
     * later comparison (F3813, F4240). Kept in step with the plugin by hand.
     */
    private val LEXICON_INPUTS = setOf("remove.tsv", "readings.tsv", "misreadings.tsv", "latin.words", "emoji.tsv").map { "lexicon/$it" }

    const val BEHAVIOUR = "behaviour"
    const val CONFIG = "config"

    /** What a comparison against a recording of another engine prints first. */
    const val ENGINE_CHANGED = "engine changed since this recording: differences may be intended"

    // `private const val LM_RELEASE = "engine-data-20261005-3"`: the release each data file comes from
    private val RELEASE = Regex("""const val (\w+)_RELEASE = "([^"]+)"""")

    /** [root]'s engine: the behaviour version and the hash of every file under [CONFIG_DIRS], by its path and bytes. */
    fun engine(root: File, behaviour: Int = EngineBehaviour.VERSION): JsonObject {
        val files = CONFIG_DIRS.map { File(root, it) }.onEach { check(it.isDirectory) { "no $it" } }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath to it } }
            .filter { (path, _) -> NOT_CONFIG.none { path.startsWith(it) } && (!path.startsWith("lexicon/") || path in LEXICON_INPUTS) }
            .sortedBy { it.first }
        val config = ParityFiles.sha256 { md ->
            files.forEach { (path, file) ->
                md.update(path.toByteArray())
                md.update(0)
                md.update(file.readBytes())
                md.update(0)
            }
        }
        return JsonObject(mapOf(BEHAVIOUR to JsonPrimitive(behaviour), CONFIG to JsonPrimitive(config)))
    }

    /** The data releases (`LM` → `engine-data-…`, `WORDS`, `ENGLISH`, `MODELS`) as the build plugin names them. */
    fun dataReleases(root: File): Map<String, String> {
        val text = File(root, DATA_PLUGIN).readText()
        return RELEASE.findAll(text).associate { it.groupValues[1].lowercase() to it.groupValues[2] }.toSortedMap()
            .also { check(it.isNotEmpty()) { "no releases in $DATA_PLUGIN" } }
    }

    /** What a recording follows of [root]: its [Header.ENGINE] and [Header.DATA]. */
    fun fingerprint(root: File): Map<String, JsonObject> = mapOf(
        Header.ENGINE to engine(root),
        Header.DATA to JsonObject(dataReleases(root).mapValues { JsonPrimitive(it.value) }),
    )

    /** A sequence file's header for [root]'s engine. */
    fun header(root: File, kind: String, ims: List<String>, seed: Long? = null): Header = Header(
        JsonObject(
            buildMap<String, JsonElement> {
                put(Header.VERSION, JsonPrimitive(1))
                put(Header.KIND, JsonPrimitive(kind))
                seed?.let { put(Header.SEED, JsonPrimitive(it)) }
                putAll(fingerprint(root))
                put(Header.IMS, JsonArray(ims.map(::JsonPrimitive)))
            },
        ),
    )

    /** Why [header] was not written for [root]'s engine (another engine or data), or null if it was. */
    fun stale(header: Header, root: File): String? = stale(header, fingerprint(root), "this checkout")

    /** As [stale] for the [fingerprint] a replay ran on, as its output's [Header.ENGINE_RUN] keeps it. */
    fun stale(header: Header, ran: JsonObject): String? = stale(header, ran, "the replay ran")

    private fun stale(header: Header, now: Map<String, JsonElement>, whose: String): String? =
        now.keys.firstOrNull { header.fields[it] != now[it] }
            ?.let { "$it: the file has ${header.fields[it]}, $whose ${now[it]}" }

    /**
     * [header] given [root]'s engine and data, the old ones kept as [Header.ENGINE_WAS] (not compared): for
     * recordings that cannot be made again (the native host's), when the fingerprint's form changes. A file
     * reheaded before keeps its first [Header.ENGINE_WAS]: that is the recording's own engine, the one a
     * second rehead would otherwise replace with the first rehead's.
     */
    fun rehead(header: Header, root: File): Header {
        val was = header.fields[Header.ENGINE_WAS]
            ?: JsonObject(mapOf(Header.ENGINE to header.fields.getValue(Header.ENGINE), Header.DATA to header.fields.getValue(Header.DATA)))
        val now = fingerprint(root)
        return header.with(Header.ENGINE_WAS to was, Header.ENGINE to now.getValue(Header.ENGINE), Header.DATA to now.getValue(Header.DATA))
    }
}
