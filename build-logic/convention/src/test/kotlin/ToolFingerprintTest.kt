/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The key compileEngineData and compileEngineTables rerun on: a missed edge ships stale data. */
class ToolFingerprintTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val warnings = ArrayList<String>()

    // a class file as far as ToolFingerprint reads one; with [line], one method whose line number table puts [op] there
    private fun classFile(
        code: String,
        classes: List<String> = listOf(),
        strings: List<String> = listOf(),
        unknownTag: Boolean = false,
        line: Int? = null,
        op: Int = 0xb1,
    ): ByteArray {
        val texts = strings + if (line != null) listOf("Code", "LineNumberTable") else listOf<String>()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).run {
            writeInt(0xCAFEBABE.toInt())
            writeShort(0)
            writeShort(61)
            writeShort(1 + 1 + texts.size + 2 * classes.size + (if (unknownTag) 1 else 0))
            writeByte(1)
            writeUTF(code)
            for (s in texts) {
                writeByte(1)
                writeUTF(s)
            }
            classes.forEachIndexed { i, name ->
                writeByte(1)
                writeUTF(name)
                writeByte(7)
                writeShort(2 + texts.size + 2 * i)
            }
            if (unknownTag) writeByte(99)
            if (line != null) {
                // access, this, super, no interfaces or fields, one method: access, name, descriptor, one attribute
                listOf(0x21, 0, 0, 0, 0, 1, 0x9, 1, 1, 1).forEach { writeShort(it) }
                // Code: max stack and locals, the instruction, no exceptions, one attribute
                writeShort(2 + strings.size)
                writeInt(25)
                writeShort(1)
                writeShort(0)
                writeInt(1)
                writeByte(op)
                writeShort(0)
                writeShort(1)
                // LineNumberTable: one entry, at pc 0
                writeShort(3 + strings.size)
                writeInt(6)
                writeShort(1)
                writeShort(0)
                writeShort(line)
                // no attributes of the class
                writeShort(0)
            }
        }
        return bytes.toByteArray()
    }

    // a coroutine's class as kotlinc writes it, less its members: its line an Integer constant in @DebugMetadata's [element]
    private fun coroutine(line: Int, element: String = "l", version: Int = 2, shared: Boolean = false, alsoIn: String? = null): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).run {
            fun utf8(s: String) {
                writeByte(1)
                writeUTF(s)
            }
            fun integer(v: Int) {
                writeByte(3)
                writeInt(v)
            }
            writeInt(0xCAFEBABE.toInt())
            writeShort(0)
            writeShort(61)
            writeShort(if (alsoIn == null) 8 else 9)
            utf8("lib")
            utf8("RuntimeVisibleAnnotations")
            utf8("Lkotlin/coroutines/jvm/internal/DebugMetadata;")
            utf8(element)
            integer(line)
            utf8("v")
            integer(version)
            if (alsoIn != null) utf8(alsoIn)
            // access, this, super, no interfaces; a field of the ConstantValue, or none; no methods
            listOf(0x21, 0, 0, 0).forEach { writeShort(it) }
            if (alsoIn == "ConstantValue") {
                listOf(1, 0x18, 1, 1, 1, 8).forEach { writeShort(it) }
                writeInt(2)
                writeShort(5)
            } else {
                writeShort(0)
            }
            writeShort(0)
            val classAttribute = alsoIn?.takeIf { it != "ConstantValue" }
            writeShort(if (classAttribute == null) 1 else 2)
            writeShort(2)
            writeInt(19)
            // one annotation of two values: [element] = [line], v = version
            writeShort(1)
            writeShort(3)
            writeShort(2)
            writeShort(4)
            writeByte('['.code)
            writeShort(1)
            writeByte('I'.code)
            writeShort(if (shared) 7 else 5)
            writeShort(6)
            writeByte('I'.code)
            writeShort(7)
            if (classAttribute != null) {
                val value = naming5(classAttribute)
                writeShort(8)
                writeInt(value.size)
                write(value)
            }
        }
        return bytes.toByteArray()
    }

    // the attribute [name]'s value naming constant 5, a coroutine's line
    private fun naming5(name: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).run {
            when (name) {
                // one method, of one argument
                "BootstrapMethods" -> listOf(1, 0, 1, 5).forEach { writeShort(it) }
                // one annotation, of one value v
                "RuntimeInvisibleAnnotations" -> listOf(1, 1, 1, 6).forEach { writeShort(it) }
                // one, on a field's type, by an empty path
                "RuntimeVisibleTypeAnnotations" -> {
                    writeShort(1)
                    writeByte(0x13)
                    writeByte(0)
                    listOf(1, 1, 6).forEach { writeShort(it) }
                }
                "Signature" -> writeShort(1)
                else -> writeShort(5)
            }
            if (name.endsWith("Annotations")) {
                writeByte('I'.code)
                writeShort(5)
            }
        }
        return bytes.toByteArray()
    }

    private fun dir(name: String, classes: Map<String, ByteArray>): File {
        val dir = tmp.newFolder(name)
        for ((path, bytes) in classes) dir.resolve("$path.class").apply { parentFile.mkdirs() }.writeBytes(bytes)
        return dir
    }

    private fun jar(name: String, classes: Map<String, ByteArray>): File {
        val jar = tmp.root.resolve(name)
        ZipOutputStream(jar.outputStream()).use { zip ->
            for ((path, bytes) in classes) {
                zip.putNextEntry(ZipEntry("$path.class"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return jar
    }

    private fun tool(lib: String, other: String) = mapOf(
        "tool/MainKt" to classFile("main", classes = listOf("tool/Lib", "java/lang/String")),
        "tool/Lib" to classFile(lib),
        "tool/Other" to classFile(other),
    )

    private fun withLib(lib: ByteArray) = mapOf("tool/MainKt" to classFile("main", classes = listOf("tool/Lib")), "tool/Lib" to lib)

    private fun fingerprint(vararg classpath: File) = ToolFingerprint.of(classpath.toList(), "tool.MainKt") { warnings += it }

    @Test
    fun anEditToAClassTheToolReachesChangesIt() {
        assertNotEquals(fingerprint(dir("a", tool("lib 1", "other"))), fingerprint(dir("b", tool("lib 2", "other"))))
    }

    @Test
    fun anEditToAClassItCannotReachDoesNot() {
        assertEquals(fingerprint(dir("a", tool("lib", "other 1"))), fingerprint(dir("b", tool("lib", "other 2"))))
    }

    @Test
    fun aClassNamedOnlyInADescriptorIsReached() {
        fun classes(lib: String) = mapOf(
            "tool/MainKt" to classFile("main", strings = listOf("(Ltool/Lib;)V")),
            "tool/Lib" to classFile(lib),
        )
        assertNotEquals(fingerprint(dir("a", classes("lib 1"))), fingerprint(dir("b", classes("lib 2"))))
    }

    @Test
    fun aClassInAJarIsReachedAsOneInADirectory() {
        val main = mapOf("tool/MainKt" to classFile("main", classes = listOf("tool/Lib")))
        val a = fingerprint(dir("a", main), jar("a.jar", mapOf("tool/Lib" to classFile("lib 1"))))
        val b = fingerprint(dir("b", main), jar("b.jar", mapOf("tool/Lib" to classFile("lib 2"))))
        assertNotEquals(a, b)
    }

    @Test
    fun theSameClassesElsewhereGiveTheSameFingerprint() {
        assertEquals(fingerprint(dir("a", tool("lib", "other"))), fingerprint(dir("b", tool("lib", "other"))))
    }

    @Test
    fun aConstantOfALaterFormatKeysOnEveryClass() {
        fun classes(other: String) = tool("lib", other) + ("tool/MainKt" to classFile("main", classes = listOf("tool/Lib"), unknownTag = true))
        val a = fingerprint(dir("a", classes("other 1")))
        assertNotEquals(a, fingerprint(dir("b", classes("other 2"))))
        assertEquals(a, fingerprint(dir("c", classes("other 1"))))
    }

    @Test
    fun codeMovedToOtherLinesDoesNotChangeIt() {
        assertEquals(fingerprint(dir("a", withLib(classFile("lib", line = 10)))), fingerprint(dir("b", withLib(classFile("lib", line = 20)))))
    }

    @Test
    fun kotlinsLineMapDoesNotChangeItEither() {
        fun lib(line: Int) = classFile("lib", strings = listOf("SMAP\nLib.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Lib.kt\ntool/Lib\n*L\n1#1,$line:1\n*E\n"), line = line)
        assertEquals(fingerprint(dir("a", withLib(lib(9)))), fingerprint(dir("b", withLib(lib(120)))))
    }

    @Test
    fun anEditToAnInstructionChangesIt() {
        assertNotEquals(fingerprint(dir("a", withLib(classFile("lib", line = 10, op = 0xb1)))), fingerprint(dir("b", withLib(classFile("lib", line = 10, op = 0x00)))))
    }

    @Test
    fun aConstantOfALaterFormatIsWarnedOfAndNothingElseIs() {
        fingerprint(dir("a", tool("lib", "other")))
        assertEquals(listOf<String>(), warnings)
        fingerprint(dir("b", tool("lib", "other") + ("tool/MainKt" to classFile("main", classes = listOf("tool/Lib"), unknownTag = true))))
        assertEquals(1, warnings.size)
        assertTrue(warnings.single(), warnings.single().startsWith("tool/MainKt "))
    }

    @Test
    fun aCoroutinesLinesDoNotChangeIt() {
        for (element in listOf("l", "nl")) {
            val a = fingerprint(dir("$element-a", withLib(coroutine(10, element))))
            assertEquals(element, a, fingerprint(dir("$element-b", withLib(coroutine(20, element)))))
        }
    }

    @Test
    fun theOtherIntegersOfItsAnnotationStillDo() {
        assertNotEquals(fingerprint(dir("a", withLib(coroutine(10, version = 1)))), fingerprint(dir("b", withLib(coroutine(10, version = 2)))))
        assertNotEquals(fingerprint(dir("c", withLib(coroutine(10, "x")))), fingerprint(dir("d", withLib(coroutine(20, "x")))))
        // a line whose constant another value names too: that value is still read
        assertNotEquals(fingerprint(dir("e", withLib(coroutine(1, version = 1, shared = true)))), fingerprint(dir("f", withLib(coroutine(1, version = 2, shared = true)))))
    }

    @Test
    fun aLineWhoseConstantAnotherAttributeMayNameStillChangesIt() {
        for (attribute in listOf("ConstantValue", "BootstrapMethods", "RuntimeInvisibleAnnotations", "RuntimeVisibleTypeAnnotations", "Unknown")) {
            val a = fingerprint(dir("$attribute-a", withLib(coroutine(10, alsoIn = attribute))))
            assertNotEquals(attribute, a, fingerprint(dir("$attribute-b", withLib(coroutine(20, alsoIn = attribute)))))
        }
    }

    @Test
    fun anAttributeThatNamesNoIntegerLeavesTheLinesOut() {
        assertEquals(fingerprint(dir("a", withLib(coroutine(10, alsoIn = "Signature")))), fingerprint(dir("b", withLib(coroutine(20, alsoIn = "Signature")))))
    }

    // kotlinc's own output, with the attributes the classes above lack: if it did not read to the end, lines and all would be hashed
    @Test
    fun realClassesLoseTheirDebugInfo() {
        // its one suspension point is on the next line, which its @DebugMetadata's l holds
        val coroutine: suspend SequenceScope<Int>.() -> Unit = { yield(1) }
        val line = Throwable().stackTrace[0].lineNumber - 1
        for (type in listOf(ToolFingerprint::class.java, javaClass, coroutine.javaClass)) {
            val code = classBytes(type)
            val read = checkNotNull(ToolFingerprint.read(code)) { type.name }
            assertTrue(type.name, read.code.size < code.size)
        }
        // smaller for its SourceFile and LineNumberTable alone: its line, moved, reads the same
        val bytes = classBytes(coroutine.javaClass)
        val at = checkNotNull(integers(bytes)[line]) { "no Integer constant of line $line" }
        val moved = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(at + 1, line + 1) }
        assertArrayEquals(checkNotNull(ToolFingerprint.read(bytes)).code, checkNotNull(ToolFingerprint.read(moved)).code)
    }

    private fun classBytes(type: Class<*>) =
        checkNotNull(type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")) { type.name }.use { it.readBytes() }

    // where each Integer constant of [code]'s constant pool starts, by its value
    private fun integers(code: ByteArray): Map<Int, Int> {
        val pool = ByteBuffer.wrap(code)
        val count = pool.getShort(8).toInt() and 0xffff
        pool.position(10)
        val starts = HashMap<Int, Int>()
        var i = 1
        while (i < count) {
            val start = pool.position()
            val size = when (val tag = pool.get().toInt()) {
                1 -> pool.short.toInt() and 0xffff
                3 -> {
                    starts[pool.getInt(start + 1)] = start
                    4
                }
                4, 9, 10, 11, 12, 17, 18 -> 4
                5, 6 -> {
                    // a long or a double takes two entries
                    i++
                    8
                }
                7, 8, 16, 19, 20 -> 2
                15 -> 3
                else -> error("constant pool tag $tag")
            }
            pool.position(pool.position() + size)
            i++
        }
        return starts
    }
}
