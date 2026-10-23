import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gerardoqm.blescoreboard.wear"
    compileSdk = 35

    defaultConfig {
        // Same ID as the phone app: the Wear Data Layer only connects apps with the same ID and signing key.
        applicationId = "com.gerardoqm.blescoreboard"
        minSdk = 30
        targetSdk = 35
        versionCode = (project.property("appVersionCode") as String).toInt()
        versionName = project.property("appVersionName") as String
    }

    androidResources {
        localeFilters += listOf("en", "es")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
