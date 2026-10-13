/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.ApkSigningConfig
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.the
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

fun Project.runCmd(cmd: String, defaultValue: String = ""): String {
    val output = providers.exec {
        commandLine = cmd.split(" ")
        // a failure (a checkout without .git, say) gives defaultValue instead of failing the configuration
        isIgnoreExitValue = true
    }
    return if (output.result.get().exitValue == 0) {
        output.standardOutput.asText.get().trim()
    } else {
        defaultValue
    }
}

val Project.libs get() = the<LibrariesForLibs>()

val Project.assetsDir: File
    get() = file("src/main/assets").also { it.mkdirs() }

val Project.cleanTask: Task
    get() = tasks.getByName("clean")

val Project.cmakeVersion
    get() = ep("CMAKE_VERSION", "cmakeVersion") { Versions.defaultCMake }

val Project.ndkVersion
    get() = ep("NDK_VERSION", "ndkVersion") { Versions.defaultNDK }

val Project.buildToolsVersion
    get() = ep("BUILD_TOOLS_VERSION", "buildTools") { Versions.defaultBuildTools }

val Project.buildVersionName
    get() = ep("BUILD_VERSION_NAME", "buildVersionName") {
        // the app's releases are tagged v<version>; the data releases' tags (engine-data-*, words-* ...)
        // and upstream fcitx5-android's bare ones (0.1.3), which a clone that fetched it has, are not
        val described = runCmd("git describe --tags --long --match v[0-9]*", "")
        appVersionName(described, Versions.baseVersionName)
    }

/**
 * The version [described] names, as `git describe --long` gives it (`v<version>-<commits>-g<hash>`):
 * `0.1.0` at the tag, `0.1.0-5-gabc1234` past it. With no tag (a source archive, a fork without the
 * tags), [base] marked `-untagged`: bare, it would read on the About page as the release of that name.
 */
internal fun appVersionName(described: String, base: String): String {
    val (version, commits, hash) = DESCRIBED.matchEntire(described)?.destructured ?: return "$base-untagged"
    return if (commits == "0") version else "$version-$commits-g$hash"
}

private val DESCRIBED = Regex("v(.+)-(\\d+)-g([0-9a-f]+)")

val Project.buildCommitHash
    get() = ep("BUILD_COMMIT_HASH", "buildCommitHash") {
        runCmd("git rev-parse HEAD", "N/A")
    }

val Project.buildTimestamp
    get() = ep("BUILD_TIMESTAMP", "buildTimestamp") {
        // the commit's time, not the clock's: BuildConfig of an unchanged tree stays up to date
        runCmd("git log -1 --format=%ct").toLongOrNull()?.let { (it * 1000).toString() }
            ?: System.currentTimeMillis().toString()
    }

val Project.buildAbiOverride: String?
    get() = epn("BUILD_ABI", "buildABI")

val Project.signKeyBase64: String?
    get() = epn("SIGN_KEY_BASE64", "signKeyBase64")

val Project.signKeyFile: String?
    get() = epn("SIGN_KEY_FILE", "signKeyFile")

// the key it was decoded from with the file: a daemon outlives a build, and the key may change
private var signKeyTempFile: Pair<String, File>? = null

/** The release keystore: [signKeyFile], or [signKeyBase64] decoded into a file under build/. */
fun Project.materializeSignKey(): File? {
    signKeyFile?.let {
        val file = File(it)
        if (file.exists()) return file
    }
    @OptIn(ExperimentalEncodingApi::class)
    signKeyBase64?.let {
        signKeyTempFile?.let { (key, file) ->
            if (key == it && file.exists()) return file
            // another key's, or cleaned away: the old keystore goes now, not when the daemon exits
            file.delete()
        }
        val buildDir = layout.buildDirectory.asFile.get()
        buildDir.mkdirs()
        // owner-only (rw------- on POSIX), where File.createTempFile leaves it to the umask
        val file = Files.createTempFile(buildDir.toPath(), "sign-", ".ks").toFile()
        try {
            // Mime: `base64` without -w0 wraps its output in lines
            file.writeBytes(Base64.Mime.decode(it))
            // kept till the daemon exits, up to org.gradle.daemon.idletimeout after the last build: deleted as
            // the build ends, it would be gone for the daemon's next build that reuses the configuration cache
            file.deleteOnExit()
            signKeyTempFile = it to file
            return file
        } catch (e: Exception) {
            println(e.localizedMessage ?: e.stackTraceToString())
            file.delete()
        }
    }
    return null
}

val Project.signKeyPwd: String?
    get() = epn("SIGN_KEY_PWD", "signKeyPwd")

val Project.signKeyAlias: String?
    get() = epn("SIGN_KEY_ALIAS", "signKeyAlias")

fun NamedDomainObjectContainer<out ApkSigningConfig>.fromProjectEnv(project: Project): ApkSigningConfig? {
    val keyFile = project.materializeSignKey() ?: return null
    val name = "release"
    return findByName(name) ?: create(name) {
        storeFile = keyFile
        storePassword = project.signKeyPwd
        keyAlias = project.signKeyAlias
        keyPassword = project.signKeyPwd
    }
}
