/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.gradle.api.GradleException
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class VoiceDataPluginTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A SentencePiece model of one [piece] scored [score]: ModelProto's field 1, of piece (1) and score (2). */
    private fun sentencePieceModel(piece: String, score: Float): ByteArray {
        val text = piece.toByteArray()
        val bits = score.toRawBits()
        val message = byteArrayOf(0x0a, text.size.toByte()) + text +
            byteArrayOf(0x15) + ByteArray(4) { (bits ushr (8 * it)).toByte() }
        return byteArrayOf(0x0a, message.size.toByte()) + message
    }

    private val localFiles = VoiceDataPlugin.MODEL_FILES + VoiceDataPlugin.ORT_FILES + VoiceDataPlugin.TAGGER_FILES

    /** a local directory of every file the pack takes, each its name as its content (bpe.model a real SentencePiece model) */
    private fun localDir(name: String = "local"): File {
        val local = folder.newFolder(name)
        for (file in localFiles) local.resolve(file).writeText(file)
        local.resolve("bpe.model").writeBytes(sentencePieceModel("▁中", -1.5f))
        return local
    }

    /** [local]'s files listed as `shasum -a 256` writes them, a comment first */
    private fun listingOf(local: File, name: String = "voice-pack-v9.sha256"): File =
        folder.root.resolve(name).apply {
            writeText("# the pack's model files\n" + (localFiles + "bpe.model").joinToString("") { "${sha256Of(local.resolve(it))}  $it\n" })
        }

    private fun task(local: File?, listing: File): VoiceDataPlugin.CopyVoiceModels {
        val project = ProjectBuilder.builder().withProjectDir(folder.newFolder()).build()
        val task = project.tasks.register("copyVoiceModels", VoiceDataPlugin.CopyVoiceModels::class.java).get()
        if (local != null) task.local.set(local)
        task.expected.set(listing)
        task.hotwords.set(folder.newFile().apply { writeText("新 词\n") })
        task.vad.set(folder.newFile().apply { writeText("vad") })
        task.outputDir.set(folder.root.resolve("voice-assets"))
        return task
    }

    // the pinned release is the offline model, which the streaming recognizer cannot load
    // (sherpa-onnx ends the process), so the downloads alone build no APK until the streaming one is
    // released; the linking is held by the local directory's test below
    @Test
    fun withoutTheLocalDirectoryTheBuildSaysWhereTheModelsComeFrom() {
        val task = task(null, folder.newFile("voice-pack-v9.sha256"))
        val e = assertThrows(IllegalStateException::class.java) { task.copy() }
        assertTrue(e.message, e.message!!.contains(VoiceDataPlugin.LOCAL_PROPERTY))
    }

    @Test
    fun aLocalDirectoryStandsInForTheDownloadsWithThePolishTagger() {
        val local = localDir()
        task(local, listingOf(local)).copy()

        val out = folder.root.resolve("voice-assets/voice")
        for (name in localFiles) {
            assertTrue(name, Files.isSameFile(local.resolve(name).toPath(), out.resolve(name).toPath()))
        }
        assertEquals("▁中\t-1.5\n", out.resolve("bpe.vocab").readText())
        assertEquals("vad", out.resolve("silero_vad.onnx").readText())
        // what goes in is the pack's content (its sum): the released v2 had these files and no others
        assertEquals((localFiles + listOf("bpe.vocab", "hotwords.txt", "silero_vad.onnx")).toSet(), out.list()!!.toSet())
    }

    // shasum writes lower case, a listing written by hand may be upper: the same sums
    @Test
    fun aListingInUpperCaseIsTheSameSums() {
        val local = localDir()
        val listing = listingOf(local).apply {
            writeText(readLines().joinToString("\n") { if (it.startsWith("#")) it else it.substringBefore("  ").uppercase() + "  " + it.substringAfter("  ") })
        }
        task(local, listing).copy()
        assertTrue(folder.root.resolve("voice-assets/voice/polish-tagger.ort").isFile)
    }

    // youmo.voiceModelDir is a machine's own: pointed at another version's models (another tagger
    // than the version's), the build fails rather than pack them under this version
    @Test
    fun aDirectoryNotTheListedVersionsBuildsNoPack() {
        val rows = listOf<Pair<String, (File, File) -> Unit>>(
            "another tagger than the version's" to { local, _ -> local.resolve("polish-tagger.ort").writeText("old") },
            "a recognizer file changed" to { local, _ -> local.resolve("encoder.ort").writeText("other") },
            "the bpe model the vocabulary is read from changed" to { local, _ -> local.resolve("bpe.model").writeText("other") },
            "a file missing from the directory" to { local, _ -> local.resolve("polish-tagger.json").delete() },
            "a file the pack takes missing from the listing" to { _, listing ->
                listing.writeText(listing.readLines().filterNot { it.endsWith("tokens.txt") }.joinToString("\n"))
            },
            "a line of the listing without its file name" to { _, listing -> listing.appendText("0123abcd\n") },
        )
        val failed = rows.mapIndexedNotNull { i, (why, change) ->
            val local = localDir("local$i")
            val listing = listingOf(local, "voice-pack-v$i.sha256")
            change(local, listing)
            val e = runCatching { task(local, listing).copy() }.exceptionOrNull()
            when {
                e !is GradleException -> "$why: built ($e)"
                VoiceDataPlugin.LOCAL_PROPERTY !in e.message!! && "does not list" !in e.message!! && "is not `<sha256>" !in e.message!! -> "$why: ${e.message}"
                else -> null
            }
        }
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
    }

    // gradle/voice-pack-v<voice.version>.sha256 is what the build holds the directory to: the pack's
    // version has one (a version raised without writing it fails every voice pack build)
    @Test
    fun theVoicePacksVersionHasItsListing() {
        val root = File(System.getProperty("user.dir")).let { generateSequence(it) { d -> d.parentFile }.first { d -> d.resolve("app/packs.properties").isFile } }
        val version = root.resolve("app/packs.properties").readLines().single { it.startsWith("voice.version=") }.substringAfter('=').trim().toInt()
        val listed = root.resolve(VoiceDataPlugin.listing(version)).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim().split(Regex("\\s+"))[1] }.toSet()
        assertEquals((localFiles + VoiceDataPlugin.BPE_MODEL).toSet(), listed)
    }

    // youmo.voiceModelDir: ~/ taken as home (a home with a $ in it kept as it is), an absolute path kept
    @Test
    fun theModelDirsTildeIsHome() {
        assertEquals("/home/a/x", homed(" ~/x ", "/home/a"))
        assertEquals("/home/\$a/x", homed("~/x", "/home/\$a"))
        assertEquals("/abs/x", homed("/abs/x", "/home/a"))
    }
}
