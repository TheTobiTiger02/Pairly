import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Screenshot tests: ./gradlew :app:recordRoborazziDebug renders every screen (all design
    // variants, light/dark, English/German) to app/build/outputs/roborazzi; verifyRoborazziDebug
    // compares against them.
    id("io.github.takahirom.roborazzi") version "1.40.1"
}

val signingProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun signingInput(propertyName: String, fileKey: String): String? =
    providers.gradleProperty(propertyName).orNull
        ?: System.getenv(propertyName)
        ?: signingProperties.getProperty(fileKey)

val releaseStorePath = signingInput("PAIRLY_ANDROID_KEYSTORE", "storeFile")
val releaseStorePassword = signingInput("PAIRLY_ANDROID_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingInput("PAIRLY_ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingInput("PAIRLY_ANDROID_KEY_PASSWORD", "keyPassword")

android {
    namespace = "dev.pairly.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pairly.android"
        minSdk = 26
        targetSdk = 36
        // Release builds get both from the git tag (scripts/Build-Release.ps1); local builds are 0.0.0-dev.
        versionCode = providers.gradleProperty("PairlyVersionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("PairlyVersionName").orNull ?: "0.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    val releaseSigning = if (
        !releaseStorePath.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()
    ) {
        signingConfigs.create("privateRelease") {
            storeFile = rootProject.file(releaseStorePath)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    } else {
        null
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            releaseSigning?.let { signingConfig = it }
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

tasks.withType<Test>().configureEach {
    // Optional Maven mirror for Robolectric's Android runtime jars (-ProbolectricRepoUrl=...), for
    // environments where Maven Central rate-limits downloads.
    providers.gradleProperty("robolectricRepoUrl").orNull?.let { systemProperty("robolectric.dependency.repo.url", it) }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(project(":streamcore"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.compose.animation:animation:1.7.8")
    implementation("androidx.compose.foundation:foundation:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.8")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // QR scanning UI from Google Play services: no camera permission or camera code in the app.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // The scanner pulls in Fragment 1.0; Activity Result APIs need 1.3+ (release lint enforces it).
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.7.8")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.8")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
