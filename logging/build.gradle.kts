// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.heartline.logging"

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    api(project(":shared"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
