/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// Measures input engines against the evaluation sets in data/; a host-side tool. `./gradlew :lib:ime-eval:run` lists the commands.
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

// uatTest: uat/ cases replayed on the app's own data; not in `test` or `check`, as it reads the large data :app compiles.
val uatTestSources = sourceSets.create("uatTest") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}
configurations["uatTestImplementation"].extendsFrom(configurations.implementation.get())
configurations["uatTestRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

// the app's engine data directories, published by EngineDataPlugin as :app's engineData; depending on it builds them
val appData = configurations.resolvable("appEngineData")
val appDataScope = configurations.dependencyScope("appEngineDataScope")
appData.configure { extendsFrom(appDataScope.get()) }
dependencies { add(appDataScope.name, project(mapOf("path" to ":app", "configuration" to "engineData"))) }

// what a run over the app's data reads (data, punctuation table, pkgdata, strings, fixtures): uatTest's
fun <T> T.appData() where T : Task, T : JavaForkOptions {
    val data = appData.get()
    inputs.files(data).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("data")
    val punctuation = rootProject.layout.projectDirectory.file("app/src/main/host-assets/pkgdata/punctuation/punc.mb.zh_CN")
    inputs.file(punctuation).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("punctuation")
    systemProperty("uat.punctuation", punctuation.asFile.absolutePath)
    // read when the task runs: the directories are the tasks' outputs, known once the configuration resolves
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Duat.assets=" + data.files.joinToString(File.pathSeparator) { it.absolutePath }) })
    // fcitx's own data the app ships, for the host replay's quick input and Unicode
    val pkgdata = rootProject.layout.projectDirectory.dir("app/src/main/host-assets/pkgdata")
    inputs.dir(pkgdata).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("pkgdata")
    systemProperty("uat.fcitx5", pkgdata.asFile.absolutePath)
    // the app's string resources: the parity replay's host texts in the devices' language
    val res = rootProject.layout.projectDirectory.dir("app/src/main/res")
    inputs.files(res.file("values/strings.xml"), res.file("values-zh-rCN/strings.xml")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("strings")
    systemProperty("uat.res", res.asFile.absolutePath)
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
    // as :lib:ime-core's tests: an engines' call off the thread that made it fails the case
    systemProperty("youmo.engines.hostThreadStrict", "true")
}

tasks.test {
    systemProperty("youmo.engines.hostThreadStrict", "true")
    // the OpenCC dictionaries, read from disk (ChineseConverterGoldenTest): an edit to them alone runs it again
    inputs.dir(rootProject.file("lib/ime-dict-tool/opencc")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("opencc")
}

dependencies {
    implementation(project(":lib:ime-core"))
    implementation(libs.kotlinx.serialization.json)
    "uatTestImplementation"(libs.junit)
    // @Uat, which uat.py jvm reads
    "uatTestImplementation"(testFixtures(project(":lib:ime-core")))
    testImplementation(libs.junit)
    // the engine's handwriting test data, rather than a copy to drift
    testImplementation(testFixtures(project(":lib:ime-core")))
}

tasks.withType<Test>().configureEach {
    workingDir = projectDir
    // an edit to data/ alone runs them again, rather than leaving them up to date (or from the cache)
    inputs.dir("data").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("failed")
    }
}
