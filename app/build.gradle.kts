plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

android {
    namespace = "com.morgan.wakepc"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.morgan.wakepc"
        minSdk = 31
        // 36 is what the S26 Ultra actually runs, matching the chat app's choice.
        targetSdk = 36
        versionCode = 22
        versionName = "0.10.0"
    }

    buildTypes {
        release {
            // Personal sideloaded app: the debug key is fine and keeps installs friction-free.
            signingConfig = signingConfigs.getByName("debug")
            // R8 broke this app once (0.7.0 would not launch), so minification
            // is back on only alongside explicit keep rules for the
            // reflectively-instantiated Glance callbacks and widget classes,
            // and only after verifying a minified build actually starts.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Off by default since AGP 8 — the settings screen reads VERSION_NAME
        // from here so the version lives in exactly one place.
        buildConfig = true
    }
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(files(rootDir.resolve("config/detekt/detekt.yml")))
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

dependencies {
    implementation(platform(libs.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.gms.code.scanner)
    implementation(libs.androidx.glance.appwidget)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
