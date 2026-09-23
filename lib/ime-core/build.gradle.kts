/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// A plain JVM library, deliberately NOT an android-library: the compiler then enforces that
// nothing here can reach for the Android framework. Logic that lands in this module is
// testable in milliseconds, with no emulator, no Robolectric and no Android SDK.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.animalsniffer)
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

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
    }
}
