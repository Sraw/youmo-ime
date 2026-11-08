plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.engine-data")
    id("org.fcitx.fcitx5.android.voice-data")
    id("org.fcitx.fcitx5.android.handwriting-data")
    id("org.fcitx.fcitx5.android.asset-packs")
    id("org.fcitx.fcitx5.android.unit-test-resources")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
    alias(libs.plugins.roborazzi)
}

// What every app unit test task runs with, AGP's (testOptions.unitTests.all) and testDebugUnitTestAt29
fun Test.unitTestSettings() {
    maxHeapSize = "3g"
    forkEvery = 40
    // two test JVMs at once (-PtestForks=<n>): CI's runner has 4 cores and 16 GB, each JVM up to 3 GB
    // beside Gradle's 2; a run one at a time took 14 min there
    maxParallelForks = project.findProperty("testForks")?.toString()?.let {
        it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: error("-PtestForks=<n>: a whole number, 1 or more, not '$it'")
    } ?: 2
    // -PskipExhaustive: KeyMarksEverywhereUatTest (every key of every keyboard, every pair of mark places)
    // left out, half a run's time; CI runs it in key_marks.yml (at 35 and 29), when the keys' code changes and weekly.
    // Not with --tests naming it: the exclusion wins, "No tests found"
    if (project.hasProperty("skipExhaustive")) filter.excludeTestsMatching("org.fcitx.fcitx5.android.uat.KeyMarksEverywhereUatTest")
    // mockk's agent attached from inside the test JVM: by default ByteBuddy starts a second
    // process to attach it, which under a full run's load at times fails and takes every
    // mockk test of that JVM with it (JvmMockKGateway); the attach's own wait, 10 s, ran
    // out under that load too (the JVM's attach listener slow to answer), and so did a minute:
    // the agent loaded when the JVM starts instead, so nothing is attached (ByteBuddyAgent
    // finds it already installed); the attach settings stay for an IDE's run without it
    jvmArgs(
        "-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading",
        "-Dsun.tools.attach.attachTimeout=60000",
    )
    val test = this
    // the byte-buddy-agent on the test's own classpath: the version mockk resolved to
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val agents = test.classpath.files.filter { f -> f.name.startsWith("byte-buddy-agent-") }
        val agent = agents.singleOrNull()
            ?: error("${test.name}: one byte-buddy-agent expected on the classpath, found ${agents.map { f -> f.name }} (mockk's dependency changed?)")
        listOf("-javaagent:${agent.path}")
    })
    // fixtures found from any working directory (an IDE's run too): core/Fixtures.kt
    systemProperty("uat.fixtures", rootProject.file("uat/fixtures").path)
    // the theme maker's shared cases (ThemeMakerRulesTest): compared, or with -PupdateThemeMaker written
    systemProperty("themeMaker.testdata", rootProject.file("web/theme-maker/test/testdata").path)
    systemProperty("themeMaker.update", project.hasProperty("updateThemeMaker").toString())
    // compared with the page and its files: a change there is a reason to run again, not an up-to-date skip
    inputs.dir(rootProject.file("web/theme-maker")).withPropertyName("themeMaker")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    // ThemeLookScreenshotTest's whole set of themes × keyboards × modes, for the eye: -PthemeShots=<dir>
    systemProperty("themeShots.dir", project.findProperty("themeShots")?.toString().orEmpty())
    // the old drawing kept for 060's P3 (OldRenderBaselineTest): made with -PoldRenderBaseline=<dir>
    // (a path from the repository root; app/src/test/baseline-060 the kept one), -PoldRenderBaselineOnly=<regex>
    // some files of it; checked without
    systemProperty("oldRenderBaseline.dir", project.findProperty("oldRenderBaseline")?.toString()?.let { rootProject.file(it).path }.orEmpty())
    systemProperty("oldRenderBaseline.only", project.findProperty("oldRenderBaselineOnly")?.toString().orEmpty())
    // the keys drawn by the canvas in every harness but where a test chose (060-render-p2 S3: KeyboardHarness):
    // -PcanvasKeyboard, the tests written on ShownKey run on the new drawing
    systemProperty("youmo.canvasKeyboard", project.hasProperty("canvasKeyboard").toString())
    inputs.files(fileTree("src/test/baseline-060")).withPropertyName("oldRenderBaseline")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}

