import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("youmo.app-convention")
    id("youmo.native-app-convention")
    id("youmo.build-metadata")
    id("youmo.engine-data")
    id("youmo.voice-data")
    id("youmo.handwriting-data")
    id("youmo.asset-packs")
    id("youmo.unit-test-resources")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
    alias(libs.plugins.roborazzi)
}

// AGP's unit test tasks and testDebugUnitTestAt29 alike
fun Test.unitTestSettings() {
    maxHeapSize = "3g"
    forkEvery = 40
    // CI's runner has 4 cores and 16 GB, each JVM up to 3 GB beside Gradle's 2
    maxParallelForks = project.findProperty("testForks")?.toString()?.let {
        it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: error("-PtestForks=<n>: a whole number, 1 or more, not '$it'")
    } ?: 2
    // -PskipExhaustive: KeyMarksEverywhereUatTest left out, half a run's time; CI runs it in key_marks.yml.
    // Not with --tests naming it: the exclusion wins, "No tests found"
    if (project.hasProperty("skipExhaustive")) filter.excludeTestsMatching("org.fcitx.fcitx5.android.uat.KeyMarksEverywhereUatTest")
    // mockk's agent loaded when the JVM starts: ByteBuddy's attach from a second process at times failed under a full
    // run's load and took every mockk test of that JVM with it; the attach settings stay for an IDE's run without it
    jvmArgs(
        "-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading",
        "-Dsun.tools.attach.attachTimeout=60000",
    )
    // a test JVM out of memory writes its heap here for the cause; a dir a run, named when it starts (the configuration
    // cache would keep one name); the newest two runs' dirs kept, so the run after an OOM does not take its dump with it
    val oom = layout.buildDirectory.dir("test-oom/$name").get().asFile
    val gcLog = project.hasProperty("testGcLog")
    var run: File? = null
    doFirst {
        val dir = File(oom, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
        dir.mkdirs()
        run = dir
        oom.listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.lastModified() }.drop(2).forEach { it.deleteRecursively() }
        oom.listFiles { f -> f.name.endsWith(".hprof") }?.forEach { it.delete() }
    }
    jvmArgs("-XX:+ExitOnOutOfMemoryError", "-XX:+HeapDumpOnOutOfMemoryError")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val dir = run ?: error("the run's dump dir not made (doFirst)")
        // -PtestGcLog: the test JVM's GC a line a collection, for a leak's trend
        listOf("-XX:HeapDumpPath=${dir.path}/") + if (gcLog) listOf("-Xlog:gc:file=${dir.path}/gc-%p.log:uptime") else emptyList()
    })
    // a run that hangs (one spun in GC with no end) stops
    timeout.set(Duration.ofMinutes(40))
    val test = this
    // the version mockk resolved to
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val agents = test.classpath.files.filter { f -> f.name.startsWith("byte-buddy-agent-") }
        val agent = agents.singleOrNull()
            ?: error("${test.name}: one byte-buddy-agent expected on the classpath, found ${agents.map { f -> f.name }} (mockk's dependency changed?)")
        listOf("-javaagent:${agent.path}")
    })
    // fixtures found from any working directory (an IDE's run too)
    systemProperty("uat.fixtures", rootProject.file("uat/fixtures").path)
    // read from disk, not the classpath: each an input, so an edit to it alone runs the tests again
    inputs.dir(rootProject.file("uat/fixtures")).withPropertyName("uatFixtures")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    // the strokes' names, the docs' settings tables, the punctuation table the app ships (assets are not on the unit
    // tests' classpath)
    inputs.files(
        rootProject.file("lexicon/stroke-order.tsv"), rootProject.file("docs/settings.md"), rootProject.file("docs/words.md"),
        file("src/main/host-assets/pkgdata/punctuation/punc.mb.zh_CN"),
    ).withPropertyName("repoFiles").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.dir(file("schemas")).withPropertyName("roomSchemas")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    // the theme maker's shared cases (ThemeMakerRulesTest): compared, or with -PupdateThemeMaker written
    systemProperty("themeMaker.testdata", rootProject.file("web/theme-maker/test/testdata").path)
    systemProperty("enginePaint.out", layout.buildDirectory.dir("w1-pixel").get().asFile.path)
    systemProperty("themeMaker.update", project.hasProperty("updateThemeMaker").toString())
    // the built-in themes' files and the scene's test data
    systemProperty("scene.src", rootProject.file("lib/keyboard-scene/src").path)
    inputs.dir(rootProject.file("lib/keyboard-scene/src/commonTest/data")).withPropertyName("sceneTestData")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    // a change there is a reason to run again, not an up-to-date skip
    inputs.dir(rootProject.file("web/theme-maker")).withPropertyName("themeMaker")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    // ThemeLookScreenshotTest's whole set for the eye: -PthemeShots=<dir>
    systemProperty("themeShots.dir", project.findProperty("themeShots")?.toString().orEmpty())
    // not read through 30 s into a test: PacksUatTest does it itself
    systemProperty("youmo.packs.verify", "off")
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
        // what a release runs like, for the instrumented performance tests: not debuggable, R8's, but the debug key and its
        // own id. Nothing is removed (benchmark-rules.pro): the tests reach members only they use
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
    testBuildType = providers.gradleProperty("youmo.testBuildType").getOrElse("debug")

    // No network permission at all. The big data is in the resource packs the user imports (AssetPacksPlugin).

    androidResources {
        // Android 13's per-app languages are xml/locales_config.xml: generated, they would offer the partial translations too
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = false
        // zh-HK/zh-MO still resolve to values-zh-rTW through Android's Hant script matching
        localeFilters += listOf("en") + Versions.translations.map { it.replace("_", "-r") }
    }

    testOptions {
        unitTests {
            // Off on purpose: a plain JVM test that reaches an android.jar stub fails loudly instead of getting false/0/null
            // back and passing for the wrong reason. Anything that needs the framework runs under Robolectric.
            isReturnDefaultValues = false
            // Also off: on, :app:test depended on the merged-assets pipeline; the resources reach Robolectric without the assets
            // instead (unit-test-resources).
            isIncludeAndroidResources = false
            // Robolectric keeps what each test's keyboard left in the app's objects till the JVM ends: the heap and forkEvery
            // are in unitTestSettings (top of this file), which testDebugUnitTestAt29 gets too
            all { it.unitTestSettings() }
        }
    }
    // fcitx's built-in data the Kotlin host reads (host-assets.md)
    sourceSets.getByName("main").assets.srcDir("src/main/host-assets")
    // the evaluation sets, for EngineEvalRunner (lib/ime-eval/run-on-device.sh)
    sourceSets.getByName("androidTest").assets.srcDir("../lib/ime-eval/data")
    // what the JVM tests and the instrumented ones both run
    sourceSets.getByName("test").kotlin.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
    // the benchmark build types into debug's field (TypingHostActivity) and has its manifest; voice clips are heard only
    // in a debug build (BuildConfig.DEBUG)
    sourceSets.getByName("benchmark") {
        kotlin.srcDir("src/debug/java")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
}

