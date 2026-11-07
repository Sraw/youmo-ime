/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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

    @Test
    fun theModelsAreLinkedIntoTheAssetsNotCopied() {
        val sources = folder.newFolder("voice-sources")
        val archive = sources.resolve(VoiceDataPlugin.MODEL_NAME).apply { mkdirs() }
        for (name in VoiceDataPlugin.MODEL_FILES) archive.resolve(name).writeText(name)
        val ort = folder.newFolder("voice-ort")
        val ortFiles = ort.resolve(VoiceDataPlugin.ORT_DIR).apply { mkdirs() }
        for (name in VoiceDataPlugin.ORT_FILES) ortFiles.resolve(name).writeText(name)
        archive.resolve("bpe.model").writeBytes(sentencePieceModel("▁中", -1.5f))
        val hotwords = folder.newFile("hotwords.txt").apply { writeText("新 词\n") }

        val project = ProjectBuilder.builder().withProjectDir(folder.newFolder("app")).build()
        val task = project.tasks.register("copyVoiceModels", VoiceDataPlugin.CopyVoiceModels::class.java).get()
        task.model.set(sources)
        task.ort.set(ort)
        task.hotwords.set(hotwords)
        task.vad.set(folder.newFile("silero_vad.onnx").apply { writeText("vad") })
        task.outputDir.set(folder.root.resolve("voice-assets"))
        task.copy()

        val out = folder.root.resolve("voice-assets/voice")
        for (name in VoiceDataPlugin.MODEL_FILES) assertTrue(name, Files.isSameFile(archive.resolve(name).toPath(), out.resolve(name).toPath()))
        for (name in VoiceDataPlugin.ORT_FILES) assertTrue(name, Files.isSameFile(ortFiles.resolve(name).toPath(), out.resolve(name).toPath()))
        assertEquals("▁中\t-1.5\n", out.resolve("bpe.vocab").readText())
        // copied as the dict tool wrote it: its MainTest holds the spelling
        assertEquals("新 词\n", out.resolve("hotwords.txt").readText())
    }

    @Test
    fun aLocalDirectoryStandsInForTheDownloadsWithThePolishTagger() {
        val local = folder.newFolder("local")
        for (name in VoiceDataPlugin.MODEL_FILES + VoiceDataPlugin.ORT_FILES + VoiceDataPlugin.TAGGER_FILES) local.resolve(name).writeText(name)
        local.resolve("bpe.model").writeBytes(sentencePieceModel("▁中", -1.5f))
        val project = ProjectBuilder.builder().withProjectDir(folder.newFolder("app")).build()
        val task = project.tasks.register("copyVoiceModels", VoiceDataPlugin.CopyVoiceModels::class.java).get()
        task.local.set(local)
        task.hotwords.set(folder.newFile("hotwords.txt").apply { writeText("新 词\n") })
        task.vad.set(folder.newFile("silero_vad.onnx").apply { writeText("vad") })
        task.outputDir.set(folder.root.resolve("voice-assets"))
        task.copy()

        val out = folder.root.resolve("voice-assets/voice")
        for (name in VoiceDataPlugin.MODEL_FILES + VoiceDataPlugin.ORT_FILES + VoiceDataPlugin.TAGGER_FILES) {
            assertTrue(name, Files.isSameFile(local.resolve(name).toPath(), out.resolve(name).toPath()))
        }
        assertEquals("▁中\t-1.5\n", out.resolve("bpe.vocab").readText())
        assertEquals("vad", out.resolve("silero_vad.onnx").readText())
    }
}
