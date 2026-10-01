import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.vaier.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.vaier.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.8"
    }

    // Release signing comes from the environment, never from a file in the tree. Unset, the release
    // build falls back to the debug key so a local build still produces an installable APK.
    val keystore = System.getenv("VAIER_ANDROID_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("VAIER_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VAIER_ANDROID_KEY_ALIAS") ?: "vaier"
                keyPassword = System.getenv("VAIER_ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystore != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The build box is memory-tight: keep the forked test JVM small enough that it and the compiler
// daemon never add up to an OOM kill.
tasks.withType<Test>().configureEach {
    maxHeapSize = "384m"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.browser)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.wireguard.tunnel)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