// 0.3.x's native host put fcitx's data here, untracked, so an old checkout still has them and they would be packed.
// Stop rather than filter.
listOf("src/main/assets/usr", "src/main/assets/descriptor.json").map(::file).filter { it.exists() }.let { left ->
    check(left.isEmpty()) { "0.3.x leftovers would go into the APK, delete them: ${left.joinToString()}" }
}

// TablesSettingsUatTest runs the real engine on build/generated's blobs: as inputs they are built first (a fresh
// checkout had none and the test's lookups failed quietly)
tasks.withType<Test>().matching { it.name.endsWith("UnitTest") || it.name.endsWith("UnitTestAt29") }.configureEach {
    inputs.files(tasks.named("compileEngineData"), tasks.named("compileEngineTables"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Android 10 (minSdk 29) must keep working, so every Robolectric test runs at 29 as well as 35. One SDK a JVM:
// robolectric.enabledSdks puts 29 in :app:testDebugUnitTestAt29 (both in one JVM ran out of memory and broke
// native SQLite across the sandboxes). A pin outside 29..36 then runs in neither, unreported.
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
    // one sandbox a JVM: another graphics mode in the same JVM found Robolectric's native runtime loaded into the first
    // one's class loader (UnsatisfiedLinkError). Nothing is compared at 29, so every test there draws natively.
    systemProperty("robolectric.graphicsMode", "NATIVE")
    useJUnit()
}

// The layouts the scene engine draws and the built-in themes: one copy, lib/keyboard-scene's; raw resources, so
// Robolectric reads them too (the unit tests get the app's resources, not its assets)
abstract class SceneLayoutsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val layouts: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val themes: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val raw = outputDir.get().dir("raw").asFile
        raw.deleteRecursively()
        raw.mkdirs()
        // a resource's name has no `-`: grouped-bottom.xml is scene_layout_grouped_bottom (SceneLayouts.RAW)
        layouts.forEach { it.copyTo(File(raw, "scene_layout_${it.name.replace('-', '_')}")) }
        themes.forEach { it.copyTo(File(raw, "scene_theme_${it.name.replace('-', '_')}")) }
    }
}

val sceneLayouts = tasks.register<SceneLayoutsTask>("copySceneLayouts") {
    layouts.from(rootProject.fileTree("lib/keyboard-scene/src/commonMain/layouts") { include("*.xml") })
    themes.from(rootProject.fileTree("lib/keyboard-scene/src/commonMain/themes") { include("*.xml") })
    outputDir.set(layout.buildDirectory.dir("generated/scene-layouts"))
}

androidComponents {
    // the benchmark build is timed on a device only: its unit tests would be release's again
    beforeVariants(selector().withBuildType("benchmark")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(sceneLayouts, SceneLayoutsTask::outputDir)
        // The build type's resValues point at launcher resources in the app's own resource set; copied into the androidTest
        // APK they cannot be resolved and linking fails. The test APK has no launcher, so drop them there.
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
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}
