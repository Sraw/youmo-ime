plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.engine-data")
    id("org.fcitx.fcitx5.android.voice-data")
    id("org.fcitx.fcitx5.android.handwriting-data")
    id("org.fcitx.fcitx5.android.unit-test-resources")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
    alias(libs.plugins.roborazzi)
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
    }

    // Everything runs on the phone, with no network permission at all. Two builds, text or voice:
    // voice adds RECORD_AUDIO and the speech recognizer with its ~180 MB of models
    // (VoiceDataPlugin), for those who want them; text, the default, has neither.
    flavorDimensions += listOf(VoiceDataPlugin.DIMENSION)
    productFlavors {
        create("text") {
            dimension = VoiceDataPlugin.DIMENSION
            isDefault = true
            buildConfigField("boolean", "VOICE", "false")
        }
        create(VoiceDataPlugin.FLAVOR) {
            dimension = VoiceDataPlugin.DIMENSION
            // the help reads a release's version less this (Const.manualUrl)
            versionNameSuffix = "-${VoiceDataPlugin.FLAVOR}"
            buildConfigField("boolean", "VOICE", "true")
        }
    }

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
            // thrashing 8 minutes first: a new JVM every 80 classes, what is kept dropped with it
            all {
                it.maxHeapSize = "3g"
                it.forkEvery = 80
                // mockk's agent attached from inside the test JVM: by default ByteBuddy starts a second
                // process to attach it, which under a full run's load at times fails and takes every
                // mockk test of that JVM with it (JvmMockKGateway); the attach's own wait, 10 s, ran
                // out under that load too (the JVM's attach listener slow to answer), given a minute
                it.jvmArgs(
                    "-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading",
                    "-Dsun.tools.attach.attachTimeout=60000",
                )
                // fixtures found from any working directory (an IDE's run too): core/Fixtures.kt
                it.systemProperty("uat.fixtures", rootProject.file("uat/fixtures").path)
                // the theme maker's shared cases (ThemeMakerRulesTest): compared, or with -PupdateThemeMaker written
                it.systemProperty("themeMaker.testdata", rootProject.file("web/theme-maker/test/testdata").path)
                it.systemProperty("themeMaker.update", project.hasProperty("updateThemeMaker").toString())
                // ThemeLookScreenshotTest's whole set of themes × keyboards × modes, for the eye: -PthemeShots=<dir>
                it.systemProperty("themeShots.dir", project.findProperty("themeShots")?.toString().orEmpty())
            }
        }
    }
    // fcitx's built-in data the Kotlin host reads (host-assets.md)
    sourceSets.getByName("main").assets.srcDir("src/main/host-assets")
    // the evaluation sets, for EngineEvalRunner (lib/ime-eval/run-on-device.sh)
    sourceSets.getByName("androidTest").assets.srcDir("../lib/ime-eval/data")
    // what the JVM tests and the instrumented ones both run: ClipboardScale's benchmark, numbers on a device too
    sourceSets.getByName("test").kotlin.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
}

// Screenshots are compared in the text flavor only: the voice build's bar has a microphone, so
// every golden would differ. Its unit tests still run every assertion, captureRoboImage a no-op
// with record, compare and verify all off (Roborazzi's plugin sets them from roborazzi.test.*;
// this doFirst, added at configuration, runs after the plugin's own).
tasks.withType<Test>().matching { it.name.startsWith("testVoice") }.configureEach {
    doFirst {
        listOf("record", "compare", "verify").forEach { systemProperty("roborazzi.test.$it", "false") }
    }
}

// TablesSettingsUatTest runs the real engine on build/generated's pinyin and table blobs. Nothing
// else of a unit test run builds them, so a fresh checkout (CI) had none and the test's lookups
// failed quietly; as inputs they are built first and key the test task's cache entry.
tasks.withType<Test>().matching { it.name.endsWith("UnitTest") }.configureEach {
    inputs.files(tasks.named("compileEngineData"), tasks.named("compileEngineTables"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

androidComponents {
    onVariants { variant ->
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
    implementation(libs.imagecropper)
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
