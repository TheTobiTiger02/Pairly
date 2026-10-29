plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The ADB-side server. Built as an APK because an APK is a zip with classes.dex inside, which is
// exactly what app_process needs on its CLASSPATH; it is never installed. The PC pushes it to
// /data/local/tmp and runs `app_process / dev.smartphonelink.server.Server` with shell rights.
android {
    namespace = "dev.smartphonelink.server"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.smartphonelink.server"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Everything here is reflection into hidden APIs guarded by runtime checks, which release lint
    // cannot see through; the checks are the safety net, not lint.
    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(project(":streamcore"))
}
