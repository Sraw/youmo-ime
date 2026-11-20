/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import org.gradle.api.logging.Logging
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** A hash of the classes [main] reaches, less debug info, so only a runnable edit remakes the engine data; reflection is missed. */
object ToolFingerprint {

    /** [warn] is told when a class cannot be read and the key falls back to the whole classpath. */
    fun of(
        classpath: Collection<File>,
        main: String,
        warn: (String) -> Unit = { Logging.getLogger(ToolFingerprint::class.java).warn(it) },
    ): String {
        val zips = ArrayList<ZipFile>()
        try {
            // opened one by one inside the try: if one cannot be opened, those before it are still closed
            for (file in classpath) if (file.isFile && file.name.endsWith(".jar")) zips += ZipFile(file)
            fun bytes(name: String): ByteArray? {
                for (dir in classpath) if (dir.isDirectory) File(dir, "$name.class").takeIf { it.isFile }?.let { return it.readBytes() }
                for (zip in zips) zip.getEntry("$name.class")?.let { return zip.getInputStream(it).use { s -> s.readBytes() } }
                return null
            }
            val start = main.replace('.', '/')
            val seen = sortedMapOf<String, ByteArray>()
            // the JDK's and anything not on the classpath: not the tool's, and looked for once
            val missing = HashSet<String>()
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val name = queue.removeFirst()
                if (name in seen || name in missing) continue
                val code = bytes(name)
                if (code == null) {
                    missing += name
                    continue
                }
                // a constant of a later class file format: what the class names is unknown
                val classFile = read(code) ?: run {
                    // said, as from then on every edit to :lib:ime-core makes the data again, for minutes
                    warn("$name has a constant pool tag of a later class file format: the engine data is keyed on the whole tool classpath, so any edit to it compiles the data again")
                    return whole(classpath, zips)
                }
                seen[name] = classFile.code
                classFile.names.filterTo(queue) { it !in seen && it !in missing }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            for ((name, code) in seen) {
                digest.update(name.toByteArray())
                digest.update(code)
            }
            return hex(digest.digest())
        } finally {
            zips.forEach { it.close() }
        }
    }

    // every class of the classpath, by its name in it: too broad, as the key was before this, but never stale
    private fun whole(classpath: Collection<File>, zips: List<ZipFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (dir in classpath) {
            if (!dir.isDirectory) continue
            val files = dir.walk().filter { it.isFile && it.name.endsWith(".class") }.sortedBy { it.invariantSeparatorsPath }
            for (file in files) {
                digest.update(file.relativeTo(dir).invariantSeparatorsPath.toByteArray())
                digest.update(file.readBytes())
            }
        }
        for (zip in zips) {
            for (entry in zip.entries().toList().filter { it.name.endsWith(".class") }.sortedBy { it.name }) {
                digest.update(entry.name.toByteArray())
                digest.update(zip.getInputStream(entry).use { it.readBytes() })
            }
        }
        return hex(digest.digest())
    }

    /** A class file as the fingerprint takes it: the classes it names, and its bytes to hash. */
    internal class ClassFile(val names: Set<String>, val code: ByteArray)

    private class Cursor(val code: ByteArray) : ByteArrayInputStream(code) {
        val position get() = pos
    }

    // the classes a class file names (Class constants, descriptor types); null for an unknown constant pool tag
    internal fun read(code: ByteArray): ClassFile? {
        val cursor = Cursor(code)
        val input = DataInputStream(cursor)
        input.skipBytes(8)
        val count = input.readUnsignedShort()
        val utf8 = HashMap<Int, String>()
        // where each Integer constant starts, by its index
        val integers = HashMap<Int, Int>()
        val classes = ArrayList<Int>()
        val debug = ArrayList<IntRange>()
        var i = 1
        while (i < count) {
            val start = cursor.position
            when (input.readUnsignedByte()) {
                1 -> {
                    val text = input.readUTF()
                    utf8[i] = text
                    // Kotlin's SMAP, kept in an annotation as well as in its attribute: line numbers
                    if (text.startsWith("SMAP\n") && "\nKotlin\n" in text) debug += start until cursor.position
                }
                3 -> {
                    integers[i] = start
                    input.skipBytes(4)
                }
                7 -> classes += input.readUnsignedShort()
                8, 16, 19, 20 -> input.skipBytes(2)
                15 -> input.skipBytes(3)
                4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                5, 6 -> {
                    input.skipBytes(8)
                    i++
                }
                else -> return null
            }
            i++
        }
        val out = HashSet<String>()
        classes.mapNotNullTo(out) { utf8[it]?.trimStart('[')?.removePrefix("L")?.removeSuffix(";") }
        for (s in utf8.values) DESCRIPTOR.findAll(s).mapTo(out) { it.groupValues[1] }
        return ClassFile(out, withoutDebugInfo(cursor, input, utf8, integers, debug))
    }

