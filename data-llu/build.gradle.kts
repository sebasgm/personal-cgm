plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Plain Kotlin/JVM, like :core. Keeping Android out means the whole LibreLinkUp
// client is unit-testable on the JVM against a MockWebServer, and can be reused
// verbatim by the Wear module for the stage 6 standalone fallback.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}
