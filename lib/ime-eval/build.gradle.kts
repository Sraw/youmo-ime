/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// Measures input engines against the evaluation sets in data/. A host-side tool: it never ships
// in the APK, so unlike :lib:ime-core it is free to use whatever the build's JDK offers.
//
//   ./gradlew :lib:ime-eval:run --args="score data/pinyin.tsv <result.tsv> [<baseline.tsv>] [--half tune]"
//   ./gradlew :lib:ime-eval:run --args="pinyin <pinyin.data> data/pinyin.tsv <result.tsv> [--scheme xiaohe] [--fuzzy all] [--half tune]"
//   ./gradlew :lib:ime-eval:run --args="shuangpin xiaohe data/pinyin.tsv data/shuangpin-xiaohe.tsv"
//   ./gradlew :lib:ime-eval:run --args="slips data/pinyin.tsv data/pinyin-slips.tsv"
//   ./gradlew :lib:ime-eval:run --args="tune <pinyin.data> data/pinyin.tsv data/pinyin-slips.tsv"
//   ./gradlew :lib:ime-eval:run --args="ksc <pinyin.data> data/pinyin.tsv [--scheme xiaohe] [--fuzzy all] [--half tune]"
//   ./gradlew :lib:ime-eval:run --args="learn <pinyin.data> data/pinyin.tsv [--scheme xiaohe] [--fuzzy all]"
//   ./gradlew :lib:ime-eval:run --args="table <table.data> data/pinyin.tsv [--preset wubi]"
//   (every command and its options: the usage, which a run with no arguments prints; Main.kt's COMMANDS make it)
//
// data/pinyin.tsv is written by hand; the other sets are made from it by the commands above, and
// a test fails until they are remade after it changes. Samples split into a tune and a held-out
// half by their text (Halves): choose on one, report on the other. data/pinyin-chat.tsv is chat
// text rather than written for the project, made once by tools/make-chat-set.py.
//
// `pinyin`, `ksc` and `tune` run on every core unless given --threads; the results do not change
// with it, but host latencies compare only between runs on as many threads.
//
// Results come from an engine run: run-on-device.sh produces one for the engine the APK ships
// (the own engine, since libime left the app); baseline/ keeps the libime result it was measured
// against.
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
    // the engine's handwriting test data, rather than a copy of it to drift
    testImplementation(testFixtures(project(":lib:ime-core")))
}

tasks.withType<Test>().configureEach {
    // the tests read data/ to check the evaluation sets themselves
    workingDir = projectDir
    // an edit to data/ alone runs them again, rather than leaving them up to date (or from the cache)
    inputs.dir("data").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("failed")
    }
}
