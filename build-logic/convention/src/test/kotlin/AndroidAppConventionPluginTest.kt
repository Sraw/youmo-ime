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

    private fun manifest(vararg permissions: Pair<String, String>): File = folder.newFile().apply {
        val lines = permissions.joinToString("\n") { (tag, name) -> "    <$tag android:name=\"android.permission.$name\" />" }
        writeText(
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"org.example\">\n$lines\n</manifest>\n",
        )
    }

    /** [manifest] checked as a build checks it: the manifest passed on. */
    private fun checked(manifest: File): File {
        val project = ProjectBuilder.builder().withProjectDir(folder.newFolder()).build()
        val task = project.tasks.register("checkPermissions", AndroidAppConventionPlugin.PermissionCheckTask::class.java).get()
        task.mergedManifest.set(manifest)
        task.allowed.set(AndroidAppConventionPlugin.PermissionCheckTask.ALLOWED)
        val out = folder.root.resolve("checked/AndroidManifest.xml")
        task.checkedManifest.set(out)
        task.execute()
        return out
    }

    @Test
    fun theBuildMayAskForVibrationAndTheMicrophoneAndPassesTheManifestOnUnchanged() {
        val manifest = manifest("uses-permission" to "VIBRATE", "uses-permission" to "RECORD_AUDIO")
        assertEquals(manifest.readText(), checked(manifest).readText())
    }

    // a permission asked for on API 23 and up only is asked for all the same
    @Test
    fun anyOtherPermissionFailsTheBuildUnderEitherTag() {
        val error = assertThrows(IllegalStateException::class.java) {
            checked(manifest("uses-permission" to "VIBRATE", "uses-permission" to "INTERNET", "uses-permission-sdk-23" to "POST_NOTIFICATIONS"))
        }
        val message = error.message.orEmpty()
        assertTrue(message, "android.permission.INTERNET" in message && "android.permission.POST_NOTIFICATIONS" in message)
        assertFalse(message, "VIBRATE" in message)
    }
}