android {
    namespace = "org.fcitx.fcitx5.android"

    defaultConfig {
        applicationId = "io.github.sraw.youmo"
        // the id without the build type's suffix (.debug): UserDataOrigin's root, for importing backups
        buildConfigField("String", "APPLICATION_ID_ROOT", "\"$applicationId\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["profileableShell"] = buildProfileable.toString()
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }

    buildTypes {
        release {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "@string/app_name_debug")
        }
        // what a release runs like, for the instrumented performance tests (KeyboardPerfBaselineTest,
        // specs/testing.md): not debuggable, R8's, but the debug key, its own id, and debug's typing host.
        // Nothing is removed (benchmark-rules.pro): the tests reach members only they use
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
            resValue("string", "app_name", "@string/app_name_bench")
            proguardFile("benchmark-rules.pro")
        }
    }
    // `-Pyoumo.testBuildType=benchmark`: the instrumented tests against the benchmark build
    testBuildType = providers.gradleProperty("youmo.testBuildType").getOrElse("debug")

    // Everything runs on the phone, with no network permission at all. One APK: the big data (the
    // engine's, handwriting's, voice's) is in the resource packs the user imports (AssetPacksPlugin);
    // voice input works once the voice pack is there, the microphone asked for at its first use.

    androidResources {
        // Android 13's per-app languages are xml/locales_config.xml, AppLanguage's: generated, they
        // would offer every values-* directory, the partial translations too
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = false
        // Chinese and English only, library translations too; zh-HK/zh-MO still resolve to
        // values-zh-rTW through Android's Hant script matching
        localeFilters += listOf("en") + Versions.translations.map { it.replace("_", "-r") }
        // what the native host's CMake install left in src/main/assets (usr/: fcitx's data and
        // catalogs) and its data descriptor (descriptor.json), in checkouts that built it:
        // ignored files, never deleted
        // a non-empty list replaces aapt's default one rather than adding to it, so that is
        // repeated first (dot files, `_` dirs, backups~, VCS and OS litter)
        @Suppress("UnstableApiUsage")
        ignoreAssetsPatterns += listOf(
            "!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "<dir>_*", "!CVS", "!thumbs.db",
            "!picasa.ini", "!*~",
        ) + listOf("<dir>usr", "descriptor.json")
    }

    // the native fcitx host's libraries, no longer built: copy-fcitx5-modules and CMake copied
    // them into AGP's obj dirs, which keep .so files from older builds
    packaging {
        jniLibs {
            excludes += listOf(
                "libspell", "libimselector",
                "libFcitx5Core", "libFcitx5Utils", "libFcitx5Config", "libnative-lib",
                "libandroidengine", "libandroidfrontend", "libandroidnotification",
                "libchttrans", "libclipboard", "libfullwidth", "libpunctuation", "libquickphrase",
                "libunicode", "libscel2org5",
            ).map { "**/$it.so" }
        }
    }

    testOptions {
        unitTests {
            // Off on purpose: a plain JVM test that reaches an android.jar stub fails loudly
            // instead of getting false/0/null back and passing for the wrong reason (a Rect
            // whose fields all read 0, say). Logic belongs in :lib:ime-core; anything that
            // genuinely needs the framework runs under Robolectric.
            isReturnDefaultValues = false
            // Also left off. Turning it on made :app:test depend on the merged-assets pipeline,
            // which dragged in the native host's CMake install tasks (gone since the Kotlin host);
            // the resources reach Robolectric without the assets instead: unit-test-resources.
            isIncludeAndroidResources = false
            // Robolectric keeps what each test's keyboard left in the app's objects till the JVM
            // ends; the UAT classes, a keyboard or more a test, ran out of Gradle's 512 MB default,
            // and of 1 GB once F1 moved the redroid UI cases here (about 1200 tests, four SDK sandboxes),
            // and of 2 GB with 0.5.1's streams merged (about 1500, the screenshots of every keyboard's marks);
            // with the themes' (ThemeMakerLayoutTest's 55 measured views) 4 GB ran out too, ThemeMakerLayoutTest
            // thrashing 8 minutes first: a new JVM every 40 classes, what is kept dropped with it (80 ran
            // out again once feat/051's popups were merged; the test alone takes 8 s and little heap)
            // unitTestSettings (top of this file): testDebugUnitTestAt29 gets the same
            all { it.unitTestSettings() }
        }
    }
    // fcitx's built-in data the Kotlin host reads (host-assets.md)
    sourceSets.getByName("main").assets.srcDir("src/main/host-assets")
    // the evaluation sets, for EngineEvalRunner (lib/ime-eval/run-on-device.sh)
    sourceSets.getByName("androidTest").assets.srcDir("../lib/ime-eval/data")
    // what the JVM tests and the instrumented ones both run: ClipboardScale's benchmark, numbers on a device too
    sourceSets.getByName("test").kotlin.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
    // the benchmark build types into debug's field (TypingHostActivity) and has its manifest
    sourceSets.getByName("benchmark") {
        kotlin.srcDir("src/debug/java")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
}

