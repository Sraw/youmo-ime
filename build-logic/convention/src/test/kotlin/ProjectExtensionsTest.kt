/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64

class ProjectExtensionsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun project(): Project = ProjectBuilder.builder().withProjectDir(folder.root).build()

    /** Runs git in [folder] as a test identity, with [env] added to its environment. */
    private fun git(vararg args: String, env: Map<String, String> = emptyMap()) {
        val command = listOf("git", "-c", "user.name=test", "-c", "user.email=test@example.com", "-c", "commit.gpgSign=false", "-c", "tag.gpgSign=false") + args
        val process = ProcessBuilder(command).directory(folder.root).redirectErrorStream(true)
            .apply { environment().putAll(env) }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "$command: $output" }
    }

    private fun assumeUnset(vararg env: String) {
        for (name in env) assumeTrue("$name is set", System.getenv(name).isNullOrBlank())
    }

    @Test
    fun aCommandThatFailsGivesTheDefault() {
        assertEquals("none", project().runCmd("git rev-parse --verify --quiet refs/tags/no-such-tag", "none"))
    }

    @Test
    fun theVersionIsTheLastAppReleaseNotADataRelease() {
        assumeUnset("BUILD_VERSION_NAME")
        git("init", "-q")
        git("commit", "-q", "--allow-empty", "-m", "a release")
        git("tag", "v0.2.0")
        git("commit", "-q", "--allow-empty", "-m", "data")
        git("tag", "engine-data-20261005-3")
        val name = project().buildVersionName
        assertTrue(name, name.startsWith("0.2.0-1-g"))
    }

    @Test
    fun withNoAppReleaseTheVersionIsTheBaseVersionMarkedUntagged() {
        assumeUnset("BUILD_VERSION_NAME")
        git("init", "-q")
        git("commit", "-q", "--allow-empty", "-m", "data")
        git("tag", "words-202610")
        assertEquals("${Versions.baseVersionName}-untagged", project().buildVersionName)
    }

    @Test
    fun upstreamsBareTagsAreNotThisAppsVersion() {
        assumeUnset("BUILD_VERSION_NAME")
        git("init", "-q")
        // as a clone that fetched upstream fcitx5-android has its tags, and the app none of its own yet
        git("commit", "-q", "--allow-empty", "-m", "upstream's release")
        git("tag", "0.1.3")
        git("commit", "-q", "--allow-empty", "-m", "this app's work")
        assertEquals("${Versions.baseVersionName}-untagged", project().buildVersionName)
    }

    @Test
    fun theVersionAtItsTagIsTheBareNumber() {
        assertEquals("0.1.0", appVersionName("v0.1.0-0-gabc1234", "0.1.0"))
        assertEquals("0.1.0-4-gabc1234", appVersionName("v0.1.0-4-gabc1234", "0.1.0"))
        // no tag: runCmd's default, and not the release v0.1.0's name
        assertEquals("0.1.0-untagged", appVersionName("", "0.1.0"))
    }

    @Test
    fun theBuildTimeIsTheCommits() {
        assumeUnset("BUILD_TIMESTAMP")
        git("init", "-q")
        git("commit", "-q", "--allow-empty", "-m", "first", env = mapOf("GIT_COMMITTER_DATE" to "@1790812800 +0000"))
        assertEquals("1790812800000", project().buildTimestamp)
    }

    @Test
    fun aWrappedKeyIsDecodedIntoAFileOnlyItsOwnerReads() {
        assumeUnset("SIGN_KEY_FILE", "SIGN_KEY_BASE64")
        val key = ByteArray(200) { it.toByte() }
        val project = project()
        // as `base64` without -w0 writes it, a line every 76 characters
        project.extensions.extraProperties["signKeyBase64"] = Base64.getMimeEncoder().encodeToString(key)
        val file = project.materializeSignKey()!!
        assertArrayEquals(key, file.readBytes())
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file.toPath()),
        )
    }

    @Test
    fun aDecodedKeyIsReusedOnlyForTheSameKey() {
        assumeUnset("SIGN_KEY_FILE", "SIGN_KEY_BASE64")
        val a = ByteArray(200) { it.toByte() }
        val b = ByteArray(200) { (it + 1).toByte() }
        val project = project()
        project.extensions.extraProperties["signKeyBase64"] = Base64.getEncoder().encodeToString(a)
        val first = project.materializeSignKey()!!
        // one daemon, many builds: the same key is decoded once
        assertEquals(first, project.materializeSignKey())
        // another key, with the same password and alias, signs with its own keystore
        project.extensions.extraProperties["signKeyBase64"] = Base64.getEncoder().encodeToString(b)
        assertArrayEquals(b, project.materializeSignKey()!!.readBytes())
        assertFalse("the old key's keystore is deleted", first.exists())
    }
}
