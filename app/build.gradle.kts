import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: simulated by CI from encrypted secrets (see docs/BUILDING.md).
// Locally, point PHONE_EYE_KEYSTORE at your own .p12. With no keystore present the
// release build falls back to the debug key so forks still build out of the box —
// but understand the consequence: Android will refuse to install one over the other.
val keystorePath = System.getenv("PHONE_EYE_KEYSTORE")
val hasReleaseKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "com.dreamer2664.phoneeye"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.dreamer2664.phoneeye"
        minSdk = 30          // Android 11: first release with AccessibilityService.takeScreenshot()
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        multiDexEnabled = true
        ndk {
            // The bundled cloudflared binary is arm64 only (see fetchCloudflared below).
            // Every mainstream phone since ~2017 is arm64-v8a. A 32-bit-only phone will get a
            // clear INSTALL_FAILED_NO_MATCHING_ABIS instead of a tunnel that never starts.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("PHONE_EYE_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("PHONE_EYE_KEY_ALIAS") ?: "phoneeye"
                keyPassword = System.getenv("PHONE_EYE_KEY_PASSWORD") ?: ""
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // sshj / Bouncy Castle use newer java.* APIs
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // Extract .so files to disk at install time. libcloudflared.so is not a library,
            // it is the cloudflared *executable* dressed as one: Android only lets an app exec
            // files from its nativeLibraryDir (Android 10+ W^X). See docs/ARCHITECTURE.md.
            useLegacyPackaging = true
        }
        resources {
            // Signed-JAR leftovers from Bouncy Castle et al. would invalidate the APK signature.
            excludes += setOf(
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST"
            )
            // The three Bouncy Castle jars each carry this same multi-release OSGi manifest;
            // AGP refuses to guess which one wins, so pick the first (it's metadata Android
            // never reads). Same story for the licence texts several jars ship.
            pickFirsts += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/OSGI-INF/MANIFEST.MF",
                "META-INF/LICENSE.txt", "META-INF/LICENSE", "META-INF/NOTICE.txt", "META-INF/NOTICE"
            )
        }
    }
}

dependencies {
    // The HTTP server. One 51KB jar, no transitive dependencies, BSD-3-Clause.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // Pure-Java SSH, for the localhost.run / serveo.net fallback tunnels (Apache-2.0).
    implementation("com.hierynomus:sshj:0.40.0")
    // sshj wants an slf4j binding; phone-eye logs tunnel state itself, so bind to nop.
    implementation("org.slf4j:slf4j-nop:2.0.17")
    // Referenced directly (provider registration); sshj would pull it transitively anyway.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    testImplementation("junit:junit:4.13.2")
}

// --------------------------------------------------------------------------------------
// cloudflared (Apache-2.0, Cloudflare) — the tunnel binary, exactly as in pc-eye, except
// the launcher downloads it at run time and we must bake it into the APK. Fetched at a
// PINNED version, verified against a pinned SHA-256, and never committed to git (37 MB).
// Pattern adapted from Thinai PR #72 (thanks); .gitignore keeps the result out of git.
// Build without it (LAN + SSH tunnels only) with:  ./gradlew assembleDebug -PnoCloudflared
// --------------------------------------------------------------------------------------
val cloudflaredVersion = "2026.10.5"
val cloudflaredSha256 = "72e0d05a67e09da87cf495e409a28c3cc5994dd23fc66a982eebea6a55923b7d"
val cloudflaredRaw = layout.buildDirectory.file("cloudflared/cloudflared-$cloudflaredVersion").get().asFile
val cloudflaredLib = file("src/main/jniLibs/arm64-v8a/libcloudflared.so")

