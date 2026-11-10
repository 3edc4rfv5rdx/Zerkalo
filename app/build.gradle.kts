import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------- 1. version out of build_number.txt ----------

val buildNumberFile = rootProject.file("build_number.txt")
val buildProps = Properties()
if (buildNumberFile.exists()) {
    buildNumberFile.inputStream().use { buildProps.load(it) }
}

val releaseVersionName = buildProps.getProperty("version") ?: "0.1.0"
// A freshly initialised build_number.txt holds build=0, which the build scripts bump on their
// first run; Gradle rejects a zero versionCode, so an un-bumped file still reads as 1 here.
val releaseVersionCode = (buildProps.getProperty("build")?.trim()?.toIntOrNull() ?: 1).coerceAtLeast(1)
// The day of the build, shown on the About screen and nowhere else.
val releaseBuildDate = buildProps.getProperty("build_date")?.trim().orEmpty()

// ---------- 2. signing out of the safe ----------

val keyProperties = Properties()
// The safe first, the project second: a key.properties in the working tree is a
// fallback for a machine that has no safe, never the thing that gets committed.
val keyPropertiesFile = sequenceOf(
    File(System.getProperty("user.home"), ".my-safe/key.properties"),
    rootProject.file("key.properties")
).firstOrNull { it.exists() } ?: rootProject.file("key.properties")
val hasReleaseSigning = keyPropertiesFile.exists().also { exists ->
    if (exists) {
        keyPropertiesFile.inputStream().use { keyProperties.load(it) }
    }
}
val keyStoreFile = (keyProperties["storeFile"] as String?)?.let { rawPath ->
    val candidate = File(rawPath)
    if (candidate.isAbsolute) candidate else File(keyPropertiesFile.parentFile, rawPath)
}

android {
    namespace = "xx.zerkalo"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "xx.zerkalo"
        // 28 (Android 9): the older of the two TV boxes this app is built for.
        minSdk = 28
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField("String", "BUILD_DATE", "\"$releaseBuildDate\"")
    }

    sourceSets {
        // The shared updater and About dialog, compiled from ../shared instead of pulled
        // in as modules. The kotlin set and not the java one: the Kotlin source set no
        // longer inherits java, so .kt files put there are never compiled.
        getByName("main") {
            kotlin.directories.add("$rootDir/../shared/updater/android/src")
            res.directories.add("$rootDir/../shared/updater/android/res")
            kotlin.directories.add("$rootDir/../shared/about/android/src")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keyStoreFile
                storePassword = keyProperties["storePassword"] as String
                keyAlias = keyProperties["keyAlias"] as String
                keyPassword = keyProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // findByName, not getByName: a machine without the safe still builds an
            // unsigned release instead of failing to configure.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        lintConfig = file("lint.xml")
        // 05-Lint.sh runs the full lint; the pass every release build would run on
        // its own only adds a minute to it.
        checkReleaseBuilds = false
    }
}

// The app collects no crash reports to read a composable mapping against.
composeCompiler {
    includeComposeMappingFile.set(false)
}

// ---------- 3. APK names the scripts can read ----------
//
// The app has no native code, so there are no ABI splits: one APK runs on the phone
// and on a 32-bit TV box alike. Gradle writes it as app-<type>.apk, and everything
// downstream reads the version and the ABI out of the file name:
//
//   <project>-<version>-universal.apk         a release
//   <project>-<version>-universal-debug.apk   a debug build
//
// The project name below must match PROJECT in 99-project.conf.

abstract class RenameApk : DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val projectName: Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val versionName: Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val buildType: Property<String>

    @get:org.gradle.api.tasks.Internal
    abstract val outputDir: DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun rename() {
        val outDir = outputDir.get().asFile
        val type = buildType.get()
        val tail = if (type == "release") "" else "-$type"
        val src = File(outDir, "app-$type.apk")
        val dst = File(outDir, "${projectName.get()}-${versionName.get()}-universal$tail.apk")
        if (!src.exists()) return
        if (dst.exists()) dst.delete()
        src.renameTo(dst)
    }
}

val renameReleaseApk by tasks.registering(RenameApk::class) {
    projectName.set("zerkalo")
    versionName.set(releaseVersionName)
    buildType.set("release")
    outputDir.set(layout.buildDirectory.dir("outputs/apk/release"))
}

// Not a finalizer of assembleDebug: the debug APK is also what an instrumented test
// run installs, and that install reads the name out of Gradle's own output metadata.
val renameDebugApk by tasks.registering(RenameApk::class) {
    dependsOn("assembleDebug")
    projectName.set("zerkalo")
    versionName.set(releaseVersionName)
    buildType.set("debug")
    outputDir.set(layout.buildDirectory.dir("outputs/apk/debug"))
}

tasks.configureEach {
    if (name == "assembleRelease") {
        finalizedBy(renameReleaseApk)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    testImplementation(libs.junit)
}
