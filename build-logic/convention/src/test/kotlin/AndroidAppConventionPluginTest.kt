/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The build's only hold on what PRIVACY.md says each build asks for: a check that always passed would go unseen. */
class AndroidAppConventionPluginTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val voice = listOf(VoiceDataPlugin.DIMENSION to VoiceDataPlugin.FLAVOR)
    private val text = listOf(VoiceDataPlugin.DIMENSION to "text")

    private fun manifest(vararg permissions: Pair<String, String>): File = folder.newFile().apply {
        val lines = permissions.joinToString("\n") { (tag, name) -> "    <$tag android:name=\"android.permission.$name\" />" }
        writeText(
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"org.example\">\n$lines\n</manifest>\n",
        )
    }

    /** [manifest] checked as a build of [flavors] checks it: the manifest passed on. */
    private fun checked(manifest: File, flavors: List<Pair<String, String>>): File {
        val project = ProjectBuilder.builder().withProjectDir(folder.newFolder()).build()
        val task = project.tasks.register("checkPermissions", AndroidAppConventionPlugin.PermissionCheckTask::class.java).get()
        task.mergedManifest.set(manifest)
        task.allowed.set(AndroidAppConventionPlugin.PermissionCheckTask.allowedFor(flavors))
        val out = folder.root.resolve("checked/AndroidManifest.xml")
        task.checkedManifest.set(out)
        task.execute()
        return out
    }

    @Test
    fun theVoiceBuildMayAskForVibrationAndTheMicrophoneAndPassesTheManifestOnUnchanged() {
        val manifest = manifest("uses-permission" to "VIBRATE", "uses-permission" to "RECORD_AUDIO")
        assertEquals(manifest.readText(), checked(manifest, voice).readText())
    }

    @Test
    fun theTextBuildMayNotAskForTheMicrophone() {
        val vibration = manifest("uses-permission" to "VIBRATE")
        assertEquals(vibration.readText(), checked(vibration, text).readText())
        val error = assertThrows(IllegalStateException::class.java) {
            checked(manifest("uses-permission" to "VIBRATE", "uses-permission" to "RECORD_AUDIO"), text)
        }
        assertTrue(error.message, "android.permission.RECORD_AUDIO" in error.message.orEmpty())
        assertFalse(error.message, "VIBRATE" in error.message.orEmpty())
    }

    // a permission asked for on API 23 and up only is asked for all the same
    @Test
    fun anyOtherPermissionFailsEitherBuildUnderEitherTag() {
        for (flavors in listOf(voice, text)) {
            val error = assertThrows(IllegalStateException::class.java) {
                checked(manifest("uses-permission" to "INTERNET", "uses-permission-sdk-23" to "POST_NOTIFICATIONS"), flavors)
            }
            val message = error.message.orEmpty()
            assertTrue(message, "android.permission.INTERNET" in message && "android.permission.POST_NOTIFICATIONS" in message)
        }
    }
}