// TablesSettingsUatTest runs the real engine on build/generated's pinyin and table blobs. Nothing
// else of a unit test run builds them, so a fresh checkout (CI) had none and the test's lookups
// failed quietly; as inputs they are built first and key the test task's cache entry.
tasks.withType<Test>().matching { it.name.endsWith("UnitTest") || it.name.endsWith("UnitTestAt29") }.configureEach {
    inputs.files(tasks.named("compileEngineData"), tasks.named("compileEngineTables"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Android 10 (minSdk 29) must keep working, so every Robolectric test runs at 29 as well as 35
// (robolectric.properties lists both): 0.5.1 shipped a cast and a call Android 10-12 lack, which
// the tests at 35 alone ran past. One SDK a JVM: robolectric.enabledSdks puts 29 in a task of its
// own, :app:testDebugUnitTestAt29, the same classes and settings (both SDKs in one JVM ran out
// of its 3 GB, and broke the native SQLite and android-all's zip filesystem across the sandboxes).
// (a pin outside 29..36 then runs in neither, unreported)
tasks.withType<Test>().matching { it.name.endsWith("UnitTest") }.configureEach {
    systemProperty("robolectric.enabledSdks", "30,31,32,33,34,35,36")
}
tasks.register<Test>("testDebugUnitTestAt29") {
    group = "verification"
    description = "The debug unit tests at SDK 29 (minSdk) alone"
    val base = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = base.testClassesDirs
    classpath = base.classpath
    unitTestSettings()
    systemProperty("robolectric.enabledSdks", "29")
    // one sandbox a JVM: a test of the other graphics mode in the same JVM found Robolectric's native
    // runtime loaded into the first one's class loader (UnsatisfiedLinkError: TypefaceNatives, SQLite's).
    // Nothing is compared at 29, so every test there draws natively.
    systemProperty("robolectric.graphicsMode", "NATIVE")
    useJUnit()
}

// The layouts the scene engine draws (060; KeyboardCanvasView behind internal.canvasKeyboard): one copy,
// lib/keyboard-scene's, which its tests and snapshots hold; raw resources, so Robolectric reads them too
// (the unit tests get the app's resources, not its assets: unit-test-resources)
abstract class SceneLayoutsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val layouts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val raw = outputDir.get().dir("raw").asFile
        raw.deleteRecursively()
        raw.mkdirs()
        // a resource's name has no `-`: grouped-bottom.xml is scene_layout_grouped_bottom (SceneLayouts.RAW)
        layouts.forEach { it.copyTo(File(raw, "scene_layout_${it.name.replace('-', '_')}")) }
    }
}

val sceneLayouts = tasks.register<SceneLayoutsTask>("copySceneLayouts") {
    // every layout the app draws and the fragments they include (060-render-p2 S4: a keyboard's moved there as it is drawn)
    layouts.from(rootProject.fileTree("lib/keyboard-scene/src/commonMain/layouts") { include("*.xml") })
    outputDir.set(layout.buildDirectory.dir("generated/scene-layouts"))
}

androidComponents {
    // the benchmark build is timed on a device only: its unit tests would be release's again
    beforeVariants(selector().withBuildType("benchmark")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(sceneLayouts, SceneLayoutsTask::outputDir)
        // The build type's resValue entries (app_icon, app_icon_round, app_name) point at launcher
        // resources that live in the app's own resource set. AGP copies them into the
        // androidTest APK too, where those references cannot be resolved and resource
        // linking fails. The test APK has no launcher, so just drop them there.
        variant.androidTest?.resValues?.empty()
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":lib:ime-core"))
    ksp(project(":codegen"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(testFixtures(project(":lib:ime-core")))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}
