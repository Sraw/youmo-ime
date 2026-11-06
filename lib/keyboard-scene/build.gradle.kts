/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// The keyboard's drawing engine (dev/design/060-render-v2.md §6): a layout and a theme in, a scene of
// keys, shapes and drawing ops out. Kotlin Multiplatform so the app (JVM) and the theme maker's web page
// (JS) run the same code and draw the same; no dependency but the Kotlin standard library on either.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.animalsniffer)
    alias(libs.plugins.kover)
}

kotlin {
    jvm {
        // as ime-core: the JDK 11 API, then checked against Android's at minSdk (animal-sniffer, below)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xjdk-release=11")
        }
    }
    js {
        // node, not a browser: the tests need no headless Chromium, and read the snapshot files
        nodejs()
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The tests read src/commonTest/data (the example layout and theme; the snapshots, which they also write)
// on both targets; a node test has no project directory, so where it is is compiled in
val testData = layout.projectDirectory.dir("src/commonTest/data")
val testDataSource = layout.buildDirectory.dir("generated/testData/kotlin")
val writeTestDataDir = tasks.register("writeTestDataDir") {
    val out = testDataSource
    val dir = testData.asFile.absolutePath
    inputs.property("dir", dir)
    outputs.dir(out)
    doLast {
        val file = out.get().file("org/fcitx/fcitx5/android/scene/TestData.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package org.fcitx.fcitx5.android.scene\n\n" +
                "internal const val TEST_DATA = \"" + dir.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n",
        )
    }
}
kotlin.sourceSets.commonTest {
    kotlin.srcDir(writeTestDataDir.map { testDataSource.get() })
}

dependencies {
    // minSdk 29; keep in step with minSdk and ime-core's
    signature(variantOf(libs.gummyBears.api29) { artifactType("signature") })
}

tasks.withType<Test>().configureEach {
    // -Psnapshots.record=true writes the scene snapshots anew instead of comparing (JVM only: the JS
    // run then compares with what the JVM wrote, so the two agree or fail)
    systemProperty("snapshots.record", providers.gradleProperty("snapshots.record").getOrElse("false"))
    testLogging {
        events("failed")
    }
}

// Coverage floors on the JVM target, as ime-core's: the engine is the logic every key is drawn by
kover {
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
