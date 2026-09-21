plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data-llu"))
    // :data-llu keeps OkHttp as an implementation detail, but the server builds
    // the shared client itself so every session reuses one connection pool.
    implementation(libs.okhttp)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("dev.cgm.web.ServerKt")
}

tasks.test {
    useJUnitPlatform()
}

/** Scores forecast models against a recorded fixture. See BenchmarkMain. */
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Run the forecast benchmark over a recorded readings file"
    mainClass.set("dev.cgm.web.BenchmarkMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}
