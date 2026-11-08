/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.BuildConfigField
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The resource packs (dev/design/060-asset-packs.md, specs/data-and-build.md): the big data no
 * longer in the APK, made into two signed `.youmopack` files the user imports, and a release's two
 * bundles (the APK and the packs, zipped).
 *
 * - `packTextPack`, `packVoicePack` (`packs` both): `build/packs/youmo-<id>-v<version>.youmopack`,
 *   a zip of stored entries: `manifest.json` (the pack, its version and format from
 *   `app/packs.properties`, each file's size and SHA-256), `manifest.sig` (Ed25519 over the
 *   manifest's bytes), then the files at the asset paths the app reads them by. Beside it
 *   `<same name>.json`: what the release listing reads. The same inputs make the same bytes.
 * - Signed with the key at local.properties' `youmo.packKey` (or `YOUMO_PACK_KEY`), the release's;
 *   without one, with the test key `app/pack-keys/test-ed25519.pem`, which only debug builds trust.
 * - What each build trusts goes into `BuildConfig.PACK_KEYS`: a release build the keys of
 *   `app/pack-keys/release.pub` only, and fails to build without one; any other the test key's too.
 *   What each pack must be (format, oldest version) goes in as `PACK_<ID>_FORMAT`, `PACK_<ID>_MIN_VERSION`.
 * - `releaseBundles`: the release APK with the text pack (文字版), and with both (语音版), and
 *   `release-packs.json`, saying of each pack whether its content changed since the listing
 *   `-PpreviousPacks=<file>` (by its files' SHA-256s): an unchanged pack is not uploaded again.
 *   Only with the release key.
 *
 * Which asset paths each pack owns is the app's `PackKind`'s; [OWNED] repeats it, and a pack task
 * fails on a file it does not list.
 */
class AssetPacksPlugin : Plugin<Project> {

    companion object {
        const val PROPERTY_KEY = "youmo.packKey"
        const val ENV_KEY = "YOUMO_PACK_KEY"

        /** The asset paths each pack owns: PackKind's lists, repeated. */
        val OWNED = mapOf(
            "text" to listOf(
                "engine/pinyin.data", "engine/english.data", "engine/sentence-model", "engine/stroke.data",
                "engine/table/", "handwriting/",
            ),
            "voice" to listOf("voice/"),
        )

        /** The tasks whose outputs (each a directory of asset paths) make each pack. */
        private val SOURCES = mapOf(
            "text" to listOf(
                EngineDataPlugin.COMPILE_TASK, EngineDataPlugin.ENGLISH_TASK, EngineDataPlugin.MODEL_TASK,
                EngineDataPlugin.TABLES_TASK, HandwritingDataPlugin.EXTRACT_TASK,
            ),
            "voice" to listOf(VoiceDataPlugin.COPY_TASK),
        )

        // a raw Ed25519 key as X.509's SubjectPublicKeyInfo: this prefix, then the 32 bytes
        private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        // entries' time: the same inputs, the same bytes
        private const val ZIP_TIME = 315532800000L

        fun publicKey(raw: ByteArray) = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))

        fun privateKey(pem: String): PrivateKey {
            val body = pem.lines().filter { !it.startsWith("-----") }.joinToString("")
            return KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
        }

        /** The raw public keys of a `.pub` file: one base64 a line, `#` comments. */
        fun readKeys(file: File): List<ByteArray> = if (!file.isFile) emptyList() else
            file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map {
                Base64.getDecoder().decode(it).also { k -> require(k.size == 32) { "$file: a key of ${k.size} bytes, not 32" } }
            }

        fun sign(key: PrivateKey, message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
            initSign(key)
            update(message)
            sign()
        }

        fun verifies(raw: ByteArray, message: ByteArray, signature: ByteArray) = Signature.getInstance("Ed25519").run {
            initVerify(publicKey(raw))
            update(message)
            verify(signature)
        }

        /** Which of [keys] [key] is: its index, or -1 (a probe signed and checked). */
        fun indexOfKey(key: PrivateKey, keys: List<ByteArray>): Int {
            val probe = "youmo pack key".toByteArray()
            val signature = sign(key, probe)
            return keys.indexOfFirst { verifies(it, probe, signature) }
        }

        fun keyName(raw: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(raw)).take(16)

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun stored(zip: ZipOutputStream, name: String, size: Long, crc: Long, write: (ZipOutputStream) -> Unit) {
            val entry = ZipEntry(name)
            entry.method = ZipEntry.STORED
            entry.size = size
            entry.compressedSize = size
            entry.crc = crc
            entry.time = ZIP_TIME
            zip.putNextEntry(entry)
            write(zip)
            zip.closeEntry()
        }

        fun storedBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) =
            stored(zip, name, bytes.size.toLong(), CRC32().apply { update(bytes) }.value) { it.write(bytes) }

        fun storedFile(zip: ZipOutputStream, name: String, file: File, crc: Long) =
            stored(zip, name, file.length(), crc) { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }

        /** A file's CRC-32 and SHA-256, one read. */
        fun sums(file: File): Pair<Long, String> {
            val crc = CRC32()
            val sha = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 16)
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                    sha.update(buffer, 0, n)
                }
            }
            return crc.value to hex(sha.digest())
        }

        /**
         * Whether [now] changed since [old] (the last release's listing of it; none: new): its files'
         * content or its version. Content changed under the same version fails: the app could not tell
         * the two packs apart (`PackManifest.sameAs` goes by both).
         */
        fun changed(old: Listing?, now: Listing): Boolean {
            if (old == null) return true
            if (old.contentSha256 != now.contentSha256 && old.version == now.version) {
                throw GradleException("the ${now.id} pack's content changed but its version is still ${now.version}: raise ${now.id}.version in app/packs.properties")
            }
            return old.contentSha256 != now.contentSha256 || old.version != now.version
        }

        fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    override fun apply(target: Project) {
        val root = target.rootProject.layout.projectDirectory
        val keysDir = target.layout.projectDirectory.dir("pack-keys")
        val props = Properties().apply { target.file("packs.properties").inputStream().use(::load) }
        fun prop(name: String) = props.getProperty(name)?.trim()?.toIntOrNull()
            ?: throw GradleException("app/packs.properties: no whole number $name")

        val local = target.providers.fileContents(root.file("local.properties")).asText.map { text ->
            Properties().apply { load(text.reader()) }.getProperty(PROPERTY_KEY).orEmpty()
        }.orElse("")
        val keyPath = target.providers.environmentVariable(ENV_KEY)
            .orElse(target.providers.gradleProperty(PROPERTY_KEY))
            .orElse(local)
            .map { it.trim().replaceFirst(Regex("^~(?=/)"), System.getProperty("user.home")) }
        val testKey = keysDir.file("test-ed25519.pem")

        val packs = OWNED.keys.associateWith { id ->
            target.tasks.register<PackTask>("pack${id.replaceFirstChar(Char::uppercase)}Pack") {
                group = "build"
                description = "The $id resource pack, signed"
                packId.set(id)
                version.set(prop("$id.version"))
                format.set(prop("$id.format"))
                owned.set(OWNED.getValue(id))
                sources.from(SOURCES.getValue(id).map { target.tasks.named(it) })
                key.set(keyPath.map { p -> if (p.isEmpty()) "" else p })
                this.testKey.set(testKey)
                releaseKeys.set(keysDir.file("release.pub"))
                outputDir.set(target.layout.buildDirectory.dir("packs"))
            }
        }
        target.tasks.register("packs") {
            group = "build"
            description = "Both resource packs, signed"
            dependsOn(packs.values)
        }

        val components = target.extensions.getByType<ApplicationAndroidComponentsExtension>()
        val release = readKeys(keysDir.file("release.pub").asFile)
        val test = readKeys(keysDir.file("test.pub").asFile)
        components.onVariants { variant ->
            val trusted = if (variant.buildType == "release") release else release + test
            val fields = variant.buildConfigFields ?: return@onVariants
            fields.put(
                "PACK_KEYS",
                BuildConfigField("String", jsonString(trusted.joinToString(",") { Base64.getEncoder().encodeToString(it) }), "the Ed25519 keys whose packs this build takes"),
            )
            for (id in OWNED.keys) {
                val up = id.uppercase()
                fields.put("PACK_${up}_FORMAT", BuildConfigField("int", prop("$id.format").toString(), "the $id pack's data format this build reads"))
                fields.put("PACK_${up}_MIN_VERSION", BuildConfigField("int", prop("$id.minVersion").toString(), "the oldest $id pack this build takes"))
            }
            if (variant.buildType == "release" && release.isEmpty()) {
                // a release that trusted no key would take no pack, and one that trusted the test key anyone's
                val check = target.tasks.register("checkPackKeys${variant.name.replaceFirstChar(Char::uppercase)}") {
                    doLast { throw GradleException("app/pack-keys/release.pub has no key: a release build must trust the release key (never the test key)") }
                }
                target.tasks.matching { it.name == "pre${variant.name.replaceFirstChar(Char::uppercase)}Build" }.configureEach { dependsOn(check) }
            }
            if (variant.name == "release") {
                target.tasks.register<ReleaseBundlesTask>("releaseBundles") {
                    group = "build"
                    description = "The release APK with the text pack, and with both packs; which packs changed"
                    apkDir.set(variant.artifacts.get(SingleArtifact.APK))
                    loader.set(variant.artifacts.getBuiltArtifactsLoader())
                    textPack.set(packs.getValue("text").flatMap { it.outputDir })
                    voicePack.set(packs.getValue("voice").flatMap { it.outputDir })
                    dependsOn(packs.values)
                    versionName.set(variant.outputs.first().versionName)
                    previous.set(target.providers.gradleProperty("previousPacks").map { root.file(it) })
                    releaseKeys.set(keysDir.file("release.pub"))
                    outputDir.set(target.layout.buildDirectory.dir("release-bundles"))
                }
            }
        }
    }

    /** One pack, signed (see the plugin). */
    @DisableCachingByDefault(because = "hundreds of MB, quicker made than fetched")
    abstract class PackTask : DefaultTask() {
        @get:Input
        abstract val packId: Property<String>

        @get:Input
        abstract val version: Property<Int>

        @get:Input
        abstract val format: Property<Int>

        @get:Input
        abstract val owned: org.gradle.api.provider.ListProperty<String>

        /** The producing tasks' output directories, each of asset paths. */
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sources: ConfigurableFileCollection

        /** The release key's path, or empty: [testKey] then. Its path, not its content, is an input: never copied into the build's caches. */
        @get:Input
        abstract val key: Property<String>

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val testKey: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val releaseKeys: RegularFileProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun pack() {
            val id = packId.get()
            val prefixes = owned.get()
            val files = sources.files.flatMap { dir ->
                dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath to it }.toList()
            }.sortedBy { it.first }
            check(files.isNotEmpty()) { "the $id pack has no files" }
            check(files.map { it.first }.toSet().size == files.size) { "the $id pack: a path made twice" }
            files.forEach { (path, _) -> check(prefixes.any { path.startsWith(it) }) { "$path is not the $id pack's (AssetPacksPlugin.OWNED, PackKind)" } }

            val keyPath = key.get()
            val releaseKey = keyPath.isNotEmpty()
            val keyFile = if (releaseKey) File(keyPath) else testKey.get().asFile
            check(keyFile.isFile) { "no pack signing key at $keyFile (${PROPERTY_KEY} in local.properties, or $ENV_KEY)" }
            val privateKey = privateKey(keyFile.readText())
            val publicRaw = if (releaseKey) {
                val trusted = readKeys(releaseKeys.get().asFile)
                val i = indexOfKey(privateKey, trusted)
                check(i >= 0) { "the key at ${PROPERTY_KEY} is not one of app/pack-keys/release.pub's: release builds would refuse its packs" }
                trusted[i]
            } else {
                val trusted = readKeys(testKey.get().asFile.resolveSibling("test.pub"))
                trusted[indexOfKey(privateKey, trusted).also { check(it >= 0) { "test-ed25519.pem is not test.pub's" } }]
            }
            if (!releaseKey) logger.warn("the $id pack is signed with the test key: debug builds take it, release builds do not")

            val sums = files.map { (path, file) -> Triple(path, file, sums(file)) }
            val entries = sums.joinToString(",\n    ") { (path, file, s) ->
                "{\"path\": ${jsonString(path)}, \"size\": ${file.length()}, \"sha256\": \"${s.second}\"}"
            }
            val manifest = """
                |{
                |  "pack": ${jsonString(id)},
                |  "version": ${version.get()},
                |  "format": ${format.get()},
                |  "key": "${keyName(publicRaw)}",
                |  "files": [
                |    $entries
                |  ]
                |}
                |""".trimMargin().toByteArray()
            val signature = sign(privateKey, manifest)
            check(verifies(publicRaw, manifest, signature))

            val out = outputDir.get().asFile
            out.mkdirs()
            val name = "youmo-$id-v${version.get()}"
            out.listFiles()?.filter { it.name.startsWith("youmo-$id-") }?.forEach { it.delete() }
            val pack = out.resolve("$name.youmopack")
            ZipOutputStream(pack.outputStream().buffered(1 shl 16)).use { zip ->
                storedBytes(zip, "manifest.json", manifest)
                storedBytes(zip, "manifest.sig", signature)
                sums.forEach { (path, file, s) -> storedFile(zip, path, file, s.first) }
            }
            val content = MessageDigest.getInstance("SHA-256").run {
                sums.forEach { (path, file, s) -> update("$path\t${file.length()}\t${s.second}\n".toByteArray()) }
                hex(digest())
            }
            out.resolve("$name.json").writeText(
                """
                |{"pack": ${jsonString(id)}, "version": ${version.get()}, "format": ${format.get()},
                | "file": ${jsonString(pack.name)}, "size": ${pack.length()}, "sha256": "${sums(pack).second}",
                | "contentSha256": "$content", "key": "${keyName(publicRaw)}", "releaseKey": $releaseKey}
                |""".trimMargin(),
            )
        }
    }

    /** The release's two bundles and its pack listing (see the plugin). */
    @DisableCachingByDefault(because = "copies")
    abstract class ReleaseBundlesTask : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val apkDir: DirectoryProperty

        @get:Internal
        abstract val loader: Property<com.android.build.api.variant.BuiltArtifactsLoader>

        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val textPack: DirectoryProperty

        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val voicePack: DirectoryProperty

        @get:Input
        abstract val versionName: Property<String>

        @get:Optional
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val previous: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val releaseKeys: RegularFileProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun bundle() {
            val apks = checkNotNull(loader.get().load(apkDir.get())) { "no release APK" }.elements.map { File(it.outputFile) }
            val apk = apks.singleOrNull() ?: throw GradleException("one APK expected (-PbuildABI=arm64-v8a), found ${apks.map { it.name }}")
            val packs = listOf("text" to textPack, "voice" to voicePack).map { (id, dir) ->
                val listing = dir.get().asFile.listFiles().orEmpty().single { it.name.startsWith("youmo-$id-") && it.name.endsWith(".json") }
                Listing.read(listing).also { check(it.releaseKey) { "the $id pack is signed with the test key: a release ships the release key's" } }
            }
            val out = outputDir.get().asFile
            out.deleteRecursively()
            out.mkdirs()
            val version = versionName.get()
            val apkName = "youmo-$version.apk"
            apk.copyTo(out.resolve(apkName))
            val text = packs[0]
            val voice = packs[1]
            bundle(out.resolve("youmo-$version-text.zip"), apk, apkName, listOf(text), README_TEXT)
            bundle(out.resolve("youmo-$version-voice.zip"), apk, apkName, listOf(text, voice), README_VOICE)
            packs.forEach { it.file.copyTo(out.resolve(it.file.name)) }
            val before = previous.orNull?.asFile?.let(Listing::readAll).orEmpty()
            val lines = packs.map { p ->
                val changed = changed(before[p.id], p)
                "  {\"pack\": ${jsonString(p.id)}, \"version\": ${p.version}, \"format\": ${p.format}, \"file\": ${jsonString(p.file.name)}, " +
                    "\"size\": ${p.file.length()}, \"sha256\": \"${p.sha256}\", \"contentSha256\": \"${p.contentSha256}\", \"changed\": $changed}"
            }
            out.resolve("release-packs.json").writeText("{\"app\": ${jsonString(version)}, \"packs\": [\n${lines.joinToString(",\n")}\n]}\n")
            logger.lifecycle(
                packs.joinToString("\n") { p ->
                    val old = before[p.id]
                    "${p.id} v${p.version}: " + when {
                        old == null -> "new (no previous listing for it)"
                        old.contentSha256 == p.contentSha256 && old.version == p.version -> "unchanged since v${old.version}: not uploaded again"
                        else -> "changed (v${old.version} -> v${p.version}): upload"
                    }
                },
            )
        }

        private fun bundle(zip: File, apk: File, apkName: String, packs: List<Listing>, readme: String) {
            ZipOutputStream(zip.outputStream().buffered(1 shl 16)).use { out ->
                storedBytes(out, "安装说明.txt", readme.toByteArray())
                storedFile(out, apkName, apk, sums(apk).first)
                packs.forEach { storedFile(out, it.file.name, it.file, sums(it.file).first) }
            }
        }
    }

    /** A pack's listing, the `.json` beside it, or a line of a release's `release-packs.json`. */
    class Listing(val id: String, val version: Int, val format: Int, val file: File, val sha256: String, val contentSha256: String, val releaseKey: Boolean) {
        companion object {
            @Suppress("UNCHECKED_CAST")
            private fun parse(text: String) = groovy.json.JsonSlurper().parseText(text)

            private fun of(m: Map<*, *>, dir: File) = Listing(
                m["pack"] as String, (m["version"] as Number).toInt(), (m["format"] as Number).toInt(),
                dir.resolve(m["file"] as String), m["sha256"] as String, m["contentSha256"] as String, m["releaseKey"] as? Boolean ?: true,
            )

            fun read(file: File) = of(parse(file.readText()) as Map<*, *>, file.parentFile)

            /** A release's listing, by pack. */
            fun readAll(file: File): Map<String, Listing> =
                ((parse(file.readText()) as Map<*, *>)["packs"] as List<*>).map { of(it as Map<*, *>, file.parentFile) }.associateBy { it.id }
        }
    }
}

private val README_TEXT = """
    幽默输入法 文字版

    1. 安装 APK。
    2. 打开幽默输入法的设置 → 资源包 → 导入，选这个压缩包解开后的 youmo-text-*.youmopack。
    3. 导入完成后，那个 .youmopack 文件可以删掉。

    以后升级：大多数时候只需要下载新的 APK 覆盖安装；资源包有新版本时，发布说明会写明。
""".trimIndent() + "\n"

private val README_VOICE = """
    幽默输入法 语音版

    1. 安装 APK。
    2. 打开幽默输入法的设置 → 资源包 → 导入，同时选这个压缩包解开后的 youmo-text-*.youmopack 和 youmo-voice-*.youmopack（可以多选）。
    3. 导入完成后，那两个 .youmopack 文件可以删掉。第一次用语音输入时，系统会问是否允许录音。

    以后升级：大多数时候只需要下载新的 APK 覆盖安装；资源包有新版本时，发布说明会写明。
""".trimIndent() + "\n"
