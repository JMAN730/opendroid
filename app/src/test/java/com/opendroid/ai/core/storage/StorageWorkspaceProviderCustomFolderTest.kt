package com.opendroid.ai.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the custom-folder (SAF) code paths of [StorageWorkspaceProvider] against an
 * in-memory [FakeDocumentNode] tree. No Android framework is involved, so these run as plain
 * JVM tests.
 */
class StorageWorkspaceProviderCustomFolderTest {

    private lateinit var root: FakeDocumentNode

    @Before
    fun setUp() {
        root = FakeDocumentNode.root()
    }

    // ── copy ─────────────────────────────────────────────────────────────

    @Test
    fun `copyFileIn copies a file inside the custom folder and creates missing parents`() {
        root.putFile("report.txt", "quarterly")

        val result = StorageWorkspaceProvider.copyFileIn(root, "report.txt", "bak/report.txt")

        assertTrue(result.error ?: "", result.success)
        assertEquals("quarterly", root.textAt("report.txt"))
        assertEquals("quarterly", root.textAt("bak/report.txt"))
    }

    @Test
    fun `copyFileIn copies a directory tree`() {
        root.putFile("project/a.txt", "A")
        root.putFile("project/sub/b.txt", "B")

        val result = StorageWorkspaceProvider.copyFileIn(root, "project", "archive/project")

        assertTrue(result.error ?: "", result.success)
        assertEquals("A", root.textAt("archive/project/a.txt"))
        assertEquals("B", root.textAt("archive/project/sub/b.txt"))
        assertEquals("A", root.textAt("project/a.txt"))
    }

    @Test
    fun `copyFileIn overwrites an existing destination file`() {
        root.putFile("src.txt", "new")
        root.putFile("dst.txt", "old content that is longer")

        val result = StorageWorkspaceProvider.copyFileIn(root, "src.txt", "dst.txt")

        assertTrue(result.error ?: "", result.success)
        assertEquals("new", root.textAt("dst.txt"))
    }

