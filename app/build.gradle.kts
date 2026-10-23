import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gerardoqm.blescoreboard"
    compileSdk = 35

    defaultConfig {
        // The Wear OS app uses the same application ID: the Data Layer only pairs apps with the same ID and signing key.
        applicationId = "com.gerardoqm.blescoreboard"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.property("appVersionCode") as String).toInt()
        versionName = project.property("appVersionName") as String
    }

    androidResources {
        // English and Spanish only, so the in-app language switch has exactly these two to offer.
        localeFilters += listOf("en", "es")
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
