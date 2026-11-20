/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// A plain JVM library, not an android-library, so nothing here can reach the Android framework and tests run in milliseconds.
plugins {
    `java-library`
    // FakeEditor in testFixtures so :app's contract test can hold it against the real BaseInputConnection
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.animalsniffer)
    alias(libs.plugins.kover)
}

// Compile against JDK 11's API and check Android's at minSdk (animal-sniffer), as lint skips plain JVM modules.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

// fcitx's built-in data as the app ships it, on the tests' classpath as /pkgdata/...
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
    // minSdk 29, in step with minSdk; no coreLib2 variant for API 29, so desugared APIs are rejected too
    signature(variantOf(libs.gummyBears.api29) { artifactType("signature") })
    // the drawing engine: a theme's look and the keyboards' state in its words (input/scene)
    api(project(":lib:keyboard-scene"))
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Engines called off its host thread fails the test (HostThread.ofTests); the app's builds check it their own way
    systemProperty("youmo.engines.hostThreadStrict", "true")
    // the engine's example layouts and themes, which input/scene's tests build scenes of
    val sceneData = rootProject.file("lib/keyboard-scene/src/commonTest/data")
    systemProperty("scene.data", sceneData.absolutePath)
    inputs.dir(sceneData).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("sceneData")
    // and the layouts the app draws, which they read beside it (SceneData)
    inputs.dir(rootProject.file("lib/keyboard-scene/src/commonMain/layouts")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("sceneLayouts")
    // MisreadingsTest checks the list the app build compiles: an edit to it alone runs it again
    inputs.file(rootProject.file("lexicon/misreadings.tsv")).withPathSensitivity(PathSensitivity.RELATIVE)
    // the other files tests read by path, as inputs so an edit reruns them
    inputs.dir(rootProject.file("lib/keyboard-scene/src/commonMain/themes")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("sceneThemes")
    inputs.files(rootProject.file("uat/fixtures/layouts"), rootProject.file("uat/fixtures/scel")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("uatFixtures")
    inputs.dir(rootProject.file("app/src/main/assets/learn")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("learnAssets")
    inputs.file(rootProject.file("docs/words.md")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("wordsDoc")
    testLogging {
        events("failed")
    }
}

// Coverage floors (part of `check`); `./gradlew :lib:ime-core:koverLog` prints the numbers
kover {
    currentProject {
        sources {
            // FakeEditor is test code, whatever source set it lives in
            excludedSourceSets.add("testFixtures")
        }
        instrumentation {
            // the bounded-heap tests time their keys; the coverage agent would slow them and add to the small heap
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

// A merged dictionary must compile and open in a small heap (DictionaryFileBoundedHeapTest): alone in a 48 MB JVM
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
