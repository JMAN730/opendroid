package com.opendroid.ai.core.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** With a custom SAF folder chosen, every file action works on that folder and never on the app workspace. */
@RunWith(RobolectricTestRunner::class)
class StorageCustomFolderTest {

    /** A document tree backed by a real directory, speaking the protocol DocumentFile uses. */
    class FolderDocumentsProvider : ContentProvider() {
        val rootDir: File by lazy { File(context!!.cacheDir, "saf-folder").apply { deleteRecursively(); mkdirs() } }
        var failStreams = false

        /** Children queries of this document throw, the first [failListingTimes] times. */
        var failListingOf: String? = null
        var failListingTimes = Int.MAX_VALUE

        /** Children queries of this document report that the provider is still fetching more rows. */
        var loadingListingOf: String? = null

        /**
         * Renames pick a free name the way FileSystemProvider's buildUniqueFile does on case-insensitive storage:
         * a name any sibling (the renamed file included) already has, ignoring case, gets a " (n)" suffix.
         */
        var uniquifyRenames = false

        /** Creates pick a free name the same way, so a name that differs only by case from a sibling gets a suffix. */
        var uniquifyCreates = false

        private fun uniqueName(dir: File, name: String): String {
            val taken = dir.list().orEmpty().map { it.lowercase() }.toSet()
            if (name.lowercase() !in taken) return name
            val base = name.substringBeforeLast('.')
            val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
            return generateSequence(1) { it + 1 }.map { "$base ($it)$ext" }.first { it.lowercase() !in taken }
        }

