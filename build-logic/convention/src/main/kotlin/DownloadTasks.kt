/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import com.android.build.gradle.tasks.ExternalNativeBuildJsonTask
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.withType
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject

/** Fetches [url] into [outputFile], failing unless its SHA-256 is [sha256]. */
@DisableCachingByDefault(because = "a download: fetching it again is what the cache would save")
abstract class DownloadTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val out = outputFile.get().asFile
        // put there by hand (offline), or by a build whose history is gone
        if (out.isFile && sha256Of(out) == sha256.get()) return
        // hundreds of megabytes on a first build: say what is fetched and where it goes
        logger.lifecycle("downloading ${url.get()} to $out")
        val partial = File(out.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URI(url.get()).toURL().openConnection() as HttpURLConnection
        // without these a stalled connection hangs the build for good
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            DigestInputStream(connection.inputStream, digest).use { input -> partial.outputStream().use { input.copyTo(it) } }
        } catch (e: IOException) {
            partial.delete()
            throw IllegalStateException(
                "cannot download ${url.get()} (${e.message}); offline, put it at $out (SHA-256 ${sha256.get()})",
                e,
            )
        } finally {
            connection.disconnect()
        }
        val actual = hex(digest.digest())
        if (actual != sha256.get()) {
            partial.delete()
            throw IllegalStateException("${url.get()}: SHA-256 $actual, expected ${sha256.get()}")
        }
        Files.move(partial.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
    }
}

/** Unpacks a .tar.zst with CMake ([sdkCmake]): the native build needs it anyway, unlike zstd. */
@DisableCachingByDefault(because = "unpacking is quicker than the cache would be")
abstract class ExtractTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val archive: RegularFileProperty

    /** The archive's paths to unpack, as `cmake -E tar` matches them; none for all of it. */
    @get:Input
    abstract val members: ListProperty<String>

    @get:Internal
    abstract val cmake: RegularFileProperty

    /** Whether [cmake] is the one local.properties' `cmake.dir` names, which no SDK package installs. */
    @get:Internal
    abstract val cmakeFromLocalProperties: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        // configuring the native build installs a CMake the SDK lacks
        mustRunAfter(project.tasks.withType<ExternalNativeBuildJsonTask>())
        cmakeFromLocalProperties.convention(project.localCmake.map { true }.orElse(false))
        members.convention(emptyList())
    }

    @TaskAction
    fun extract() {
        val cmake = cmake.get().asFile
        // AGP installs CMake only for the native build, which may not have run yet
        check(cmake.exists() || File(cmake.path + ".exe").exists()) { noCmake(cmake, cmakeFromLocalProperties.get()) }
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        exec.exec {
            commandLine(listOf(cmake.path, "-E", "tar", "xf", archive.get().asFile.path) + members.get())
            workingDir = dir
        }
    }
}

/**
 * What to do about a [cmake] that is not there: install the SDK's of its version, unless it is the
 * one `cmake.dir` names ([fromCmakeDir]), whose directory is named after no SDK package.
 */
internal fun noCmake(cmake: File, fromCmakeDir: Boolean): String =
    if (fromCmakeDir) {
        "no CMake at $cmake, where cmake.dir in local.properties points"
    } else {
        "no CMake at $cmake: install it with sdkmanager \"cmake;${cmake.parentFile.parentFile.name}\", or set cmake.dir in local.properties"
    }
