/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// Compiles the engine's data files from their text sources: a host-side tool, run by the app build through EngineDataPlugin.
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    // coverage reported only (`koverHtmlReport`, uploaded by CI), with no floor yet
    alias(libs.plugins.kover)
}

application {
    mainClass.set("org.fcitx.fcitx5.android.dicttool.MainKt")
    // mix holds two language models and the chat's counts in memory
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}

tasks.named<JavaExec>("run") {
    // relative paths in --args resolve against where gradle was started, not this module
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":lib:ime-core"))
    implementation(libs.duckdb.jdbc)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // MainTest compiles the lists the app build passes: an edit to one alone runs it again
    inputs.files(rootProject.files("lexicon/readings.tsv", "lexicon/misreadings.tsv", "lexicon/remove.tsv"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("failed")
    }
}
