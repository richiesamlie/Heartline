// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.paparazzi) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.ksp) apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    plugins.withId("com.android.base") {
        extensions.configure(CommonExtension::class.java) {
            compileSdk = 37
            compileOptions.apply {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions.apply {
                unitTests.isIncludeAndroidResources = true
                unitTests.all { test ->
                    // Robolectric (SDK 36 sandbox) on JDK 21 needs these internals opened.
                    test.jvmArgs(
                        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                        "--add-opens=java.base/java.io=ALL-UNNAMED"
                    )
                }
            }
        }
        plugins.withId("org.jetbrains.kotlin.plugin.compose") {
            extensions.configure(CommonExtension::class.java) {
                buildFeatures.compose = true
            }
        }
    }

    plugins.withId("com.android.application") {
        extensions.configure(ApplicationExtension::class.java) {
            signingConfigs {
                getByName("debug") {
                    storeFile = rootProject.file("keystore/debug.keystore")
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
                // The release key comes from the environment (the Build workflow's secrets). Without it,
                // every build falls back to the shared test key (local builds, pull requests).
                System.getenv("HEARTLINE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { keystore ->
                    create("release") {
                        storeFile = file(keystore)
                        storePassword = System.getenv("HEARTLINE_KEYSTORE_PASSWORD")
                        keyAlias = System.getenv("HEARTLINE_KEY_ALIAS")
                        keyPassword = System.getenv("HEARTLINE_KEY_PASSWORD")
                    }
                }
                // v1 (JAR) signatures besides v2: with minSdk 24+ the build leaves v1 out, and some phone
                // installers refuse an APK without its META-INF signature files (ColorOS on a OnePlus Ace 3:
                // "安装包没有签名文件", the package has no signature file).
                configureEach {
                    enableV1Signing = true
                    enableV2Signing = true
                }
            }

            buildTypes {
                debug {
                    signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
                }
                release {
                    // R8 shrinks the libraries only (proguard-rules.pro keeps Heartline's own code whole and
                    // unrenamed). Resources are all kept: some are read only by the system, such as the Wear OS
                    // capability the phone and watch find each other by. Signed with the release key when the
                    // environment provides one; opt into debug signing explicitly with -Pheartline.allowDebugSigning=true.
                    isMinifyEnabled = true
                    isShrinkResources = false
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                    val allowDebugSigning = findProperty("heartline.allowDebugSigning")?.toString()?.toBoolean() == true
                    signingConfig = signingConfigs.findByName("release")
                        ?: if (allowDebugSigning) signingConfigs.getByName("debug") else null
                }
            }

            buildFeatures.buildConfig = true
            lint.checkReleaseBuilds = false

            // Exported Room schemas, readable by MigrationTestHelper in Robolectric tests.
            sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")

            testOptions.unitTests.all { test ->
                val requested = gradle.startParameter.taskNames
                val wantsPaparazzi = requested.any { task -> task.contains("Paparazzi", ignoreCase = true) }
                val wantsUnit = requested.any { task ->
                    val name = task.substringAfterLast(':')
                    name == "test" || (name.startsWith("test") && name.endsWith("UnitTest")) || name == "check"
                }
                // Paparazzi (layoutlib) and Robolectric cannot share a JVM. When invoked separately,
                // each suite reuses a single JVM (forkEvery = 0); when combined in one CLI call, fork per class.
                test.forkEvery = if (wantsPaparazzi && wantsUnit) 1 else 0
                test.maxParallelForks = 1
                val hasCliArgs = gradle.startParameter.taskRequests.any { req ->
                    req.args.any { arg -> arg == "--tests" || arg.startsWith("--tests=") }
                }
                val hasFilterPatterns = (test.filter as? DefaultTestFilter)
                    ?.commandLineIncludePatterns
                    ?.isNotEmpty() == true
                if (!hasCliArgs && !hasFilterPatterns) {
                    if (wantsPaparazzi && !wantsUnit) {
                        test.filter {
                            isFailOnNoMatchingTests = false
                            includeTestsMatching("*ScreenshotTest*")
                            includeTestsMatching("*BrandAssetsTest*")
                        }
                    } else if (wantsUnit && !wantsPaparazzi) {
                        test.filter {
                            isFailOnNoMatchingTests = false
                            excludeTestsMatching("*ScreenshotTest*")
                            excludeTestsMatching("*BrandAssetsTest*")
                        }
                    }
                }
                test.jvmArgs("-Duser.timezone=UTC")
            }
        }

        // Unit and screenshot tests run on debug; the play build type only differs in packaging.
        tasks.matching { it.name == "testPlayUnitTest" }.configureEach { enabled = false }
    }
}
