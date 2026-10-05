/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * A tool's code as far as a run of it reaches: the classes [main] names in its constant pool, and
 * those they name, hashed. The engine's data is made by a tool that shares :lib:ime-core with the
 * app; keyed on the whole classpath, every edit to the keyboard's logic made the data again, the
 * mixed model sorted whole in memory (minutes, and 3.6 GB). Keyed on this, only an edit to what the
 * tool can run does. A class reached by reflection alone would be missed: the tool has none.
 */
object ToolFingerprint {

    fun of(classpath: Collection<File>, main: String): String {
        val zips = classpath.filter { it.isFile && it.name.endsWith(".jar") }.map(::ZipFile)
        try {
            fun bytes(name: String): ByteArray? {
                for (dir in classpath) if (dir.isDirectory) File(dir, "$name.class").takeIf { it.isFile }?.let { return it.readBytes() }
                for (zip in zips) zip.getEntry("$name.class")?.let { return zip.getInputStream(it).use { s -> s.readBytes() } }
                return null
            }
            val start = main.replace('.', '/')
            val seen = sortedMapOf<String, ByteArray>()
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val name = queue.removeFirst()
                if (name in seen) continue
                // the JDK's and anything not on the classpath: not the tool's
                val code = bytes(name) ?: continue
                seen[name] = code
                names(code).filterTo(queue) { it !in seen }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            for ((name, code) in seen) {
                digest.update(name.toByteArray())
                digest.update(code)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            zips.forEach { it.close() }
        }
    }

    // the classes a class file names: its Class constants, and the types in its descriptors
    private fun names(code: ByteArray): Set<String> {
        val input = DataInputStream(code.inputStream())
        input.skipBytes(8)
        val count = input.readUnsignedShort()
        val utf8 = HashMap<Int, String>()
        val classes = ArrayList<Int>()
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[i] = input.readUTF()
                7 -> classes += input.readUnsignedShort()
                8, 16, 19, 20 -> input.skipBytes(2)
                15 -> input.skipBytes(3)
                3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                5, 6 -> {
                    input.skipBytes(8)
                    i++
                }
                else -> error("constant pool tag $tag")
            }
            i++
        }
        val out = HashSet<String>()
        classes.mapNotNullTo(out) { utf8[it]?.trimStart('[')?.removePrefix("L")?.removeSuffix(";") }
        for (s in utf8.values) DESCRIPTOR.findAll(s).mapTo(out) { it.groupValues[1] }
        return out
    }

    private val DESCRIPTOR = Regex("L([\\w/$]+);")
}
