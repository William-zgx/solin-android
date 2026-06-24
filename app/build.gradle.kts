import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

val huggingFaceOAuthClientId: String = providers
    .gradleProperty("pocketmind.huggingFaceOAuthClientId")
    .orElse(providers.environmentVariable("POCKETMIND_HF_OAUTH_CLIENT_ID"))
    .orElse("")
    .get()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bytedance.zgx.pocketmind"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bytedance.zgx.pocketmind"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "HUGGING_FACE_OAUTH_CLIENT_ID", "\"$huggingFaceOAuthClientId\"")
        // RC perf collection entry points are gated off by default. Only the dedicated
        // rcPerfRelease variant flips this to true, so the production release never exposes the
        // harness receiver/activity/service.
        buildConfigField("Boolean", "RC_PERF_ENABLED", "false")

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // release-like variant used only for RC performance collection. It inherits the
        // release minify/shrink/proguard path so measured numbers reflect the shipping shape,
        // but enables the controlled RC perf harness and is signed with the debug key so it can
        // be installed on a real device. It deliberately keeps the production applicationId (no
        // suffix) so the harness can exercise models the app already downloaded on the device,
        // and it never wipes app data or model directories.
        create("rcPerfRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("Boolean", "RC_PERF_ENABLED", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    (
        providers.gradleProperty("aiBehaviorActualTraceFile").orNull
            ?: providers.systemProperty("aiBehaviorActualTraceFile").orNull
    )?.let { outputPath ->
        systemProperty("aiBehaviorActualTraceFile", outputPath)
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)
    implementation(libs.localagents.rag)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
