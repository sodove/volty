import java.util.Base64
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The task inspects the assembled APK contents")
abstract class VerifyProductionReleaseTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apk: RegularFileProperty

    @get:Input
    abstract val forbiddenMarkers: ListProperty<String>

    @TaskAction
    fun verify() {
        val apkFile = apk.get().asFile
        require(apkFile.isFile) { "Release APK was not produced: ${apkFile.absolutePath}" }

        val (dexMarkers, assetMarkers) = ZipFile(apkFile).use { archive ->
            archive.entries().asSequence().toList()
                .let { entries ->
                    val dexMarkers = entries
                        .filter { it.name.endsWith(".dex") }
                        .flatMap { entry ->
                            val bytes = archive.getInputStream(entry).use { it.readBytes() }
                            val text = bytes.toString(Charsets.ISO_8859_1)
                            forbiddenMarkers.get().filter(text::contains).asSequence()
                        }
                        .toSet()
                    val assetMarkers = entries
                        .filter { it.name.startsWith("assets/offline-routing/") }
                        .map { it.name }
                        .toSet()
                    dexMarkers to assetMarkers
                }
        }
        require(dexMarkers.isEmpty() && assetMarkers.isEmpty()) {
            "Production APK still contains the debug-only BRouter payload: " +
                "dex=$dexMarkers assets=$assetMarkers"
        }
    }
}

val appVersionCode = 31
val appVersionName = "0.7.7"
val productionReleaseGate = providers.gradleProperty("voltyProductionRelease").orNull?.let { value ->
    value.toBooleanStrictOrNull()
        ?: error("voltyProductionRelease must be true or false")
} ?: false
val offlineCatalogUrl = providers.gradleProperty("voltyOfflineCatalogUrl").orNull.orEmpty()
val offlineManifestKeyId = providers.gradleProperty("voltyOfflineManifestKeyId").orNull.orEmpty()
val offlineManifestPublicKey = providers.gradleProperty("voltyOfflineManifestPublicKey").orNull.orEmpty()
val offlineRuntimeEnabled = productionReleaseGate || offlineCatalogUrl.isNotBlank()

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
            implementation(libs.valhalla.models)
            implementation(libs.valhalla.models.config)
            implementation(libs.bouncycastle)
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
    // keystore (fresh clone / CI without secrets) ordinary development builds
    // remain usable; the explicit production gate below fails closed.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signingSecret(propKey: String, envKey: String): String? =
        keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

    val storeFilePath = signingSecret("storeFile", "VOLTY_KEYSTORE_FILE") ?: "123.jks"
    val releaseStoreFile = rootProject.file(storeFilePath)
    val releaseStorePassword = signingSecret("storePassword", "VOLTY_KEYSTORE_PASSWORD")
    val releaseKeyAlias = signingSecret("keyAlias", "VOLTY_KEY_ALIAS")
    val releaseKeyPassword = signingSecret("keyPassword", "VOLTY_KEY_PASSWORD")
    val hasReleaseKeystore = releaseStoreFile.exists() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    if (productionReleaseGate) {
        require(hasReleaseKeystore) {
            "Production release requires a configured release keystore"
        }
        require(offlineCatalogUrl.startsWith("https://")) {
            "Production release requires -PvoltyOfflineCatalogUrl=https://..."
        }
        require(offlineManifestKeyId.isNotBlank() && offlineManifestKeyId !in setOf("UNSIGNED_DEV", "UNSIGNED")) {
            "Production release requires -PvoltyOfflineManifestKeyId"
        }
        val publicKey = runCatching {
            Base64.getDecoder().decode(offlineManifestPublicKey)
        }.getOrNull()
        require(publicKey?.size == 32) {
            "Production release requires a Base64 Ed25519 public key (32 raw bytes)"
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    namespace = "ru.sodovaya.volty"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/previous-compilation-data.bin"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            // The release runtime is always the Valhalla/OfflineFirst path.
            // BRouter is retained only as a debug compatibility fallback.
            buildConfigField("boolean", "VOLTY_OFFLINE_RUNTIME_ENABLED", "true")
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("releaseX86") {
            // A production-equivalent variant for native Valhalla smoke tests
            // on an x86_64 host. It deliberately keeps the release application
            // id/signing/runtime and differs only in ABI packaging.
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "VOLTY_OFFLINE_RUNTIME_ENABLED", "true")
        }
        getByName("debug") {
            isMinifyEnabled = false
            buildConfigField("boolean", "VOLTY_OFFLINE_RUNTIME_ENABLED", offlineRuntimeEnabled.toString())
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    defaultConfig {
        applicationId = "ru.sodovaya.volty"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["voltyOfflineCatalogUrl"] = offlineCatalogUrl
        manifestPlaceholders["voltyOfflineManifestKeyId"] = offlineManifestKeyId
        manifestPlaceholders["voltyOfflineManifestPublicKey"] = offlineManifestPublicKey
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

if (productionReleaseGate) {
    val productionApk = layout.buildDirectory
        .file("outputs/apk/release/volty-$appVersionName-release.apk")
    tasks.register<VerifyProductionReleaseTask>("verifyProductionReleaseOmitsBRouter") {
        apk.set(productionApk)
        forbiddenMarkers.set(listOf("btools/", "btools.", "RoutingEngine"))
        dependsOn("assembleRelease")
    }
    val productionX86Apk = layout.buildDirectory
        .file("outputs/apk/releaseX86/volty-$appVersionName-releaseX86.apk")
    tasks.register<VerifyProductionReleaseTask>("verifyProductionReleaseX86OmitsBRouter") {
        apk.set(productionX86Apk)
        forbiddenMarkers.set(listOf("btools/", "btools.", "RoutingEngine"))
        dependsOn("assembleReleaseX86")
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
