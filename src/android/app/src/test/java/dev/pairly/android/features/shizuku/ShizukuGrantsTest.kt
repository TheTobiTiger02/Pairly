package dev.pairly.android.features.shizuku

import dev.pairly.android.ui.features.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuGrantsTest {
    @Test
    fun unknownKeysGrantNothing() {
        assertTrue(ShizukuGrants.steps("clipboard").isEmpty())
        assertTrue(ShizukuGrants.steps("../../etc").isEmpty())
        assertTrue(ShizukuGrants.steps("").isEmpty())
    }

    @Test
    fun everySetupFeatureCanBeGrantedOnEachAndroidVersion() {
        val setupKeys = Feature.setupOrder.map { it.key }
        assertEquals(setupKeys.toSet(), ShizukuGrants.keys.toSet())
        ShizukuGrants.keys.filter { it != "alerts" }.forEach { key ->
            assertTrue("$key on API 26", ShizukuGrants.steps(key, sdk = 26).isNotEmpty())
        }
        ShizukuGrants.keys.forEach { key -> assertTrue("$key on API 36", ShizukuGrants.steps(key, sdk = 36).isNotEmpty()) }
    }

    @Test
    fun storageAndPhotosFollowTheAndroidVersion() {
        assertEquals(listOf(ShizukuGrants.Step.AppOp("MANAGE_EXTERNAL_STORAGE")), ShizukuGrants.steps("files", sdk = 30))
        assertEquals(
            listOf(ShizukuGrants.Step.Permission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)),
            ShizukuGrants.steps("files", sdk = 29)
        )
        assertTrue(ShizukuGrants.steps("alerts", sdk = 32).isEmpty())
        assertEquals(2, ShizukuGrants.steps("photos", sdk = 33).size)
    }
}
