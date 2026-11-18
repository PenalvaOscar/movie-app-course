plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "com.encore.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.encore.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Loopback, forwarded to the host's `npm --prefix api start` with `adb reverse tcp:3000 tcp:3000`.
            // Not 10.0.2.2: Android 17 blocks private-network addresses for apps targeting API 37 unless
            // ACCESS_LOCAL_NETWORK is granted at runtime; loopback needs no permission.
            // Cleartext is allowed for loopback only, in src/debug/res/xml/network_security_config.xml.
            buildConfigField("String", "CATALOG_URL", "\"http://127.0.0.1:3000/catalog\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "CATALOG_URL", "\"https://movie-api-delta-six.vercel.app/catalog\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Stage 1 ships EN/ES only; DE/PT catch up later. Restore to error once all four locales are aligned.
        warning += "MissingTranslation"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

// user.db schemas (one JSON per version) are committed, so future migrations can be written and tested (O4).
room {
    schemaDirectory("$projectDir/schemas")
}

/**
 * Copies the ignored local catalog (`../data/movies.json`, from `npm --prefix api run setup`)
 * into a generated asset folder, so it ships as `assets/catalog/movies.json` without ever
 * touching src/. If the file is missing, the folder stays empty and the app falls back to
 * downloading the catalog. Build with `-Pencore.bundleCatalog=false` to test that path.
 */
abstract class CopyCatalogTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val catalog: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val target = File(out, "catalog").apply { mkdirs() }
        val source = catalog.files.firstOrNull { it.isFile }
        if (source == null) {
            logger.warn("Encore: no catalog bundled (data/movies.json missing or disabled); the app will download it.")
        } else {
            source.copyTo(File(target, "movies.json"), overwrite = true)
        }
    }
}

val bundleCatalog = providers.gradleProperty("encore.bundleCatalog").map { it.toBoolean() }.orElse(true)
val copyCatalog = tasks.register<CopyCatalogTask>("copyCatalog") {
    if (bundleCatalog.get()) catalog.from(rootProject.layout.projectDirectory.file("../data/movies.json"))
    outputDir.set(layout.buildDirectory.dir("generated/encoreCatalog"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyCatalog, CopyCatalogTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
