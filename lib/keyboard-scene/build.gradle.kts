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


// The JS test runner's npm packages are pinned by kotlin-js-store/package-lock.json: a lock that does not
// match fails the build (the default), and so does a run that would write one where none is committed
rootProject.extensions.configure<org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension> {
    reportNewPackageLock.set(true)
}

dependencies {
    // minSdk 29; keep in step with minSdk and ime-core's
    signature(variantOf(libs.gummyBears.api29) { artifactType("signature") })
}

// The tests read src/commonTest/data (the example layouts and themes; the snapshots, which they also
// write) on both targets: told where by a system property (JVM) or the environment (node), so no
// absolute path of this machine is compiled into anything
val testData: String = layout.projectDirectory.dir("src/commonTest/data").asFile.absolutePath
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    environment("SCENE_TEST_DATA", testData)
}

tasks.withType<Test>().configureEach {
    // -Psnapshots.record=true writes the scene snapshots anew instead of comparing (JVM only: the JS
    // run then compares with what the JVM wrote, so the two agree or fail)
    systemProperty("snapshots.record", providers.gradleProperty("snapshots.record").getOrElse("false"))
    systemProperty("scene.testData", testData)
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
