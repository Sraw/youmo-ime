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

// uatTest: the uat/ cases whose 预期 is what the engine types, replayed through Engines with the
// app's own data (EngineReplay), so they need no device. Not part of `test` or `check`: it reads the
// data :app compiles (hundreds of MB, minutes the first time), where ime-core's tests take milliseconds.
//   ./gradlew :lib:ime-eval:uatTest
val uatTestSources = sourceSets.create("uatTest") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}
configurations["uatTestImplementation"].extendsFrom(configurations.implementation.get())
configurations["uatTestRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

// the app's engine data, as its assets have them: each task with its output directory (engine/ inside),
// one pair a line so a task cannot be added without its directory
val appDataOutputs = listOf(
    "compileEngineData" to "engine-assets",
    "compileEnglishData" to "engine-english",
    "compileEmojiData" to "engine-emoji",
    "compileChaiziData" to "engine-chaizi",
    "compileEngineTables" to "engine-tables",
    "copySentenceModels" to "engine-models",
    "compileOpenccData" to "engine-opencc",
)
val appDataTasks = appDataOutputs.map { it.first }
val appDataDirs = appDataOutputs.map { rootProject.layout.projectDirectory.dir("app/build/generated/${it.second}") }

// what a run over the app's data reads: the data, the punctuation table, pkgdata, strings, the
// fixtures; uatTest's and parityReplay's
fun <T> T.appData() where T : Task, T : JavaForkOptions {
    dependsOn(appDataTasks.map { ":app:$it" })
    appDataDirs.forEach { inputs.dir(it).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("data-${it.asFile.name}") }
    // the punctuation table the app ships, for the host replay's punctuation module
    val punctuation = rootProject.layout.projectDirectory.file("app/src/main/host-assets/pkgdata/punctuation/punc.mb.zh_CN")
    inputs.file(punctuation).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("punctuation")
    systemProperty("uat.punctuation", punctuation.asFile.absolutePath)
    systemProperty("uat.assets", appDataDirs.joinToString(File.pathSeparator) { it.asFile.absolutePath })
    // fcitx's own data the app ships (quick phrases, charselectdata), the Kotlin host's pkgdata: the host
    // replay's quick input and Unicode read them
    val pkgdata = rootProject.layout.projectDirectory.dir("app/src/main/host-assets/pkgdata")
    inputs.dir(pkgdata).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("pkgdata")
    systemProperty("uat.fcitx5", pkgdata.asFile.absolutePath)
    // the app's string resources: the parity replay's host texts in the devices' language (ResourceHostStrings)
    val res = rootProject.layout.projectDirectory.dir("app/src/main/res")
    inputs.files(res.file("values/strings.xml"), res.file("values-zh-rCN/strings.xml")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("strings")
    systemProperty("uat.res", res.asFile.absolutePath)
    // the files a case imports (dict-words.txt…), the ones the device's cases push
    val fixtures = rootProject.layout.projectDirectory.dir("uat/fixtures")
    inputs.dir(fixtures).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("fixtures")
    systemProperty("uat.fixtures", fixtures.asFile.absolutePath)
    // the data is mapped, not read in; the sentence models' weights and the word indexes are on the heap
    maxHeapSize = "2g"
}

val uatTest = tasks.register<Test>("uatTest") {
    description = "The uat/ cases replayed through the engine with the app's data."
    group = "verification"
    testClassesDirs = uatTestSources.output.classesDirs
    classpath = uatTestSources.runtimeClasspath
    appData()
}

// a parity sequence file (fcitx-replacement §7.2, uat/parity) on the Kotlin host with the app's data:
//   ./gradlew :lib:ime-eval:parityReplay --args="<sequences.jsonl> <out.jsonl> [<first n> | <k>/<n> | all] [<device output.jsonl>: its ids only]"
// paths relative to this module, as `run`'s
tasks.register<JavaExec>("parityReplay") {
    description = "A parity sequence file replayed on the Kotlin host and the real engines."
    classpath = uatTestSources.runtimeClasspath
    mainClass.set("org.fcitx.fcitx5.android.uat.ParityReplay")
    workingDir = projectDir
    appData()
}

dependencies {
    implementation(project(":lib:ime-core"))
    // the parity files (sequences, outputs): JSON lines the device tools (uat/parity) read and write too
    implementation(libs.kotlinx.serialization.json)
    "uatTestImplementation"(libs.junit)
    // @Uat, which uat.py jvm reads
    "uatTestImplementation"(testFixtures(project(":lib:ime-core")))
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
