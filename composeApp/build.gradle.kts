import java.util.Properties

val appVersionCode = 28
val appVersionName = "0.7.6"

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
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.datetime)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.sqldelight.jvm)
            implementation(libs.ktor.client.mock)
        }

        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.koin.android)
            implementation(libs.coroutines.android)
            implementation(libs.sqldelight.android)
            implementation(libs.datastore.preferences)
            implementation(libs.graphics.shapes)
            implementation(libs.haze)
            implementation(libs.valhalla.mobile)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.okhttp)
            implementation("org.maplibre.gl:android-sdk:13.0.2")
            implementation("io.livekit:livekit-android:2.25.3") {
                exclude(group = "com.github.davidliu", module = "audioswitch")
            }
            implementation(files("libs/audioswitch-039a35aefab7747c557242fa216c9ea11743b604.aar"))
            implementation(files("libs/brouter-1.7.10-all.jar"))
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
        versionCode = appVersionCode
        versionName = appVersionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    applicationVariants.all {
        val variantBuildType = buildType.name
        outputs.all {
            (this as com.android.build.gradle.api.ApkVariantOutput).outputFileName =
                "volty-$appVersionName-$variantBuildType.apk"
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // Keep x86 ABIs for debug/emulator builds, but do not ship them in production.
        variant.packaging.jniLibs.excludes.add("**/x86/*.so")
        variant.packaging.jniLibs.excludes.add("**/x86_64/*.so")
    }
}

sqldelight {
    databases {
        create("VoltyDatabase") {
            packageName.set("ru.sodovaya.volty.data.db")
            generateAsync.set(false)

            // Snapshots of the schema at each version, committed to the repo.
            // `generateCommonMainVoltyDatabaseSchema` writes <currentVersion>.db
            // here. Verification replays, for every committed N.db, the
            // migrations numbered N and up, then diffs the result against the
            // .sq definitions — so N.db is the schema *at* version N, and only
            // migrations >= N are exercised by it. That is why 1.db..6.db are
            // all committed: with only the newest snapshot no migration would
            // run and the verifier would pass against anything.
            //
            // When adding migration N.sqm, N.db must already be here (it is the
            // "before" state); afterwards run the generate task once more so
            // (N+1).db lands here for the migration after that.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))

            // Makes `verifyCommonMainVoltyDatabaseMigration` — which `check`
            // and the aggregate `verifySqlDelightMigration` both pull in —
            // fail when a migration drifts from the .sq definitions: a
            // NOT NULL, DEFAULT or column-type divergence that the repository
            // tests cannot see.
            //
            // Windows: this puts the generate/verify tasks — and therefore
            // `check` and `build`, which used to be green anywhere — on the
            // sqlite-jdbc native library. The SQLDelight Gradle worker can start
            // with an empty environment, so its java.io.tmpdir resolves to
            // C:\WINDOWS, sqlite-jdbc cannot extract the .dll there, and the
            // task dies with
            //   AccessDeniedException: C:\WINDOWS\sqlite-...-sqlitejdbc.dll.lck
            // Neither org.gradle.jvmargs nor _JAVA_OPTIONS reaches that worker.
            // Fix: copy sqlitejdbc.dll (from the sqlite-jdbc jar in ~/.gradle
            // caches, or from an earlier successful extraction in %TEMP%) into
            // %USERPROFILE%\.gradle\workers\ — the worker's working directory,
            // which is on its java.library.path, so the System.loadLibrary
            // fallback finds it. Linux and macOS are unaffected.
            verifyMigrations.set(true)
        }
    }
}
