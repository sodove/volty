import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3) {
                version { strictly("1.10.0-alpha04") }
            }
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.decompose.core)
            implementation(libs.decompose.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.kable.core)
            implementation(libs.datetime)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.sqldelight.jvm)
        }

        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.koin.android)
            implementation(libs.coroutines.android)
            implementation(libs.sqldelight.android)
            implementation(libs.datastore.preferences)
            implementation(libs.graphics.shapes)
        }
    }
}

android {
    // Release signing secrets — never hard-coded. Read from a gitignored
    // keystore.properties at the repo root (so Android Studio signs reliably
    // without OS env vars), falling back to environment variables for CI.
    // Properties: storeFile (root-relative), storePassword, keyAlias, keyPassword.
    // See keystore.properties.example. When neither source provides a usable
    // keystore (fresh clone / CI without secrets) the release config is skipped
    // and debug falls back to the default debug keystore so the build still works.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signingSecret(propKey: String, envKey: String): String? =
        keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

    val storeFilePath = signingSecret("storeFile", "VOLTY_KEYSTORE_FILE") ?: "123.jks"
    val releaseStoreFile = rootProject.file(storeFilePath)
    val releaseStorePassword = signingSecret("storePassword", "VOLTY_KEYSTORE_PASSWORD")
    val hasReleaseKeystore = releaseStoreFile.exists() && releaseStorePassword != null

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = signingSecret("keyAlias", "VOLTY_KEY_ALIAS")
                keyPassword = signingSecret("keyPassword", "VOLTY_KEY_PASSWORD")
            }
        }
    }

    namespace = "ru.sodovaya.volty"
    compileSdk = 36

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/previous-compilation-data.bin"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    defaultConfig {
        applicationId = "ru.sodovaya.volty"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

sqldelight {
    databases {
        create("VoltyDatabase") {
            packageName.set("ru.sodovaya.volty.data.db")
            generateAsync.set(false)
        }
    }
}