fun sha256Of(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

// Best-effort hunt for the NDK's llvm-strip via env vars / local.properties only:
// no AGP internal APIs, so a missing NDK is a log line, never a broken build.
fun Project.findLlvmStrip(): File? {
    val os = System.getProperty("os.name").lowercase()
    val host = when {
        os.contains("win") -> "windows-x86_64"
        os.contains("mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val exe = "toolchains/llvm/prebuilt/$host/bin/llvm-strip" + if (os.contains("win")) ".exe" else ""
    val roots = mutableListOf<File>()
    listOfNotNull(System.getenv("ANDROID_NDK_HOME"), System.getenv("ANDROID_NDK_ROOT"))
        .forEach { roots.add(File(it)) }
    val sdkDirs = mutableListOf<String>()
    listOfNotNull(System.getenv("ANDROID_SDK_ROOT"), System.getenv("ANDROID_HOME")).forEach { sdkDirs.add(it) }
    try {
        val localProps = rootProject.file("local.properties")
        if (localProps.exists()) {
            localProps.readLines().firstOrNull { it.startsWith("sdk.dir=") }
                ?.substringAfter("sdk.dir=")?.trim()?.let { sdkDirs.add(it) }
        }
    } catch (e: Exception) {
        logger.info("phone-eye: local.properties unreadable while hunting the NDK (${e.message})")
    }
    sdkDirs.forEach { sdk ->
        File("$sdk/ndk").listFiles()?.filter { it.isDirectory }?.sortedDescending()?.let { roots.addAll(it) }
        roots.add(File("$sdk/ndk-bundle"))
    }
    return roots.map { File(it, exe) }.firstOrNull { it.exists() }
}

val fetchCloudflared by tasks.registering {
    description = "Download the pinned cloudflared binary into jniLibs (git-ignored)"
    inputs.property("version", cloudflaredVersion)
    inputs.property("sha256", cloudflaredSha256)
    outputs.file(cloudflaredLib)
    onlyIf { !project.hasProperty("noCloudflared") }
    doLast {
        // Offline path: scripts/fetch-cloudflared.sh already placed a plausible binary.
        if (cloudflaredLib.exists() && cloudflaredLib.length() > 5_000_000 && !cloudflaredRaw.exists()) {
            val head = cloudflaredLib.inputStream().use { it.readNBytes(4) }
            if (head.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))) {
                logger.lifecycle("phone-eye: using pre-fetched libcloudflared.so (${cloudflaredLib.length() / 1_000_000} MB)")
                return@doLast
            }
        }
        if (!cloudflaredRaw.exists() || sha256Of(cloudflaredRaw) != cloudflaredSha256) {
            cloudflaredRaw.parentFile.mkdirs()
            val url = "https://github.com/cloudflare/cloudflared/releases/download/" +
                "$cloudflaredVersion/cloudflared-linux-arm64"
            logger.lifecycle("phone-eye: downloading cloudflared $cloudflaredVersion (~38 MB, once) ...")
            URI(url).toURL().openStream().use { input ->
                cloudflaredRaw.outputStream().use { input.copyTo(it) }
            }
            if (sha256Of(cloudflaredRaw) != cloudflaredSha256) {
                cloudflaredRaw.delete()
                throw GradleException(
                    "cloudflared checksum mismatch for $url — refusing to bake an unverified " +
                    "binary into the APK. If Cloudflare re-released the asset, update " +
                    "cloudflaredVersion/cloudflaredSha256 in app/build.gradle.kts AND scripts/fetch-cloudflared.sh."
                )
            }
        }
        cloudflaredLib.parentFile.mkdirs()
        val strip = project.findLlvmStrip()
        if (strip != null) {
            // The release binary ships Go debug info; stripping nearly halves the APK growth.
            val exit = ProcessBuilder(strip.path, "-o", cloudflaredLib.path, cloudflaredRaw.path)
                .inheritIO().start().waitFor()
            if (exit != 0) {
                logger.lifecycle("phone-eye: llvm-strip failed ($exit); shipping unstripped cloudflared")
                cloudflaredRaw.copyTo(cloudflaredLib, overwrite = true)
            } else {
                logger.lifecycle("phone-eye: libcloudflared.so stripped to ${cloudflaredLib.length() / 1_000_000} MB")
            }
        } else {
            logger.lifecycle("phone-eye: no NDK llvm-strip found; shipping unstripped cloudflared (larger APK, same behaviour)")
            cloudflaredRaw.copyTo(cloudflaredLib, overwrite = true)
        }
    }
}

tasks.named("preBuild") { dependsOn(fetchCloudflared) }
