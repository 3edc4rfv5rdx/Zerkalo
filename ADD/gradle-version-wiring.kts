// The Gradle side of the build scripts. Not a build file of its own — paste
// these three pieces into app/build.gradle.kts of a project that uses the set.
//
// What the scripts assume, and this provides:
//   1. versionName / versionCode come out of build_number.txt
//   2. the release is signed from ~/.my-safe/key.properties (77-MakeMyKey.sh)
//   3. the release APKs are renamed to <project>-<version>-<abi>.apk

import java.util.Properties

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
// The day of the build, which the version stopped carrying when its last
// component became the build number. Shown on the About screen and nowhere else.
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
    defaultConfig {
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField("String", "BUILD_DATE", "\"$releaseBuildDate\"")
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
            // findByName, not getByName: a machine without the safe still builds an
            // unsigned release instead of failing to configure.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // arm64-v8a for the phone, x86_64 for the emulator, universal for anything
    // else. 19-LinkOut.sh links the first and the last into OUT/.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
}

// ---------- 3. APK names the scripts can read ----------
//
// Gradle writes app-<abi>-<type>.apk, which says nothing about which build it
// is. Everything downstream — 19-LinkOut.sh, 22-RelUpload.sh, 23-ToUpdate.sh,
// the .apkx link — reads the version out of the file name instead, and the
// version ends in the build number, so the rename happens here, once, right
// after the assemble.
//
// One shape for every artifact of every project here:
//
//   <project>-<version>-<abi>.apk         a release
//   <project>-<version>-<abi>-debug.apk   a debug build
//
// A release says nothing about its build type: that is what an artifact is
// unless it says otherwise, and the word in every name only makes the listing
// harder to read. A debug build does say so, because it is the one that must
// never be mistaken for the other.
//
// The name below must match the one the scripts use.

abstract class RenameApks : DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val projectName: Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val versionName: Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val buildType: Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val abis: ListProperty<String>

    @get:org.gradle.api.tasks.Internal
    abstract val outputDir: DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun rename() {
        val outDir = outputDir.get().asFile
        val type = buildType.get()
        val prefix = "${projectName.get()}-${versionName.get()}"
        val tail = if (type == "release") "" else "-$type"

        fun move(src: File, dst: File) {
            if (!src.exists()) return
            if (dst.exists()) dst.delete()
            src.renameTo(dst)
        }

        abis.get().forEach { abi ->
            move(File(outDir, "app-$abi-$type.apk"), File(outDir, "$prefix-$abi$tail.apk"))
        }
        // The unsplit output, for a variant the ABI splits do not apply to.
        move(File(outDir, "app-$type.apk"), File(outDir, "$prefix$tail.apk"))
    }
}

val renameReleaseApks by tasks.registering(RenameApks::class) {
    projectName.set("zerkalo")
    versionName.set(releaseVersionName)
    buildType.set("release")
    abis.set(listOf("universal", "arm64-v8a", "x86_64"))
    outputDir.set(layout.buildDirectory.dir("outputs/apk/release"))
}

// Not a finalizer of assembleDebug, the way the release one is: the debug APK is
// also what an instrumented test run installs, and that install reads the name
// out of Gradle's own output metadata. The debug build step asks for this task
// by name instead, so a test run from the IDE still finds app-<abi>-debug.apk.
val renameDebugApks by tasks.registering(RenameApks::class) {
    dependsOn("assembleDebug")
    projectName.set("zerkalo")
    versionName.set(releaseVersionName)
    buildType.set("debug")
    abis.set(listOf("universal", "arm64-v8a", "x86_64"))
    outputDir.set(layout.buildDirectory.dir("outputs/apk/debug"))
}

tasks.configureEach {
    if (name == "assembleRelease") {
        finalizedBy(renameReleaseApks)
    }
}

