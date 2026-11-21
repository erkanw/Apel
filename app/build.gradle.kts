import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The release signing key stays out of git (PLAN §2.3): keystore.properties beside settings.gradle.kts names it.
// Without that file the release build is unsigned (as F-Droid builds it; F-Droid signs with its own key).
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.isFile) keystoreFile.inputStream().use { load(it) } }

// The licence texts shown in About → Licences come from the repository's own files, so they can't drift.
val licenseAssets = tasks.register<Sync>("licenseAssets") {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("NOTICE"))
    from(rootProject.file("licenses"))
    into(layout.buildDirectory.dir("generated/licenseAssets/licenses"))
}

android {
    namespace = "io.github.erkanw.vault"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.erkanw.vault"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystore.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // No Google-only blobs in the APK: F-Droid builds must be reproducible from the source.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets"))
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
    sourceSets["test"].java.srcDir("src/test/kotlin")
    sourceSets["androidTest"].java.srcDir("src/androidTest/kotlin")
}

tasks.named("preBuild") { dependsOn(licenseAssets) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    // HTTP for the provider, web pages and tool servers.
    implementation(libs.okhttp)

    // Sessions and messages on the phone (PLAN §5), and the provider sign-in in a Custom Tab.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    // Automations: catching missed runs, waiting for the network, and running when an alarm can't start the service (PLAN §8).
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    // The JVM unit tests need a real org.json; android.jar only has stubs.
    testImplementation(libs.org.json)
    // The user-database rules on the phone's real SQLite (PLAN §9), run with am instrument.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
