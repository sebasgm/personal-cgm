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

    /**
     * Nothing here uses Fragments; this is a floor, not a dependency.
     *
     * androidx.fragment arrives transitively at 1.1.0 — play-services-wearable
     * brings it, and so does appcompat under the watch-face complication
     * artifacts. That version predates the ActivityResult contracts the
     * activities here are built on: its FragmentActivity never calls
     * super.onRequestPermissionsResult and uses request codes the contracts
     * treat as invalid, so a permission result can be delivered to nothing. Lint
     * reports it as an error rather than a warning for that reason.
     *
     * A constraint rather than `implementation`, because what is wanted is the
     * version requirement without the library on the compile classpath.
     */
    constraints {
        implementation(libs.androidx.fragment) {
            because("ActivityResult contracts need Fragment 1.3.0 or newer")
        }
    }

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.wear.complications.data.source)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)
}
