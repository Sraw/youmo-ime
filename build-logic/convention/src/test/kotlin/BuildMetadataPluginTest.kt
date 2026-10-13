/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
import kotlinx.serialization.json.Json
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuildMetadataPluginTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theMetadataIsWhatTheTaskWasGivenAtConfiguration() {
        val project = ProjectBuilder.builder().withProjectDir(folder.newFolder("app")).build()
        val task = project.tasks.register("generateBuildMetadata", BuildMetadataPlugin.BuildMetadataTask::class.java).get()
        val out = folder.root.resolve("build-metadata.json")
        task.versionName.set("0.2.0-1-gb6868b7")
        task.commitHash.set("b6868b7f798bf318454a21602333417cac412b47")
        task.timestamp.set("1790812800000")
        task.outputFile.set(out)
        task.execute()
        assertEquals(
            BuildMetadataPlugin.BuildMetadataTask.BuildMetadata(
                "0.2.0-1-gb6868b7", "b6868b7f798bf318454a21602333417cac412b47", "1790812800000",
            ),
            Json.decodeFromString(BuildMetadataPlugin.BuildMetadataTask.BuildMetadata.serializer(), out.readText()),
        )
    }
}
