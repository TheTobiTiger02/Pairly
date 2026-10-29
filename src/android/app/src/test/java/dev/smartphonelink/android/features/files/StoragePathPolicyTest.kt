package dev.smartphonelink.android.features.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePathPolicyTest {

    private val root: File = Files.createTempDirectory("storage-policy-root").toFile()

    @Test
    fun blankPathResolvesToRoot() {
        assertEquals(root.canonicalFile, StoragePathPolicy.resolve(root, null))
        assertEquals(root.canonicalFile, StoragePathPolicy.resolve(root, ""))
        assertEquals(root.canonicalFile, StoragePathPolicy.resolve(root, "/"))
        assertEquals(root.canonicalFile, StoragePathPolicy.resolve(root, "."))
    }

    @Test
    fun childPathsResolveInsideRoot() {
        File(root, "DCIM/Camera").mkdirs()
        val resolved = StoragePathPolicy.resolve(root, "/DCIM/Camera")
        assertNotNull(resolved)
        assertTrue(resolved!!.path.startsWith(root.canonicalPath))
    }

    @Test
    fun traversalEscapesAreRejected() {
        assertNull(StoragePathPolicy.resolve(root, "../"))
        assertNull(StoragePathPolicy.resolve(root, "../../etc/passwd"))
        assertNull(StoragePathPolicy.resolve(root, "/DCIM/../../secret"))
        assertNull(StoragePathPolicy.resolve(root, "DCIM/../../.."))
    }

    @Test
    fun redundantSeparatorsStayInsideRoot() {
        File(root, "Download").mkdirs()
        assertNotNull(StoragePathPolicy.resolve(root, "//Download//"))
        assertNotNull(StoragePathPolicy.resolve(root, "/./Download"))
    }

    @Test
    fun inlineLimitRejectsOversizedFiles() {
        assertTrue(!StoragePathPolicy.exceedsInlineLimit(StoragePathPolicy.MAX_INLINE_FILE_BYTES))
        assertTrue(StoragePathPolicy.exceedsInlineLimit(StoragePathPolicy.MAX_INLINE_FILE_BYTES + 1))
        assertTrue(StoragePathPolicy.tooLargeMessage(64L * 1024 * 1024).contains("8 MiB"))
    }
}
