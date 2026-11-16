plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val spatial = "0.15.0"

android {
    namespace = "com.simach.arepo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.simach.arepo.quest"
        minSdk = 34
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "arepo2026"
            keyAlias = "arepo"
            keyPassword = "arepo2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging { resources.excludes.add("META-INF/LICENSE") }
    lint { abortOnError = false; checkReleaseBuilds = false }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.meta.spatial:meta-spatial-sdk:$spatial")
    implementation("com.meta.spatial:meta-spatial-sdk-toolkit:$spatial")
    implementation("com.meta.spatial:meta-spatial-sdk-vr:$spatial")
    implementation("com.meta.spatial:meta-spatial-sdk-physics:$spatial")
    implementation("com.meta.spatial:meta-spatial-sdk-mruk:$spatial")
    implementation("com.meta.spatial:meta-spatial-sdk-compose:$spatial")
    implementation("com.meta.spatial:meta-spatial-sdk-isdk:$spatial")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
}
