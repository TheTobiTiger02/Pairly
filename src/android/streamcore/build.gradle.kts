plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.pairly.streamcore"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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

// Deliberately dependency-free apart from the platform. :streamcore is shared by the installed
// app and by the ADB-side server process, so it must not pull in anything the shell UID cannot
// load, and must never reach for android.app.* or a hidden framework API.
dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Guards the one rule that keeps this module usable from both the installed app and the ADB-side
// server process: it may only touch public platform APIs that the shell UID can also load. A
// comment saying so is not enough, because breaking it compiles fine and fails at runtime on the
// ADB path only.
val verifyStreamCoreIsolation by tasks.registering {
    val sources = fileTree("src/main/java") { include("**/*.kt") }
    val projectDirectory = projectDir
    inputs.files(sources)

    doLast {
        val forbiddenPrefixes = listOf(
            "import android.app.",
            "import android.content.",
            "import androidx.",
            "import dalvik.system."
        )
        val offenders = mutableListOf<String>()

        sources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (forbiddenPrefixes.any { trimmed.startsWith(it) }) {
                    offenders += file.relativeTo(projectDirectory).path + ":" + (index + 1) + ": " + trimmed
                }
            }
        }

        if (offenders.isNotEmpty()) {
            val detail = offenders.joinToString(separator = System.lineSeparator())
            throw GradleException(
                "streamcore must stay loadable by the ADB server process. Move these to :app or :server:"
                    + System.lineSeparator() + detail
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyStreamCoreIsolation) }
