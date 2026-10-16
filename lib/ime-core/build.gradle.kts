/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// A plain JVM library, deliberately NOT an android-library: the compiler then enforces that
// nothing here can reach for the Android framework. Logic that lands in this module is
// testable in milliseconds, with no emulator, no Robolectric and no Android SDK.
plugins {
    `java-library`
    // FakeEditor lives in testFixtures so :app's contract test can hold it against the real
    // BaseInputConnection; the handwriting test data so :lib:ime-eval's tests read the same words
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.animalsniffer)
    alias(libs.plugins.kover)
}

// The bytecode target alone does not stop code from calling APIs newer than Android has: this
// module compiles against the build's JDK, so e.g. Kotlin's `list.removeFirst()` would bind to
// JDK 21's `List.removeFirst`, pass every JVM test, then throw NoSuchMethodError on Android
// before 15. Two guards:
//  - compile against the JDK 11 API rather than the host JDK's (`release`);
//  - check the result against Android's actual API at minSdk (animal-sniffer, below). Android
//    lint does not look inside a plain JVM module, so nothing else would catch it.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xjdk-release=11")
    }
}

animalsniffer {
    // tests run on the JVM only; checking them against Android would be noise
    sourceSets = listOf(project.sourceSets.main.get())
}

dependencies {
    // minSdk 23 plus the java.* APIs that the app's core library desugaring (desugar_jdk_libs
    // 2.x) backports; keep in step with minSdk and that dependency
    signature(variantOf(libs.gummyBears.api23) { classifier("coreLib2"); artifactType("signature") })
    testImplementation(libs.junit)
}

// AndroidEngineHeaderTest reads the addon's headers, outside this module: handed their paths, it
// finds them wherever it runs from; inputs relative to the build, an edit to one alone runs it
// again, and its result is still taken from the build cache in another checkout
abstract class AndroidEngineHeaders : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headers: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = headers.files.map { "-Dheader.${it.name}=${it.absolutePath}" }
}

tasks.withType<Test>().configureEach {
    // MisreadingsTest checks the list the app build compiles: an edit to it alone runs it again
    inputs.file(rootProject.file("lexicon/misreadings.tsv")).withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(objects.newInstance<AndroidEngineHeaders>().apply {
        headers.from(rootProject.files("app/src/main/cpp/androidengine/androidengine.h", "app/src/main/cpp/androidengine/androidengine_public.h"))
    })
    testLogging {
        events("failed")
    }
}

// Coverage floors. This module holds the logic that is hard to get right and easy to break
// quietly -- cursor prediction, editing, gestures -- and most of it is fully tested; the floors
// keep it that way. `./gradlew :lib:ime-core:koverLog` prints the current numbers (at the time
// of writing: 92% of lines overall; input/* 97-100%, core 64%, being mostly key tables --
// adding untested tables there will need its floor looked at).
// Part of `check`.
kover {
    currentProject {
        sources {
            // FakeEditor is test code, whatever source set it lives in
            excludedSourceSets.add("testFixtures")
        }
    }
    reports {
        verify {
            rule("line coverage, whole module") {
                minBound(90)
            }
            rule("line coverage, every package") {
                groupBy.set(kotlinx.kover.gradle.plugin.dsl.GroupingEntityType.PACKAGE)
                minBound(60)
            }
        }
    }
}
