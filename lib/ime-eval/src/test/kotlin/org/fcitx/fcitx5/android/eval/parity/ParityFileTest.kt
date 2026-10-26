/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval.parity

import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.engine.EngineBehaviour
import org.fcitx.fcitx5.android.eval.runCli
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ParityFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // a checkout with an engine and the data plugin, as Provenance reads them
    private fun checkout(release: String = "engine-data-1"): File = tmp.newFolder().also { root ->
        Provenance.CONFIG_DIRS.forEach { File(root, "$it/pkgdata/punc").apply { parentFile.mkdirs() }.writeText("，") }
        File(root, Provenance.DATA_PLUGIN).apply { parentFile.mkdirs() }.writeText(
            """
            private const val LM_RELEASE = "$release"
            private const val WORDS_RELEASE = "words-1"
            """.trimIndent(),
        )
    }

    private val sequences = SequenceGenerator(SequenceGenerator.DEFAULT_IMS, 1).generate(20)

    @Test
    fun sequencesAndOutputsRoundTrip() {
        val root = checkout()
        val f = tmp.newFile()
        val header = ParityFiles.writeSequences(f, Provenance.header(root, "random", SequenceGenerator.DEFAULT_IMS, 1), sequences)
        val (read, back) = ParityFiles.sequences(f)
        assertEquals(header, read)
        assertEquals(sequences, back)
        assertEquals(SequenceGenerator.DEFAULT_IMS, read.ims)
        assertEquals("""{"lm":"engine-data-1","words":"words-1"}""", read.fields[Header.DATA].toString())

        val out = tmp.newFile()
        val outputs = listOf(Output("r1-1", listOf(State("你好", 2 to 2, "", listOf("啊")), State("", 0 to 0, "", listOf(), busy = true))), Output("r1-2", listOf(), "Blocked: x"))
        ParityFiles.writeOutputs(out, outputHeader(read, "jvm"), outputs)
        val (oh, ob) = ParityFiles.outputs(out)
        assertEquals("jvm", oh[Header.HOST])
        assertEquals(outputs, ob)
        assertNull(read.mismatch(oh))
    }

    @Test
    fun aSequenceFileEditedAfterItsHeaderIsRefused() {
        val f = tmp.newFile()
        ParityFiles.writeSequences(f, Provenance.header(checkout(), "random", SequenceGenerator.DEFAULT_IMS), sequences)
        f.writeText(f.readLines().dropLast(1).joinToString("\n"))
        assertThrows(IllegalStateException::class.java) { ParityFiles.sequences(f) }
    }

    @Test
    fun theBehaviourTheConfigAndTheDataMakeTheHeader() {
        val root = checkout()
        val h = Provenance.header(root, "random", SequenceGenerator.DEFAULT_IMS)
        assertNull(Provenance.stale(h, root))
        // the engine's sources are not hashed: a comment, a rename change nothing
        File(root, "lib/ime-core/src/main/kotlin/A.kt").apply { parentFile.mkdirs() }.writeText("// renamed")
        assertNull(Provenance.stale(h, root))
        // another behaviour version is another engine, whatever the version now
        assertNotEquals(h.fields[Header.ENGINE], Provenance.engine(root, behaviour = EngineBehaviour.VERSION + 1))
        File(root, "${Provenance.CONFIG_DIRS[0]}/pkgdata/punc").writeText("。")
        assertTrue(Provenance.stale(h, root)!!.startsWith("engine"))
        val other = checkout("engine-data-2")
        assertEquals(h.fields[Header.ENGINE], Provenance.header(other, "random", h.ims).fields[Header.ENGINE])
        assertTrue(Provenance.stale(h, other)!!.startsWith("data"))
    }

    @Test
    fun whatTheAppDoesNotShipIsNotTheEngine() {
        // F3813: the native install's leftovers (ignored, kept out of the APK) are in some checkouts only
        val root = checkout()
        val h = Provenance.header(root, "random", SequenceGenerator.DEFAULT_IMS)
        File(root, "app/src/main/assets/usr/share/fcitx5/punc").apply { parentFile.mkdirs() }.writeText("x")
        File(root, "app/src/main/assets/descriptor.json").writeText("{}")
        File(root, "lexicon/tools/latin.py").apply { parentFile.mkdirs() }.writeText("# a script")
        // F4240: of lexicon/, only what the data plugin reads; add.tsv comes through the words-* release
        listOf("README.md", "add.tsv", "reject.tsv").forEach { File(root, "lexicon/$it").writeText("x") }
        assertNull(Provenance.stale(h, root))
        // F3858: lexicon/'s corrections are compiled into the engine data
        File(root, "lexicon/readings.tsv").writeText("行\txing")
        assertTrue(Provenance.stale(h, root)!!.startsWith("engine"))
    }

    @Test
    fun outputsOfOtherSequencesOrInputMethodsDoNotCompare() {
        val root = checkout()
        val a = ParityFiles.writeSequences(tmp.newFile(), Provenance.header(root, "random", SequenceGenerator.DEFAULT_IMS), sequences)
        val b = ParityFiles.writeSequences(tmp.newFile(), Provenance.header(root, "random", SequenceGenerator.DEFAULT_IMS), sequences.drop(1))
        assertTrue(a.mismatch(b)!!.startsWith(Header.SEQUENCES))
        val c = ParityFiles.writeSequences(tmp.newFile(), Provenance.header(root, "random", listOf("engine-pinyin")), sequences)
        assertTrue(a.mismatch(c)!!.startsWith(Header.IMS))
        // the host and what else made an output are not compared
        assertNull(a.mismatch(outputHeader(a, "native", "commit" to "abc")))
    }

    @Test
    fun aRecordingOfAnotherEngineIsComparedAndLabelled() {
        val root = checkout()
        val seqs = File(tmp.root, "s.jsonl")
        val out = StringBuilder()
        val err = StringBuilder()
        assertEquals(0, runCli(arrayOf("parity-generate", seqs.path, "--seed", "3", "--count", "5", "--repo", root.path), out, err))
        assertEquals(0, runCli(arrayOf("parity-check", seqs.path, "--repo", root.path), out, err))
        assertFalse(Provenance.ENGINE_CHANGED in out)
        val (header, _) = ParityFiles.sequences(seqs)
        val dev = File(tmp.root, "d.jsonl").also { ParityFiles.writeOutputs(it, outputHeader(header, "native"), listOf()) }
        val jvm = arrayOf("parity-compare", seqs.path, dev.path, dev.path, "--jvm", "--model", "none", "--epsilon", "1e-4", "--repo", root.path)
        assertEquals(0, runCli(jvm, out, err))
        assertTrue(out.toString(), "same 0" in out)

        File(root, "${Provenance.CONFIG_DIRS[0]}/pkgdata/punc").writeText("。")
        out.clear()
        assertEquals(0, runCli(arrayOf("parity-check", seqs.path, "--repo", root.path), out, err))
        assertTrue(out.toString(), Provenance.ENGINE_CHANGED in out)
        // device against device: whatever engine wrote the sequences, a plain pass
        assertEquals(0, runCli(arrayOf("parity-compare", seqs.path, dev.path, dev.path, "--repo", root.path), out, err))
        // the JVM against a recording of another engine: compared, labelled, its own exit code
        out.clear()
        assertEquals(ENGINE_CHANGED_EXIT, runCli(jvm, out, err))
        assertTrue(out.toString(), out.startsWith(Provenance.ENGINE_CHANGED) && "same 0" in out)
        // --jvm needs its model and ε
        assertEquals(2, runCli(arrayOf("parity-compare", seqs.path, dev.path, dev.path, "--jvm"), out, StringBuilder()))
    }

    // F3990: a JVM output says the engine it ran on; compare holds that to the recording, not the checkout comparing
    @Test
    fun aJvmReplayIsHeldToTheEngineItRanOnNotTheComparingCheckouts() {
        val root = checkout()
        val seqs = File(tmp.root, "r.jsonl")
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("parity-generate", seqs.path, "--seed", "3", "--count", "5", "--repo", root.path), out, StringBuilder()))
        val (header, _) = ParityFiles.sequences(seqs)
        val dev = File(tmp.root, "rd.jsonl").also { ParityFiles.writeOutputs(it, outputHeader(header, "native"), listOf()) }
        fun replay(ranOn: File) = File(tmp.root, "rj.jsonl").also {
            val ran = JsonObject(Provenance.fingerprint(ranOn))
            ParityFiles.writeOutputs(it, outputHeader(header, "jvm").with(Header.ENGINE_RUN to ran), listOf())
        }
        val jvm = { j: File -> arrayOf("parity-compare", seqs.path, dev.path, j.path, "--jvm", "--model", "none", "--epsilon", "1e-4", "--repo", root.path) }
        // replayed on the recording's engine: a pass
        assertEquals(0, runCli(jvm(replay(root)), out, StringBuilder()))
        // replayed on another (an older checkout's output left behind), compared from this one: labelled
        val older = checkout().also { File(it, "${Provenance.CONFIG_DIRS[0]}/pkgdata/punc").writeText("。") }
        out.clear()
        assertEquals(ENGINE_CHANGED_EXIT, runCli(jvm(replay(older)), out, StringBuilder()))
        assertTrue(out.toString(), out.startsWith(Provenance.ENGINE_CHANGED) && "the replay ran" in out)
        // F4283: in shards, a stale one after a fresh one is labelled (not only the first is read)
        val fresh = File(tmp.root, "rj0.jsonl").also { replay(root).copyTo(it, overwrite = true) }
        val stale = replay(older)
        assertEquals(ENGINE_CHANGED_EXIT, runCli(jvm(File("${fresh.path},${stale.path}")), StringBuilder(), StringBuilder()))
        // F4280: the checkout moved on after a replay on the recording's engine: still a pass, the device
        // recording (no engine-run) is not held to the checkout
        val ranHere = File(tmp.root, "rj1.jsonl").also { replay(root).copyTo(it, overwrite = true) }
        File(root, "${Provenance.CONFIG_DIRS[0]}/pkgdata/punc").writeText("。")
        assertEquals(0, runCli(jvm(ranHere), StringBuilder(), StringBuilder()))
    }

    @Test
    fun outputsFromAnotherBuildsSequencesAreRefused() {
        val root = checkout()
        val seqs = File(tmp.root, "s.jsonl")
        val out = StringBuilder()
        val err = StringBuilder()
        assertEquals(0, runCli(arrayOf("parity-generate", seqs.path, "--seed", "3", "--count", "5", "--repo", root.path), out, err))
        val (header, _) = ParityFiles.sequences(seqs)
        val a = File(tmp.root, "a.jsonl").also { ParityFiles.writeOutputs(it, outputHeader(header, "native"), listOf()) }
        File(root, "${Provenance.CONFIG_DIRS[0]}/pkgdata/punc").writeText("。")
        val b = File(tmp.root, "b.jsonl").also { ParityFiles.writeOutputs(it, outputHeader(Provenance.rehead(header, root), "kotlin"), listOf()) }
        assertEquals(1, runCli(arrayOf("parity-compare", seqs.path, a.path, b.path, "--repo", root.path), out, err))
        assertNotNull(Regex("refused: .*engine differs").find(err))
    }

    @Test
    fun reheadGivesTheCheckoutsEngineAndKeepsTheOldAndTheSequences() {
        val root = checkout()
        val seqs = File(tmp.root, "s.jsonl")
        assertEquals(0, runCli(arrayOf("parity-generate", seqs.path, "--seed", "3", "--count", "5", "--repo", root.path), StringBuilder(), StringBuilder()))
        val (old, sequences) = ParityFiles.sequences(seqs)
        val body = seqs.readLines().drop(1)
        File(root, "${Provenance.CONFIG_DIRS[0]}/pkgdata/punc").writeText("。")
        assertEquals(0, runCli(arrayOf("parity-rehead", seqs.path, "--repo", root.path), StringBuilder(), StringBuilder()))
        val (now, again) = ParityFiles.sequences(seqs)
        assertNull(Provenance.stale(now, root))
        assertEquals(old.fields[Header.ENGINE], (now.fields[Header.ENGINE_WAS] as JsonObject)[Header.ENGINE])
        assertEquals(body, seqs.readLines().drop(1))
        assertEquals(sequences, again)
        assertNull(old.mismatch(now.with(Header.ENGINE to old.fields.getValue(Header.ENGINE))))
        // F3943: a second rehead keeps the recording's own engine, not the first rehead's
        File(root, "${Provenance.CONFIG_DIRS[0]}/pkgdata/punc").writeText("！")
        assertEquals(0, runCli(arrayOf("parity-rehead", seqs.path, "--repo", root.path), StringBuilder(), StringBuilder()))
        val twice = ParityFiles.sequences(seqs).first
        assertNull(Provenance.stale(twice, root))
        assertEquals(now.fields[Header.ENGINE_WAS], twice.fields[Header.ENGINE_WAS])
    }

    @Test
    fun aStepChecksItsArgument() {
        assertThrows(IllegalArgumentException::class.java) { Step("type") }
        assertThrows(IllegalArgumentException::class.java) { Step("space", "1") }
        assertThrows(IllegalArgumentException::class.java) { Step("swipe", "x") }
        assertEquals("••", State.mask("ab"))
        assertEquals("•", State.mask("😀"))
    }
}
