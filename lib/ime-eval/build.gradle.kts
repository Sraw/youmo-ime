/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// Measures input engines against the evaluation sets in data/. A host-side tool: it never ships
// in the APK, so unlike :lib:ime-core it is free to use whatever the build's JDK offers.
//
//   ./gradlew :lib:ime-eval:run --args="score data/pinyin.tsv <result.tsv> [<baseline.tsv>]"
//   ./gradlew :lib:ime-eval:run --args="pinyin <pinyin.data> data/pinyin.tsv <result.tsv> [<shuangpin scheme>]"
//   ./gradlew :lib:ime-eval:run --args="shuangpin xiaohe data/pinyin.tsv <shuangpin-set.tsv>"
//
// Results come from an engine run: run-on-device.sh produces one for the engine the APK ships
// (libime today); baseline/ keeps the libime result the new engine is measured against.
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

application {
    mainClass.set("org.fcitx.fcitx5.android.eval.MainKt")
}

tasks.named<JavaExec>("run") {
    // relative paths in --args resolve against this module, where data/ lives
    workingDir = projectDir
}

dependencies {
    implementation(project(":lib:ime-core"))
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // the tests read data/ to check the evaluation sets themselves
    workingDir = projectDir
    testLogging {
        events("failed")
    }
}