    // [cursor]'s code less what only a stack trace or debugger reads, so moved code does not remake the data
    private fun withoutDebugInfo(
        cursor: Cursor,
        input: DataInputStream,
        utf8: Map<Int, String>,
        integers: Map<Int, Int>,
        debug: MutableList<IntRange>,
    ): ByteArray {
        val code = cursor.code
        val lines = HashSet<Int>()
        val others = HashSet<Int>()
        // false once an attribute this does not read might name an Integer constant
        var allRead = true
        fun skip(n: Int) = input.skipExactly(n)
        fun attributes() {
            repeat(input.readUnsignedShort()) {
                val start = cursor.position
                val name = utf8[input.readUnsignedShort()]
                val length = input.readInt()
                when (name) {
                    in DEBUG_ATTRIBUTES -> {
                        skip(length)
                        debug += start until cursor.position
                    }
                    "Code" -> {
                        // its length counts the debug attributes in it
                        debug += start + 2 until start + 6
                        skip(4)
                        skip(input.readInt())
                        skip(8 * input.readUnsignedShort())
                        attributes()
                        if (cursor.position != start + 6 + length) throw EOFException()
                    }
                    else -> {
                        skip(length)
                        if (!constants(name, DataInputStream(ByteArrayInputStream(code, start + 6, length)), utf8, lines, others)) allRead = false
                    }
                }
            }
        }
        try {
            // access flags, this and super class, interfaces
            skip(6)
            skip(2 * input.readUnsignedShort())
            // the fields, then the methods
            repeat(2) {
                repeat(input.readUnsignedShort()) {
                    skip(6)
                    attributes()
                }
            }
            attributes()
            if (cursor.position != code.size) return code
        } catch (e: EOFException) {
            return code
        }
        // a line's constant anything else names is kept, as is every one an unread attribute might name
        if (allRead) for (index in lines - others) integers[index]?.let { debug += it + 1 until it + 5 }
        debug.sortBy { it.first }
        val kept = ByteArrayOutputStream(code.size)
        var from = 0
        for (range in debug) {
            kept.write(code, from, range.first - from)
            from = range.last + 1
        }
        kept.write(code, from, code.size - from)
        return kept.toByteArray()
    }

    // the Integer constants attribute [name] names: a coroutine's lines (@DebugMetadata) in [lines], others in [others]
    private fun constants(name: String?, input: DataInputStream, utf8: Map<Int, String>, lines: MutableSet<Int>, others: MutableSet<Int>): Boolean {
        when (name) {
            "RuntimeVisibleAnnotations" -> repeat(input.readUnsignedShort()) { annotation(input, utf8, lines, others) }
            "RuntimeInvisibleAnnotations" -> repeat(input.readUnsignedShort()) { annotation(input, utf8, others, others) }
            "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations" -> repeat(input.readUnsignedByte()) {
                repeat(input.readUnsignedShort()) { annotation(input, utf8, others, others) }
            }
            "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations" -> repeat(input.readUnsignedShort()) { typeAnnotation(input, utf8, others) }
            "AnnotationDefault" -> elementValue(input, utf8, others, others)
            "ConstantValue" -> others += input.readUnsignedShort()
            // each method's arguments, any loadable constant
            "BootstrapMethods" -> repeat(input.readUnsignedShort()) {
                input.skipExactly(2)
                repeat(input.readUnsignedShort()) { others += input.readUnsignedShort() }
            }
            else -> return name in NO_CONSTANTS
        }
        if (input.available() != 0) throw EOFException()
        return true
    }

    // a type annotation: what it annotates and the path into the type skipped, then as any other
    private fun typeAnnotation(input: DataInputStream, utf8: Map<Int, String>, others: MutableSet<Int>) {
        val target = when (input.readUnsignedByte()) {
            0x13, 0x14, 0x15 -> 0
            0x00, 0x01, 0x16 -> 1
            0x10, 0x11, 0x12, 0x17, 0x42, 0x43, 0x44, 0x45, 0x46 -> 2
            0x47, 0x48, 0x49, 0x4A, 0x4B -> 3
            // a local variable's ranges: start, length and slot each
            0x40, 0x41 -> 6 * input.readUnsignedShort()
            else -> throw EOFException()
        }
        input.skipExactly(target)
        input.skipExactly(2 * input.readUnsignedByte())
        annotation(input, utf8, others, others)
    }

    private fun annotation(input: DataInputStream, utf8: Map<Int, String>, lines: MutableSet<Int>, others: MutableSet<Int>) {
        val type = utf8[input.readUnsignedShort()]
        repeat(input.readUnsignedShort()) {
            val name = utf8[input.readUnsignedShort()]
            elementValue(input, utf8, if (type == DEBUG_METADATA && name in DEBUG_LINES) lines else others, others)
        }
    }

    // an annotation's element value: the int constants it names go in [ints], all others in [others]
    private fun elementValue(input: DataInputStream, utf8: Map<Int, String>, ints: MutableSet<Int>, others: MutableSet<Int>) {
        when (input.readUnsignedByte().toChar()) {
            'I' -> ints += input.readUnsignedShort()
            // a byte, char, short or boolean is an Integer constant too
            'B', 'C', 'S', 'Z' -> others += input.readUnsignedShort()
            'D', 'F', 'J', 's', 'c' -> input.skipExactly(2)
            'e' -> input.skipExactly(4)
            '@' -> annotation(input, utf8, others, others)
            '[' -> repeat(input.readUnsignedShort()) { elementValue(input, utf8, ints, others) }
            else -> throw EOFException()
        }
    }

    private fun DataInputStream.skipExactly(n: Int) {
        if (n < 0 || skipBytes(n) != n) throw EOFException()
    }

    private val DESCRIPTOR = Regex("L([\\w/$]+);")

    private val DEBUG_ATTRIBUTES = setOf("SourceFile", "SourceDebugExtension", "LineNumberTable", "LocalVariableTable", "LocalVariableTypeTable")

    // those that name no Integer constant, of the attributes JVMS 4.7 defines
    private val NO_CONSTANTS = setOf(
        "StackMapTable", "Exceptions", "InnerClasses", "EnclosingMethod", "Synthetic", "Signature", "Deprecated",
        "MethodParameters", "Module", "ModulePackages", "ModuleMainClass", "NestHost", "NestMembers", "PermittedSubclasses",
    )

    private const val DEBUG_METADATA = "Lkotlin/coroutines/jvm/internal/DebugMetadata;"

    // l, each suspension point's line, and nl, the line after it (version 2, for a debugger)
    private val DEBUG_LINES = setOf("l", "nl")
}
