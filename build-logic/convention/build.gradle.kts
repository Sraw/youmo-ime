import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `kotlin-dsl`
    kotlin("plugin.serialization") version embeddedKotlinVersion
    `java-gradle-plugin`
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.aboutlibraries.plugin)
    compileOnly(libs.detekt.gradlePlugin)
    implementation(libs.kotlinx.serialization.json)
    // A workaround to enable version catalog usage in the convention plugin,
    // see https://github.com/gradle/gradle/issues/15383#issuecomment-779893192
    implementation(files(LibrariesForLibs::class.java.protectionDomain.codeSource.location))
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        register("androidAppConvention") {
            id = "youmo.app-convention"
            implementationClass = "AndroidAppConventionPlugin"
        }
        register("androidLibConvention") {
            id = "youmo.lib-convention"
            implementationClass = "AndroidLibConventionPlugin"
        }
        register("buildMetadata") {
            id = "youmo.build-metadata"
            implementationClass = "BuildMetadataPlugin"
        }
        register("detektConvention") {
            id = "youmo.detekt"
            implementationClass = "DetektConventionPlugin"
        }
        register("voiceData") {
            id = "youmo.voice-data"
            implementationClass = "VoiceDataPlugin"
        }
        register("handwritingData") {
            id = "youmo.handwriting-data"
            implementationClass = "HandwritingDataPlugin"
        }
        register("engineData") {
            id = "youmo.engine-data"
            implementationClass = "EngineDataPlugin"
        }
        register("unitTestResources") {
            id = "youmo.unit-test-resources"
            implementationClass = "UnitTestResourcesPlugin"
        }
        register("assetPacks") {
            id = "youmo.asset-packs"
            implementationClass = "AssetPacksPlugin"
        }
        register("nativeAppConvention") {
            id = "youmo.native-app-convention"
            implementationClass = "NativeAppConventionPlugin"
        }
    }
}
