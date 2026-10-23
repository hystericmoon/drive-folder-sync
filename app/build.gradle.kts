plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Values supplied by CI (see .github/workflows/release.yml). Local builds fall back to defaults.
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val signingPassword: String? = System.getenv("SIGNING_STORE_PASSWORD")
val webClientId: String = System.getenv("GOOGLE_WEB_CLIENT_ID") ?: ""
val updateRepo: String = System.getenv("GITHUB_REPOSITORY") ?: "hystericmoon/drive-folder-sync"
val ciVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

android {
    namespace = "io.github.hystericmoon.drivesync"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.hystericmoon.drivesync"
        minSdk = 29
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = "0.1.$ciVersionCode"
        buildConfigField("String", "WEB_CLIENT_ID", "\"$webClientId\"")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (signingKeystore != null && signingPassword != null) {
            create("release") {
                storeFile = file(signingKeystore)
                storePassword = signingPassword
                keyAlias = "drivesync"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.generateKotlin", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)

    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
    implementation(libs.coroutines.play.services)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
