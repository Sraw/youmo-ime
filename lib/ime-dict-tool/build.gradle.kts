/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// Compiles the engine's data files from their text sources (libime's dictionary, language model
// and code tables). A host-side tool: it never ships in the APK; the formats themselves, and
// the code that reads them on the device, live in :lib:ime-core.
//
//   ./gradlew :lib:ime-dict-tool:run --args="pinyin -o <out> --lm <lm.arpa> <dict.txt>..."
//   ./gradlew :lib:ime-dict-tool:run --args="table -o <out> <table.txt>"
//   ./gradlew :lib:ime-dict-tool:run --args="mix -o <out.arpa> --lm <lm.arpa> <chat.jsonl.gz | fineweb.parquet>..."
//   ./gradlew :lib:ime-dict-tool:run --args="check <pinyin data> <lm.arpa>"
//
// A release's data (a CommonCrawl crawl's Chinese pages, cleaned and mixed in, and its new words)
// is engine-data.sh's to make, a job of hours for a rented machine (dev/TRAINING-PLAN.md 12.9).
//
// The app build runs it through EngineDataPlugin (build-logic): `./gradlew :app:compileEngineData`
// downloads libime's sources and two of FineWeb-2's Chinese shards once, mixes the shards'
// chat-like pages into the model and puts engine/pinyin.data into the app's assets.
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

application {
    mainClass.set("org.fcitx.fcitx5.android.dicttool.MainKt")
    // mix holds two language models and the chat's counts in memory: 7 GB resident over two
    // FineWeb-2 shards, as measured; keep in step with EngineDataPlugin
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
    testLogging {
        events("failed")
    }
}
