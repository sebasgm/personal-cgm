import java.io.File

plugins {
    // AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android is gone
    // (and is actively rejected). Only the compiler plugins are declared here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

/**
 * The release notes shown in Settings are CHANGELOG.md itself.
 *
 * Copied into assets at build time rather than duplicated into a string
 * resource, so there is one place to write them and no way for the two to drift.
 *
 * A typed task rather than a `Copy`, because AGP 9 wires generated sources
 * through the Variant API and that needs a `DirectoryProperty` to point at.
 */
abstract class CopyChangelog : DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    abstract val source: RegularFileProperty

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun copy() {
        val target = outputDirectory.get().asFile
        target.mkdirs()
        source.get().asFile.copyTo(target.resolve("CHANGELOG.md"), overwrite = true)
    }
}

val copyChangelog = tasks.register<CopyChangelog>("copyChangelog") {
    source.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
}

/**
 * Copies a built APK to `build/apks` under a name that carries the version.
 *
 * AGP names its own output `app-release.apk`, which says nothing about what is
 * inside it: once a handful have been sideloaded onto a phone or attached to a
 * release, there is no telling one build from another by looking at it. The version
 * is in `output-metadata.json` beside the APK and nowhere else — not in the file
 * name a download folder shows, which is the one place it is read.
 *
 * A copy rather than a rename, so the canonical paths the README and the
 * field-testing checklist install from keep working.
 */
abstract class VersionedApk : DefaultTask() {
    @get:org.gradle.api.tasks.InputFiles
    abstract val apkDirectory: DirectoryProperty

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:org.gradle.api.tasks.Input
    abstract val apkName: Property<String>

    /**
     * The loader is how the APK's real file name is read out of the variant's
     * `output-metadata.json`; it is not itself an input to anything.
     */
    @get:org.gradle.api.tasks.Internal
    abstract val builtArtifacts: Property<com.android.build.api.variant.BuiltArtifactsLoader>

    @org.gradle.api.tasks.TaskAction
    fun copy() {
        val built = builtArtifacts.get().load(apkDirectory.get())
            ?: error("No APK was built to name ${apkName.get()}")
        val target = outputDirectory.get().asFile.resolve(apkName.get())
        target.parentFile.mkdirs()
        File(built.elements.single().outputFile).copyTo(target, overwrite = true)
        logger.lifecycle("APK: $target")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyChangelog,
            CopyChangelog::outputDirectory,
        )

        tasks.register<VersionedApk>(
            "versionedApk${variant.name.replaceFirstChar(Char::uppercase)}",
        ) {
            apkDirectory.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            builtArtifacts.set(variant.artifacts.getBuiltArtifactsLoader())
            outputDirectory.set(rootProject.layout.buildDirectory.dir("apks"))
            // A release build is the thing that gets handed out, so its name is
            // just the version; anything else says which build type it came from.
            apkName.set(
                variant.outputs.single().versionName.map { version ->
                    if (variant.buildType == "release") {
                        "cgm-$version.apk"
                    } else {
                        "cgm-$version-${variant.buildType}.apk"
                    }
                },
            )
        }
    }
}

android {
    namespace = "dev.cgm.app"
    // Compose BOM 2026.09 requires 37; Android 16+ platforms carry a minor version.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "dev.cgm.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 3105
        versionName = "3.1.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            /**
             * Signed with the debug key, deliberately.
             *
             * These builds are sideloaded one at a time onto the developer's own
             * phone, and an APK signed with a key other than the installed one
             * cannot be installed over it — the app would have to be uninstalled
             * first, taking the local reading history with it. There is no Play
             * listing and no upload key here to protect, so the key every build so
             * far has carried is the one to keep carrying.
             *
             * The cost is that the debug key is the public, well-known one: a
             * release published anywhere is a release anyone can build an update
             * for. That is a real property of these builds, not a signing accident.
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data-llu"))

    /**
     * Nothing here uses Fragments; this is a floor, not a dependency.
     *
     * androidx.fragment arrives transitively at 1.1.0, under
     * play-services-wearable. That version predates the ActivityResult
     * contracts the activities here are built on: its FragmentActivity never calls
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
    implementation(libs.okhttp)
    // Publishes readings to the watch over the Data Layer.
    implementation(libs.play.services.wearable)
    // The car screen. app-projected is the Android Auto half; the base artifact
    // alone builds but never gets a host to talk to.
    implementation(libs.car.app)
    implementation(libs.car.app.projected)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
