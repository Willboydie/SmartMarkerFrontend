plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.example.smartmarkerfrontend"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.smartmarkerfrontend"
        minSdk = 24
        targetSdk = 37
        versionCode = 214
        versionName = "0.9.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("ezdxf")
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    listOf("ccu.h2_0.file", "ccu.h2_1.file", "ccu.h2_4.file").forEach { prop ->
        val marker = providers.gradleProperty(prop).orNull
        if (!marker.isNullOrEmpty()) {
            systemProperty(
                prop,
                project.layout.projectDirectory.file(marker).asFile.absolutePath
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