    @Test
    fun `copyFileIn fails when the source is missing and creates nothing`() {
        val result = StorageWorkspaceProvider.copyFileIn(root, "missing.txt", "bak/missing.txt")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("does not exist"))
        assertNull(root.nodeAt("bak"))
    }

    @Test
    fun `copyFileIn refuses to copy a folder into itself`() {
        root.putFile("project/a.txt", "A")

        val result = StorageWorkspaceProvider.copyFileIn(root, "project", "project/inner")

        assertFalse(result.success)
        assertNull(root.nodeAt("project/inner"))
    }

    // ── move ─────────────────────────────────────────────────────────────

    @Test
    fun `moveFileIn moves a file and removes the original`() {
        root.putFile("notes.txt", "hello")

        val result = StorageWorkspaceProvider.moveFileIn(root, "notes.txt", "archive/notes.txt")

        assertTrue(result.error ?: "", result.success)
        assertNull(root.nodeAt("notes.txt"))
        assertEquals("hello", root.textAt("archive/notes.txt"))
    }

    @Test
    fun `moveFileIn moves a directory tree`() {
        root.putFile("project/a.txt", "A")
        root.putFile("project/sub/b.txt", "B")

        val result = StorageWorkspaceProvider.moveFileIn(root, "project", "old/project")

        assertTrue(result.error ?: "", result.success)
        assertNull(root.nodeAt("project"))
        assertEquals("A", root.textAt("old/project/a.txt"))
        assertEquals("B", root.textAt("old/project/sub/b.txt"))
    }

    @Test
    fun `moveFileIn fails when the source is missing`() {
        val result = StorageWorkspaceProvider.moveFileIn(root, "missing.txt", "x.txt")

        assertFalse(result.success)
        assertNull(root.nodeAt("x.txt"))
    }

    @Test
    fun `moveFileIn refuses to move a folder into itself and keeps the source`() {
        root.putFile("project/a.txt", "A")

        val result = StorageWorkspaceProvider.moveFileIn(root, "project", "project/sub/project")

        assertFalse(result.success)
        assertEquals("A", root.textAt("project/a.txt"))
    }

    // ── zip / unzip ──────────────────────────────────────────────────────

    @Test
    fun `zipFilesIn and unzipFileIn round-trip a directory inside the custom folder`() {
        root.putFile("subfolder/hello.txt", "Hello OpenDroid!")
        root.putFile("subfolder/nested/deep.txt", "deep")

        val zipResult = StorageWorkspaceProvider.zipFilesIn(root, "subfolder", "backup.zip")
        assertTrue(zipResult.error ?: "", zipResult.success)
        val zip = root.nodeAt("backup.zip")
        assertNotNull(zip)
        assertTrue(zip!!.length() > 0)

        val unzipResult = StorageWorkspaceProvider.unzipFileIn(root, "backup.zip", "unzipped")
        assertTrue(unzipResult.error ?: "", unzipResult.success)
        assertEquals("Hello OpenDroid!", root.textAt("unzipped/subfolder/hello.txt"))
        assertEquals("deep", root.textAt("unzipped/subfolder/nested/deep.txt"))
    }

    @Test
    fun `zipFilesIn does not include the archive in itself when written inside the source`() {
        root.putFile("docs/a.txt", "A")

        val zipResult = StorageWorkspaceProvider.zipFilesIn(root, "docs", "docs/docs.zip")
        assertTrue(zipResult.error ?: "", zipResult.success)

        val unzipResult = StorageWorkspaceProvider.unzipFileIn(root, "docs/docs.zip", "out")
        assertTrue(unzipResult.error ?: "", unzipResult.success)
        assertEquals("A", root.textAt("out/docs/a.txt"))
        assertNull(root.nodeAt("out/docs/docs.zip"))
    }

    @Test
    fun `zipFilesIn fails when the source is missing`() {
        val result = StorageWorkspaceProvider.zipFilesIn(root, "nope", "nope.zip")

        assertFalse(result.success)
        assertNull(root.nodeAt("nope.zip"))
    }

    @Test
    fun `unzipFileIn fails when the zip is missing`() {
        val result = StorageWorkspaceProvider.unzipFileIn(root, "missing.zip", "out")

        assertFalse(result.success)
        assertNull(root.nodeAt("out"))
    }

    @Test
    fun `unzipFileIn rejects zip-slip entries`() {
        val zipBytes = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.ZipOutputStream(bytes).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("../evil.txt"))
                zos.write("evil".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()
        root.putFile("evil.zip", "")
        root.nodeAt("evil.zip")!!.openOutputStream()!!.use { it.write(zipBytes) }

        val result = StorageWorkspaceProvider.unzipFileIn(root, "evil.zip", "out")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("ZipSlip"))
        assertNull(root.nodeAt("evil.txt"))
        assertNull(root.nodeAt("out/evil.txt"))
    }

    // ── single-file operations ───────────────────────────────────────────

    @Test
    fun `writeFileIn then readFileIn round-trips and listFilesIn sees the file`() {
        val write = StorageWorkspaceProvider.writeFileIn(root, "sub/notes.txt", "hi there")
        assertTrue(write.error ?: "", write.success)

        val read = StorageWorkspaceProvider.readFileIn(root, "sub/notes.txt")
        assertTrue(read.error ?: "", read.success)
        assertEquals("hi there", read.data)

        val list = StorageWorkspaceProvider.listFilesIn(root, "sub")
        assertTrue(list.error ?: "", list.success)
        assertTrue(list.data!!.contains("notes.txt"))
    }

    @Test
    fun `writeFileIn fails when an intermediate component is a file`() {
        root.putFile("notes.txt", "existing")

        val result = StorageWorkspaceProvider.writeFileIn(root, "notes.txt/x", "data")

        assertFalse(result.success)
        assertEquals("existing", root.textAt("notes.txt"))
    }

    @Test
    fun `deleteFileIn removes the file and reports missing files`() {
        root.putFile("gone.txt", "x")

        assertTrue(StorageWorkspaceProvider.deleteFileIn(root, "gone.txt").success)
        assertNull(root.nodeAt("gone.txt"))
        assertFalse(StorageWorkspaceProvider.deleteFileIn(root, "gone.txt").success)
    }

    @Test
    fun `deleteFileIn refuses to delete the custom folder root`() {
        root.putFile("keep.txt", "x")

        val result = StorageWorkspaceProvider.deleteFileIn(root, "")

        assertFalse(result.success)
        assertEquals("x", root.textAt("keep.txt"))
    }

    @Test
    fun `createDirectoryIn creates nested folders and tolerates existing ones`() {
        assertTrue(StorageWorkspaceProvider.createDirectoryIn(root, "a/b/c").success)
        assertTrue(root.nodeAt("a/b/c")!!.isDirectory)
        assertTrue(StorageWorkspaceProvider.createDirectoryIn(root, "a/b/c").success)

        root.putFile("file.txt", "x")
        assertFalse(StorageWorkspaceProvider.createDirectoryIn(root, "file.txt").success)
    }
}
