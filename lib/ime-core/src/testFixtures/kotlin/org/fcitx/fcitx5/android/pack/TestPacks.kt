/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.pack

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Signed packs made in a test, as the build makes them (PackTask: a zip of stored entries, the
 * manifest, its signature, then the files), signed by a key of the test's own through the JDK's
 * Ed25519 (Java 15+), the one the build signs with.
 */
class TestPacks(val keys: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()) {

    /** The raw 32-byte public key: the X.509 encoding's last 32 bytes. */
    val publicKey: ByteArray = keys.public.encoded.let { it.copyOfRange(it.size - Ed25519.KEY_SIZE, it.size) }

    fun sign(message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(keys.private)
        update(message)
        sign()
    }

    /** The manifest's JSON for [files] (path to bytes), as the build writes it; [sha] overrides a file's SHA-256. */
    fun manifest(
        kind: PackKind,
        files: Map<String, ByteArray>,
        version: Int = 1,
        format: Int = 1,
        sha: Map<String, String> = emptyMap(),
    ): ByteArray {
        val entries = files.entries.joinToString(",") { (path, bytes) ->
            // a path's quote or backslash escaped, as JSON has them
            val quoted = path.replace("\\", "\\\\").replace("\"", "\\\"")
            """{"path": "$quoted", "size": ${bytes.size}, "sha256": "${sha[path] ?: sha256(bytes)}"}"""
        }
        val key = PackManifest.keyName(publicKey)
        return """{"pack": "${kind.id}", "version": $version, "format": $format, "key": "$key", "files": [$entries]}""".toByteArray()
    }

    /**
     * A pack's bytes: [manifest] (made of [files] if not given), its signature (or [signature]),
     * then [entries] (the files themselves if not given), their names written in [charset] (a zip's
     * are UTF-8; ISO 8859-1 writes a name's byte 0xFF as is, which UTF-8 cannot read).
     */
    fun pack(
        kind: PackKind,
        files: Map<String, ByteArray>,
        version: Int = 1,
        format: Int = 1,
        manifest: ByteArray = manifest(kind, files, version, format),
        signature: ByteArray = sign(manifest),
        entries: Map<String, ByteArray> = files,
        charset: Charset = Charsets.UTF_8,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, charset).use { zip ->
            stored(zip, PackManifest.NAME, manifest)
            stored(zip, PackManifest.SIGNATURE, signature)
            entries.forEach { (path, bytes) -> stored(zip, path, bytes) }
        }
        return out.toByteArray()
    }

    companion object {
        fun sha256(bytes: ByteArray): String = PackManifest.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun stored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
            val entry = ZipEntry(name)
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = CRC32().apply { update(bytes) }.value
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }

        /** A text pack's files, small: what a test needs of one is that it is there. */
        val TEXT_FILES = mapOf(
            "engine/pinyin.data" to ByteArray(1000) { it.toByte() },
            "engine/table/wbx.data" to "wubi".toByteArray(),
            "handwriting/chars.txt" to "一二三".toByteArray(),
        )

        val VOICE_FILES = mapOf(
            "voice/encoder.ort" to ByteArray(3000) { (it * 7).toByte() },
            "voice/tokens.txt" to "a 1\n".toByteArray(),
        )
    }
}