        private fun fileOf(documentId: String) = File(rootDir, documentId.removePrefix("root").trimStart('/'))

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID)
            val id = DocumentsContract.getDocumentId(uri)
            val isListing = uri.lastPathSegment == "children"
            if (isListing && id == failListingOf && failListingTimes > 0) {
                failListingTimes--
                throw IllegalStateException("Provider failed to list $id")
            }
            val ids = if (isListing) {
                fileOf(id).list().orEmpty().map { "$id/$it" }
            } else {
                listOf(id).filter { fileOf(it).exists() }
            }
            return MatrixCursor(columns).apply {
                ids.forEach { rowId -> addRow(columns.map { column(rowId, it) }) }
                if (isListing && id == loadingListingOf) {
                    extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
                }
            }
        }

        private fun column(id: String, name: String): Any? {
            val file = fileOf(id)
            return when (name) {
                Document.COLUMN_DOCUMENT_ID -> id
                Document.COLUMN_DISPLAY_NAME -> file.name
                Document.COLUMN_MIME_TYPE -> if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
                Document.COLUMN_SIZE -> file.length()
                else -> 0
            }
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            val uri = requireNotNull(extras?.getParcelable<Uri>("uri"))
            val id = DocumentsContract.getDocumentId(uri)
            val file = fileOf(id)
            val name = extras?.getString(Document.COLUMN_DISPLAY_NAME)
            val out = Bundle()
            when (method) {
                "android:createDocument" -> {
                    check(file.isDirectory) { "Parent is not a directory: $id" }
                    val actual = if (uniquifyCreates) uniqueName(file, name!!) else name!!
                    val child = File(file, actual)
                    val created = if (extras.getString(Document.COLUMN_MIME_TYPE) == Document.MIME_TYPE_DIR) {
                        child.mkdir()
                    } else {
                        child.createNewFile()
                    }
                    check(created) { "Couldn't create $actual in $id" }
                    out.putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, "$id/$actual"))
                }
                "android:deleteDocument" -> check(file.deleteRecursively()) { "Couldn't delete $id" }
                "android:renameDocument" -> {
                    val actual = if (uniquifyRenames) uniqueName(file.parentFile!!, name!!) else name!!
                    check(file.renameTo(File(file.parentFile, actual))) { "Couldn't rename $id" }
                    val renamedId = id.substringBeforeLast('/') + "/" + actual
                    out.putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, renamedId))
                }
                else -> error("Unexpected call: $method")
            }
            return out
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failStreams) throw FileNotFoundException("Provider refused to open $uri")
            return ParcelFileDescriptor.open(
                fileOf(DocumentsContract.getDocumentId(uri)),
                ParcelFileDescriptor.parseMode(mode),
            )
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private lateinit var context: Context
    private lateinit var provider: FolderDocumentsProvider
    private lateinit var workspace: File
    private val authority = "com.opendroid.test.folder"
    private val chosenFolder get() = DocumentsContract.buildTreeDocumentUri(authority, "root")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = Robolectric.setupContentProvider(FolderDocumentsProvider::class.java, authority)
        provider.rootDir
        StorageWorkspaceProvider.setCustomFolderUri(context, chosenFolder)
        workspace = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        workspace.deleteRecursively()
        workspace.mkdirs()
    }

    private fun folderFile(path: String) = File(provider.rootDir, path)

    private fun givenFolderFile(path: String, text: String) = folderFile(path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun givenWorkspaceFile(path: String, text: String) = File(workspace, path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun assertWorkspaceEmpty() = assertEquals(emptyList<String>(), workspace.list().orEmpty().toList())

    private fun givenFolderZip(path: String, vararg entries: Pair<String, String>) {
        folderFile(path).parentFile!!.mkdirs()
        ZipOutputStream(folderFile(path).outputStream()).use { zos ->
            entries.forEach { (name, text) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }
    }

    // ── Copy ─────────────────────────────────────────────────────────────

    @Test
    fun `copy works on the custom folder and ignores a same named workspace file`() {
        givenFolderFile("report.txt", "real")
        val stale = givenWorkspaceFile("report.txt", "stale")

        val result = StorageWorkspaceProvider.copyFile(context, "report.txt", "bak/report.txt")

        assertTrue(result.error, result.success)
        assertEquals("real", folderFile("bak/report.txt").readText())
        assertEquals("real", folderFile("report.txt").readText())
        assertEquals("stale", stale.readText())
        assertFalse(File(workspace, "bak").exists())
    }

    @Test
    fun `copy fails when the source is missing from the custom folder even if the workspace has it`() {
        givenWorkspaceFile("report.txt", "stale")

        val result = StorageWorkspaceProvider.copyFile(context, "report.txt", "bak/report.txt")

        assertFalse(result.success)
        assertFalse(folderFile("bak").exists())
        assertFalse(File(workspace, "bak").exists())
    }

    @Test
    fun `copying a folder copies everything inside it`() {
        givenFolderFile("src/a.txt", "a")
        givenFolderFile("src/deep/b.txt", "b")

        val result = StorageWorkspaceProvider.copyFile(context, "src", "out/copy")

        assertTrue(result.error, result.success)
        assertEquals("a", folderFile("out/copy/a.txt").readText())
        assertEquals("b", folderFile("out/copy/deep/b.txt").readText())
        assertWorkspaceEmpty()
    }

    @Test
    fun `copy overwrites an existing destination file completely`() {
        givenFolderFile("a.txt", "new")
        givenFolderFile("b.txt", "much longer old content")

        val result = StorageWorkspaceProvider.copyFile(context, "a.txt", "b.txt")

        assertTrue(result.error, result.success)
        assertEquals("new", folderFile("b.txt").readText())
    }

    @Test
    fun `copying onto itself into itself or over its parent fails and keeps the data`() {
        givenFolderFile("dir/a.txt", "a")

        for ((src, dest) in listOf("dir/a.txt" to "dir/a.txt", "dir/a.txt" to "DIR/A.TXT", "dir" to "dir/sub", "dir/a.txt" to "dir", "dir" to "/sdcard")) {
            val result = StorageWorkspaceProvider.copyFile(context, src, dest)

            assertFalse("$src -> $dest", result.success)
            assertEquals("a", folderFile("dir/a.txt").readText())
        }
        assertFalse(folderFile("dir/sub").exists())
    }

    @Test
    fun `copy and move can't climb out of the custom folder`() {
        givenFolderFile("a.txt", "a")

        for (dest in listOf("sub/..", "../a.txt", "sub/../b.txt")) {
            assertFalse(dest, StorageWorkspaceProvider.copyFile(context, "a.txt", dest).success)
            assertFalse(dest, StorageWorkspaceProvider.moveFile(context, "a.txt", dest).success)
        }

        assertEquals(listOf("a.txt"), provider.rootDir.list()!!.toList())
        assertEquals("a", folderFile("a.txt").readText())
    }

    @Test
    fun `a stream failure during copy is an error and doesn't touch the workspace`() {
        givenFolderFile("report.txt", "real")
        provider.failStreams = true

        val result = StorageWorkspaceProvider.copyFile(context, "report.txt", "bak.txt")

        assertFalse(result.success)
        assertWorkspaceEmpty()
    }

    // ── Move ─────────────────────────────────────────────────────────────

    @Test
    fun `move works on the custom folder and ignores a same named workspace file`() {
        givenFolderFile("report.txt", "real")
        val stale = givenWorkspaceFile("report.txt", "stale")

        val result = StorageWorkspaceProvider.moveFile(context, "report.txt", "bak/report.txt")

        assertTrue(result.error, result.success)
        assertEquals("real", folderFile("bak/report.txt").readText())
        assertFalse(folderFile("report.txt").exists())
        assertEquals("stale", stale.readText())
    }

    @Test
    fun `move fails when the source is missing from the custom folder even if the workspace has it`() {
        val stale = givenWorkspaceFile("report.txt", "stale")

        val result = StorageWorkspaceProvider.moveFile(context, "report.txt", "bak/report.txt")

        assertFalse(result.success)
        assertTrue(stale.exists())
        assertFalse(folderFile("bak").exists())
    }

    @Test
    fun `moving a folder into itself fails and keeps the data`() {
        givenFolderFile("dir/a.txt", "a")

        val result = StorageWorkspaceProvider.moveFile(context, "dir", "dir/sub/moved")

        assertFalse(result.success)
        assertEquals("a", folderFile("dir/a.txt").readText())
        assertFalse(folderFile("dir/sub").exists())
    }

    @Test
    fun `a move that can't write the copy keeps the source`() {
        givenFolderFile("report.txt", "real")
        provider.failStreams = true

        val result = StorageWorkspaceProvider.moveFile(context, "report.txt", "bak.txt")

        assertFalse(result.success)
        assertEquals("real", folderFile("report.txt").readText())
    }

    @Test
    fun `moving to a name that differs only by case renames it`() {
        givenFolderFile("dir/notes.txt", "keep")

        val result = StorageWorkspaceProvider.moveFile(context, "dir/notes.txt", "dir/NOTES.txt")

        assertTrue(result.error, result.success)
        assertEquals(listOf("NOTES.txt"), folderFile("dir").list()!!.toList())
        assertEquals("keep", folderFile("dir/NOTES.txt").readText())
    }

    @Test
    fun `a case only rename lands on the requested name when the provider uniquifies names`() {
        provider.uniquifyRenames = true
        givenFolderFile("dir/notes.txt", "keep")

        val result = StorageWorkspaceProvider.moveFile(context, "dir/notes.txt", "dir/NOTES.txt")

        assertTrue(result.error, result.success)
        assertEquals(listOf("NOTES.txt"), folderFile("dir").list()!!.toList())
        assertEquals("keep", folderFile("dir/NOTES.txt").readText())
    }

    @Test
    fun `a move keeps the source when the provider files the copy under a different name`() {
        provider.uniquifyCreates = true
        givenFolderFile("notes.txt", "keep")
        givenFolderFile("REPORT.txt", "other")

        val result = StorageWorkspaceProvider.moveFile(context, "notes.txt", "report.txt")

        assertFalse(result.success)
        assertEquals("keep", folderFile("notes.txt").readText())
        assertEquals("other", folderFile("REPORT.txt").readText())
        assertEquals(listOf("REPORT.txt", "notes.txt"), folderFile("").list()!!.sorted())
    }

    // ── Zip and unzip ────────────────────────────────────────────────────

    @Test
    fun `zip reads the custom folder and writes the zip there`() {
        givenFolderFile("src/a.txt", "a")
        givenFolderFile("src/deep/b.txt", "b")

        val result = StorageWorkspaceProvider.zipFiles(context, "src", "out/src.zip")

        assertTrue(result.error, result.success)
        val entries = ZipInputStream(folderFile("out/src.zip").inputStream()).use { zis ->
            generateSequence { zis.nextEntry }.associate { entry ->
                entry.name to zis.readBytes().decodeToString()
            }
        }
        assertEquals(mapOf("src/" to "", "src/a.txt" to "a", "src/deep/" to "", "src/deep/b.txt" to "b"), entries)
        assertWorkspaceEmpty()
    }

    @Test
    fun `zip fails when the source is only in the workspace`() {
        givenWorkspaceFile("src/a.txt", "a")

        val result = StorageWorkspaceProvider.zipFiles(context, "src", "src.zip")

        assertFalse(result.success)
        assertFalse(folderFile("src.zip").exists())
        assertFalse(File(workspace, "src.zip").exists())
    }

    @Test
    fun `a zip can't be the custom folder itself the source or inside the source`() {
        givenFolderFile("src/a.txt", "a")

        for ((src, zip) in listOf("src" to "", "src" to "src/a.zip", "src/a.txt" to "src/a.txt", "src" to "SRC/a.zip")) {
            val result = StorageWorkspaceProvider.zipFiles(context, src, zip)

            assertFalse("$src -> $zip", result.success)
            assertEquals("a", folderFile("src/a.txt").readText())
        }
        assertEquals(listOf("a.txt"), folderFile("src").list()!!.toList())
    }

    @Test
    fun `unzip reads the custom folder and writes there`() {
        givenFolderZip("in.zip", "dir/" to "", "dir/a.txt" to "a", "b.txt" to "b")

        val result = StorageWorkspaceProvider.unzipFile(context, "in.zip", "out/here")

        assertTrue(result.error, result.success)
        assertEquals("a", folderFile("out/here/dir/a.txt").readText())
        assertEquals("b", folderFile("out/here/b.txt").readText())
        assertWorkspaceEmpty()
    }

    @Test
    fun `unzip fails when the zip is only in the workspace`() {
        givenWorkspaceFile("in.zip", "not read")

        val result = StorageWorkspaceProvider.unzipFile(context, "in.zip", "out")

        assertFalse(result.success)
        assertFalse(folderFile("out").exists())
        assertFalse(File(workspace, "out").exists())
    }

    @Test
    fun `unzip rejects an entry that climbs out of the destination`() {
        givenFolderZip("in.zip", "../../evil.txt" to "evil")

        val result = StorageWorkspaceProvider.unzipFile(context, "in.zip", "out/here")

        assertFalse(result.success)
        assertFalse(folderFile("evil.txt").exists())
        assertFalse(folderFile("out/evil.txt").exists())
    }

    @Test
    fun `unzip can't spill over the custom folder root`() {
        givenFolderZip("in.zip", "a.txt" to "a")

        val result = StorageWorkspaceProvider.unzipFile(context, "in.zip", "/sdcard")

        assertFalse(result.success)
        assertFalse(folderFile("a.txt").exists())
    }

    // ── One backend per call ─────────────────────────────────────────────

    @Test
    fun `an operation spanning the custom folder and the workspace is refused`() {
        givenFolderFile("report.txt", "real")
        val inWorkspace = givenWorkspaceFile("in-workspace.txt", "stale")
        val workspacePath = inWorkspace.absolutePath.replace(File.separatorChar, '/')

        val results = listOf(
            StorageWorkspaceProvider.copyFile(context, "report.txt", workspacePath),
            StorageWorkspaceProvider.copyFile(context, workspacePath, "copied.txt"),
            StorageWorkspaceProvider.moveFile(context, "report.txt", workspacePath),
            StorageWorkspaceProvider.moveFile(context, workspacePath, "moved.txt"),
            StorageWorkspaceProvider.zipFiles(context, "report.txt", workspacePath),
            StorageWorkspaceProvider.zipFiles(context, workspacePath, "a.zip"),
            StorageWorkspaceProvider.unzipFile(context, workspacePath, "out"),
            StorageWorkspaceProvider.unzipFile(context, "report.txt", workspacePath),
        )

        results.forEach { assertFalse(it.success) }
        assertEquals("real", folderFile("report.txt").readText())
        assertEquals("stale", inWorkspace.readText())
        assertEquals(listOf("report.txt"), provider.rootDir.list()!!.toList())
    }

    // ── SAF failures never fall back to the workspace ────────────────────

    @Test
    fun `writing below a path that is a file fails instead of landing in the workspace`() {
        givenFolderFile("notes.txt", "keep")

        val result = StorageWorkspaceProvider.writeFile(context, "notes.txt/x", "lost")

        assertFalse(result.success)
        assertEquals("keep", folderFile("notes.txt").readText())
        assertWorkspaceEmpty()
    }

    @Test
    fun `writing over a directory fails instead of landing in the workspace`() {
        folderFile("dir").mkdirs()

        val result = StorageWorkspaceProvider.writeFile(context, "dir", "lost")

        assertFalse(result.success)
        assertTrue(folderFile("dir").isDirectory)
        assertWorkspaceEmpty()
    }

    @Test
    fun `a write stream failure fails instead of landing in the workspace`() {
        provider.failStreams = true

        val result = StorageWorkspaceProvider.writeFile(context, "report.txt", "lost")

        assertFalse(result.success)
        assertWorkspaceEmpty()
    }

    @Test
    fun `a write can't climb out of the custom folder`() {
        val result = StorageWorkspaceProvider.writeFile(context, "../escaped.txt", "lost")

        assertFalse(result.success)
        assertFalse(File(provider.rootDir.parentFile, "escaped.txt").exists())
        assertWorkspaceEmpty()
    }

    @Test
    fun `a read stream failure fails instead of returning a same named workspace file`() {
        givenFolderFile("report.txt", "real")
        givenWorkspaceFile("report.txt", "stale")
        provider.failStreams = true

        val result = StorageWorkspaceProvider.readFile(context, "report.txt")

        assertFalse(result.success)
        assertEquals(null, result.data)
    }

    @Test
    fun `creating a folder over a file fails instead of landing in the workspace`() {
        givenFolderFile("notes.txt", "keep")

        for (path in listOf("notes.txt", "notes.txt/sub")) {
            val result = StorageWorkspaceProvider.createDirectory(context, path)

            assertFalse(path, result.success)
        }
        assertEquals("keep", folderFile("notes.txt").readText())
        assertWorkspaceEmpty()
    }

    @Test
    fun `the plan from the report keeps a backup before the delete`() {
        assertTrue(StorageWorkspaceProvider.writeFile(context, "report.txt", "real").success)
        assertTrue(StorageWorkspaceProvider.copyFile(context, "report.txt", "bak/report.txt").success)
        assertTrue(StorageWorkspaceProvider.deleteFile(context, "report.txt").success)

        assertFalse(folderFile("report.txt").exists())
        val backup = StorageWorkspaceProvider.readFile(context, "bak/report.txt")
        assertEquals("real", backup.data)
    }

    // ── A listing that failed is an error, not an empty folder ───────────

    private fun givenDocsFolder() {
        givenFolderFile("docs/a.txt", "a")
        givenFolderFile("docs/deep/b.txt", "b")
    }

    private fun assertDocsFolderIntact() {
        assertEquals("a", folderFile("docs/a.txt").readText())
        assertEquals("b", folderFile("docs/deep/b.txt").readText())
    }

    @Test
    fun `a move keeps the source when listing the folder or a subfolder fails`() {
        for (failing in listOf("root/docs", "root/docs/deep")) {
            givenDocsFolder()
            provider.failListingOf = failing

            val result = StorageWorkspaceProvider.moveFile(context, "docs", "archive/docs")

            assertFalse(failing, result.success)
            assertDocsFolderIntact()
        }
    }

    @Test
    fun `a move keeps the source when a listing fails once and then recovers`() {
        givenDocsFolder()
        provider.failListingOf = "root/docs"
        provider.failListingTimes = 1

        val result = StorageWorkspaceProvider.moveFile(context, "docs", "archive/docs")

        assertFalse(result.success)
        assertDocsFolderIntact()
    }

    @Test
    fun `a move keeps the source when the provider is still loading the listing`() {
        givenDocsFolder()
        provider.loadingListingOf = "root/docs"

        val result = StorageWorkspaceProvider.moveFile(context, "docs", "archive/docs")

        assertFalse(result.success)
        assertDocsFolderIntact()
    }

    @Test
    fun `copy fails when listing a subfolder fails`() {
        givenDocsFolder()
        provider.failListingOf = "root/docs/deep"

        val result = StorageWorkspaceProvider.copyFile(context, "docs", "bak/docs")

        assertFalse(result.success)
        assertDocsFolderIntact()
    }

    @Test
    fun `zip fails when listing a subfolder fails`() {
        givenDocsFolder()
        provider.failListingOf = "root/docs/deep"

        val result = StorageWorkspaceProvider.zipFiles(context, "docs", "docs.zip")

        assertFalse(result.success)
    }

    @Test
    fun `listing a folder fails instead of calling it empty when the query fails`() {
        givenDocsFolder()
        provider.failListingOf = "root/docs"

        val result = StorageWorkspaceProvider.listFiles(context, "docs")

        assertFalse(result.success)
    }

    // ── A lost grant is an error, whatever other grants are left ─────────

    private fun givenChosenFolderGrantLostWithAnotherGrantLeft() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val leftover = DocumentsContract.buildTreeDocumentUri(authority, "previous")
        context.contentResolver.takePersistableUriPermission(leftover, flags)
        context.contentResolver.releasePersistableUriPermission(chosenFolder, flags)
        assertEquals(listOf(leftover), context.contentResolver.persistedUriPermissions.map { it.uri })
    }

    @Test
    fun `the chosen folder works while its grant is held alongside another`() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(DocumentsContract.buildTreeDocumentUri(authority, "previous"), flags)

        val result = StorageWorkspaceProvider.writeFile(context, "report.txt", "real")

        assertTrue(result.error, result.success)
        assertEquals("real", folderFile("report.txt").readText())
        assertWorkspaceEmpty()
    }

    @Test
    fun `no file action reaches the workspace after the chosen folder's grant is lost`() {
        val report = givenWorkspaceFile("report.txt", "stale")
        val zip = givenWorkspaceFile("in.zip", "not read")
        val before = workspace.walkTopDown().map { it.path }.toList()
        givenChosenFolderGrantLostWithAnotherGrantLeft()

        val results = mapOf(
            "list" to StorageWorkspaceProvider.listFiles(context, null),
            "read" to StorageWorkspaceProvider.readFile(context, "report.txt"),
            "write" to StorageWorkspaceProvider.writeFile(context, "report.txt", "lost"),
            "delete" to StorageWorkspaceProvider.deleteFile(context, "report.txt"),
            "mkdir" to StorageWorkspaceProvider.createDirectory(context, "dir"),
            "copy" to StorageWorkspaceProvider.copyFile(context, "report.txt", "bak/report.txt"),
            "move" to StorageWorkspaceProvider.moveFile(context, "report.txt", "bak/report.txt"),
            "zip" to StorageWorkspaceProvider.zipFiles(context, "report.txt", "report.zip"),
            "unzip" to StorageWorkspaceProvider.unzipFile(context, "in.zip", "out"),
        )

        results.forEach { (action, result) ->
            assertFalse(action, result.success)
            assertNotNull(action, result.error)
        }
        assertEquals(before, workspace.walkTopDown().map { it.path }.toList())
        assertEquals("stale", report.readText())
        assertEquals("not read", zip.readText())
    }

    @Test
    fun `a lost grant counts as no usable folder for the permissions screen`() {
        givenChosenFolderGrantLostWithAnotherGrantLeft()

        assertFalse(StorageWorkspaceProvider.hasCustomFolder(context))
        assertNull(StorageWorkspaceProvider.getCustomFolderUri(context))
    }

    @Test
    fun `clearing the folder after a lost grant returns to the workspace`() {
        givenChosenFolderGrantLostWithAnotherGrantLeft()

        StorageWorkspaceProvider.clearCustomFolder(context)
        val result = StorageWorkspaceProvider.writeFile(context, "report.txt", "local")

        assertTrue(result.error, result.success)
        assertEquals("local", File(workspace, "report.txt").readText())
    }
}
