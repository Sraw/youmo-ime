plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.fcitx.fcitx5.android"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // jni
                    "native-lib",
                    // copy fcitx5 built-in addon libraries
                    "copy-fcitx5-modules",
                    // android specific modules
                    "androidfrontend",
                    "androidkeyboard",
                    "androidnotification"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }

    buildTypes {
        release {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    testOptions {
        unitTests {
            // don't blow up with `RuntimeException: Stub!` when a test incidentally reaches
            // an android.jar stub; Robolectric handles the cases that need real behaviour
            isReturnDefaultValues = true
            // ...which also means a plain JVM test that touches the framework gets false/0/null
            // back and can pass for the wrong reason. Logic belongs in :lib:ime-core; anything
            // that genuinely needs the framework should run under Robolectric.
            // NOTE: intentionally left off. Turning it on makes :app:test depend on the
            // merged-assets pipeline, which drags in the fcitx-component CMake install tasks;
            // those fail outside a native build with "Cannot query ... property 'cxxAbiModel'".
            // Consequence: Robolectric tests can use the framework but not the app's own
            // resources (no R.string lookups). See dev/ISSUES.md #4.
            isIncludeAndroidResources = false
        }
    }
}

fcitxComponent {
    includeLibs = listOf(
        "fcitx5",
        "fcitx5-lua",
        "libime",
        "fcitx5-chinese-addons"
    )
    // exclude (delete immediately after install) tables that nobody would use
    excludeFiles = listOf("cangjie", "erbi", "qxm", "wanfeng").map {
        "usr/share/fcitx5/inputmethod/$it.conf"
    }
    installPrebuiltAssets = true
}

androidComponents {
    onVariants { variant ->
        // The build type's resValue entries (app_icon, app_name) point at launcher
        // resources that live in the app's own resource set. AGP copies them into the
        // androidTest APK too, where those references cannot be resolved and resource
        // linking fails. The test APK has no launcher, so just drop them there.
        variant.androidTest?.resValues?.empty()
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":lib:ime-core"))
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
    implementation(project(":lib:libime"))
    implementation(project(":lib:fcitx5-chinese-addons"))
    implementation(project(":lib:common"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.imagecropper)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockk.android)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}
