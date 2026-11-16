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
        /**
         * **Tracks the phone**, from the root build rather than being set here.
         *
         * It used to be set here, and it was then left at 3.0.0 across every change
         * the watch received — so the watch could not say what it was running and
         * neither could anyone else. The two APKs are installed as a pair; one number
         * for both is the only version either of them can be asked about usefully.
         */
        versionCode = rootProject.extra["cgmVersionCode"] as Int
        versionName = rootProject.extra["cgmVersionName"] as String
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            /**
             * Signed with the debug key, for the phone module's reasons and one of
             * its own.
             *
             * A release build with no signing config at all is unsigned, and an
             * unsigned APK cannot be installed on anything — so until now `:wear`
             * had a release variant that could be built and never installed, which
             * is why every watch build so far has been a debug one.
             *
             * The key has to match the phone's for the other reason as well: these
             * are two APKs of one application id, and a watch APK signed with a
             * different key could not be installed beside it.
             */
            signingConfig = signingConfigs.getByName("debug")
        }
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

/**
 * Copies the built APK to `build/apks` under a name that carries the version.
 *
 * The phone module does this through the Variant API because it names release and
 * debug differently; here one plain copy will do, and the point is only that the file
 * in a download folder says which build it is.
 *
 * The name shouts because the two APKs share an application id — they have to, or the
 * Data Layer never pairs them — and so the watch build will install cleanly over the
 * phone app and replace it. That is the one mistake this directory can hand someone,
 * and the file name is the only warning present at the moment it would be made. The
 * 3.0.0 release named it this way for the same reason.
 */
tasks.register<Copy>("versionedApkRelease") {
    // Read here and not inside `rename`: that lambda runs at execution time, and a
    // reference to the project from there is captured in the configuration cache,
    // which refuses to serialise one.
    val apkName = "cgm-WATCH-do-not-install-on-phone-${rootProject.extra["cgmVersionName"]}.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { apkName }
    }
    into(rootProject.layout.buildDirectory.dir("apks"))
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
