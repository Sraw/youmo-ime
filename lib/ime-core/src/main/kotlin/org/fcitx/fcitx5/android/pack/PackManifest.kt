/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.pack

import org.fcitx.fcitx5.android.engine.rerank.Json
import java.security.MessageDigest

/** The two resource packs the APK does not carry; each owns asset paths, the same as the APK had them. Without the text pack the keyboard does not work. */
enum class PackKind(val id: String, private val owned: List<String>) {
    TEXT(
        "text",
        listOf(
            "engine/pinyin.data", "engine/english.data", "engine/sentence-model", "engine/stroke.data",
            "engine/table/", "handwriting/",
        ),
    ),
    VOICE("voice", listOf("voice/")),
    ;

    fun owns(path: String): Boolean = owned.any { path.startsWith(it) }

    companion object {
        /** Null: the APK's own. */
        fun of(path: String): PackKind? = entries.firstOrNull { it.owns(path) }

        fun byId(id: String): PackKind? = entries.firstOrNull { it.id == id }
    }
}

/** [format]: a pack of another is not read; [minVersion]: the oldest accepted. Packs are versioned apart from the app. */
data class PackRequirement(val format: Int, val minVersion: Int)

/**
 * A pack's `manifest.json`, its zip's first entry, signed by `manifest.sig` (Ed25519, the second). Every file is
 * checked against it as copied, so the signature covers them all.
 *
 * ```
 * {"pack": "text", "version": 1, "format": 1, "key": "f780…",
 *  "files": [{"path": "engine/pinyin.data", "size": 163987720, "sha256": "…"}, …]}
 * ```
 * `key`: the signing key's SHA-256's first 16 hex digits, to say a pack was signed for another build.
 */
class PackManifest(
    val kind: PackKind,
    val version: Int,
    val format: Int,
    val files: List<Entry>,
    val key: String = "",
) {
    class Entry(val path: String, val size: Long, val sha256: String)

    val size: Long get() = files.sumOf { it.size }

    /** `format<TAB>n\n` then `path<TAB>size<TAB>sha256\n` by path, SHA-256'd; AssetPacksPlugin's `contentSha256` must match (tests hold both). */
    val contentSha256: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("format\t$format\n".toByteArray(Charsets.UTF_8))
        files.sortedBy { it.path }.forEach { digest.update("${it.path}\t${it.size}\t${it.sha256}\n".toByteArray(Charsets.UTF_8)) }
        hex(digest.digest())
    }

    fun sameAs(other: PackManifest) =
        kind == other.kind && version == other.version && format == other.format && contentSha256 == other.contentSha256

    companion object {
        const val NAME = "manifest.json"
        const val SIGNATURE = "manifest.sig"

        // far more than two packs' few dozen files need
        const val MAX_BYTES = 1 shl 20
        private const val MAX_FILES = 4096
        private val PATH = Regex("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** An [IllegalArgumentException] says what is wrong. */
        fun parse(bytes: ByteArray): PackManifest {
            require(bytes.size <= MAX_BYTES) { "manifest too large" }
            val json = requireNotNull(Json.parse(String(bytes, Charsets.UTF_8)) as? Map<*, *>) { "manifest is no object" }
            val id = requireNotNull(json["pack"] as? String) { "no pack" }
            val kind = requireNotNull(PackKind.byId(id)) { "unknown pack $id" }
            val files = requireNotNull(json["files"] as? List<*>) { "no files" }
            require(files.size <= MAX_FILES) { "too many files" }
            val entries = files.map { f ->
                val file = requireNotNull(f as? Map<*, *>) { "file is no object" }
                val path = requireNotNull(file["path"] as? String) { "file without path" }
                require(safePath(path)) { "bad path $path" }
                require(kind.owns(path)) { "$path is not the $id pack's" }
                val sha = requireNotNull(file["sha256"] as? String) { "$path without sha256" }
                require(SHA256.matches(sha)) { "$path: bad sha256" }
                Entry(path, whole(file["size"], "$path's size"), sha)
            }
            require(entries.map { it.path }.toSet().size == entries.size) { "a file listed twice" }
            return PackManifest(
                kind, small(json["version"], "version"), small(json["format"], "format"), entries,
                json["key"] as? String ?: "",
            )
        }

        // JSON's numbers come as doubles: whole, not negative, exact
        private fun whole(v: Any?, what: String): Long {
            val d = requireNotNull(v as? Double) { "no $what" }
            require(d >= 0 && d == Math.floor(d) && d <= EXACT) { "bad $what" }
            return d.toLong()
        }

        private fun small(v: Any?, what: String): Int =
            whole(v, what).also { require(it <= Int.MAX_VALUE) { "bad $what" } }.toInt()

        // 2^53
        private const val EXACT = 9.007199254740992E15

        /** No `.`/`..` step, no leading or doubled `/`, nothing a file name could hide. */
        fun safePath(path: String): Boolean =
            PATH.matches(path) && path.split('/').none { it == "." || it == ".." }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun keyName(publicKey: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(publicKey)).take(16)
    }
}
