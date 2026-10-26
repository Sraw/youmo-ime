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
// The Android modules target Java 21 (Versions.java); this one stays at the JDK 11 API on purpose.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

// fcitx's built-in data (charselectdata, quick phrase and punctuation tables) as the app ships it:
// the tests read that one copy, on the classpath as /pkgdata/...
sourceSets.test {
    resources.srcDir("../../app/src/main/host-assets")
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
    // minSdk 29; keep in step with minSdk. gummy-bears publishes no coreLib2 (desugared java.*)
    // variant for API 29, so APIs only desugaring would supply are rejected here too
    signature(variantOf(libs.gummyBears.api29) { artifactType("signature") })
    // the host's event flow (HostEvents): a SharedFlow, as FcitxAPI.eventFlow is
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // MisreadingsTest checks the list the app build compiles: an edit to it alone runs it again
    inputs.file(rootProject.file("lexicon/misreadings.tsv")).withPathSensitivity(PathSensitivity.RELATIVE)
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
        instrumentation {
            // the bounded-heap tests time their keys; the coverage agent would slow them and add to the 48 MB heap
            disabledForTestTasks.add("boundedHeapTest")
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

// A merged dictionary's words must compile and open in a small heap (DictionaryFileBoundedHeapTest):
// 0.3.1 ran a phone's 256 MB out with them. Alone in a 48 MB JVM, so the bound is the heap's own
tasks.named<Test>("test") {
    exclude("**/*BoundedHeapTest*")
}
val boundedHeapTest = tasks.register<Test>("boundedHeapTest") {
    description = "Compiles, opens and codes for a table a large dictionary in a 48 MB heap."
    group = "verification"
    val main = tasks.named<Test>("test").get()
    testClassesDirs = main.testClassesDirs
    classpath = main.classpath
    filter.includeTestsMatching("*BoundedHeapTest")
    maxHeapSize = "48m"
}
tasks.named("check") { dependsOn(boundedHeapTest) }
