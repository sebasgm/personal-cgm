plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.cgm.wear"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        /**
         * **Must match the phone app.** The Wear Data Layer delivers to the app
         * with the same application id on the other device; a different id means
         * the two never see each other, with no error to explain why.
         */
        applicationId = "dev.cgm.app"

        // Wear OS 4 and up. The target watch shipped on Wear OS 5, and supporting
        // older releases would mean carrying the dead watch-face runtime with it.
        minSdk = 33
        targetSdk = 37
        versionCode = 3000
        versionName = "3.0.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures { compose = true }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)
}
