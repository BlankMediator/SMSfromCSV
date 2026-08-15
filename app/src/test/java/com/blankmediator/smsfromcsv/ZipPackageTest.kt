package com.blankmediator.smsfromcsv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipPackageTest {
    @Test
    fun importsRecipientsCsvAndResolvesImagesFolder() {
        val zip = makeZip(
            "recipients.csv" to "name,phone,image,message\nJane,+61400000001,invite.jpg,Hi Jane\n".toByteArray(),
            "images/invite.jpg" to byteArrayOf(1, 2, 3)
        )
        val root = createTempDir(prefix = "smsfromcsv-test-")
        try {
            val imported = ZipPackageImporter.importZip("batch.zip", ByteArrayInputStream(zip), root)
            assertEquals(1, imported.table.rows.size)
            assertTrue(imported.isZip)
            assertNotNull(imported.resolveImage("invite.jpg"))
            assertNotNull(imported.resolveImage("images/invite.jpg"))
            assertNull(imported.resolveImage("missing.jpg"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZipTraversal() {
        val zip = makeZip(
            "recipients.csv" to "phone,message\n+61400000001,Hi\n".toByteArray(),
            "../outside.jpg" to byteArrayOf(1)
        )
        val root = createTempDir(prefix = "smsfromcsv-test-")
        try {
            ZipPackageImporter.importZip("batch.zip", ByteArrayInputStream(zip), root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsAbsoluteAndDrivePaths() {
        assertNull(ZipPackageImporter.normalizeRelativePath("/etc/passwd"))
        assertNull(ZipPackageImporter.normalizeRelativePath("C:/temp/a.jpg"))
        assertNull(ZipPackageImporter.normalizeRelativePath("images/../a.jpg"))
        assertEquals("images/a.jpg", ZipPackageImporter.normalizeRelativePath("images\\a.jpg"))
    }

    private fun makeZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
