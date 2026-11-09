package dev.pairly.android.features.shizuku

import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * Shizuku starts the helper by class name in another process, so a release build only works if R8
 * keeps that class. Keep rules name classes as plain text, and a rename doesn't update them.
 */
class KeepRulesTest {
    private val rules = File("proguard-rules.pro").readText()

    @Test
    fun helperServiceIsKept() {
        assertTrue(rules.contains("-keep class ${ShizukuShellService::class.java.name} { *; }"))
    }

    @Test
    fun everyKeptPairlyClassExists() {
        Regex("""-keep class (dev\.pairly\.[\w.$]+)""").findAll(rules).map { it.groupValues[1] }.forEach { Class.forName(it) }
    }
}
