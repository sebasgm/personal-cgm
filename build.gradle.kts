plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}

/**
 * One version number for every APK this repository produces.
 *
 * The phone and the watch are two APKs of one application id, installed as a pair and
 * talking to each other over the Data Layer. Versioned separately they drifted, and the
 * drift was invisible from either device: four commits of real change landed on the
 * watch — the complication itself among them, and the fix for a watch that froze on a
 * stale payload forever — while `versionName` sat at the value it was released with. A
 * wrist showing "3.0.0" therefore meant nothing at all, which is the exact failure the
 * numbered headings in CHANGELOG.md exist to prevent on the phone.
 *
 * So the number lives here and the modules read it. It is the same argument the
 * changelog makes about builds that are sideloaded one at a time: the only way to tell
 * two of them apart is a number that cannot be forgotten.
 */
val cgmVersionName = "3.8.0"

/**
 * The integer Android orders installs by, derived rather than written.
 *
 * `major * 1000 + minor * 100 + patch`, which is what every version in this repository's
 * history already used — 3.0.0 was 3000 and 3.2.1 was 3201 — so nothing reinstalls or
 * downgrades across the change. Derived because two numbers that must agree are two
 * numbers that eventually will not: a `versionCode` left behind is an APK Android
 * silently refuses to install over the one already there.
 *
 * The arithmetic caps minor at 9 and patch at 99. Both are checked rather than assumed,
 * because the failure is otherwise a collision between two different versions claiming
 * one code.
 */
val cgmVersionCode = cgmVersionName.split(".").let { parts ->
    require(parts.size == 3) { "Version must be major.minor.patch, got $cgmVersionName" }
    val (major, minor, patch) = parts.map {
        it.toIntOrNull() ?: error("Version part '$it' is not a number in $cgmVersionName")
    }
    require(minor in 0..9) { "Minor version $minor does not fit the versionCode scheme" }
    require(patch in 0..99) { "Patch version $patch does not fit the versionCode scheme" }
    major * 1000 + minor * 100 + patch
}

allprojects {
    extra["cgmVersionName"] = cgmVersionName
    extra["cgmVersionCode"] = cgmVersionCode
}
