package com.opendroid.ai.core.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.AdvancedControlActions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StorageWorkspaceProviderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        StorageWorkspaceProvider.clearCustomFolder(context)
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        ws.deleteRecursively()
        ws.mkdirs()
    }

    @Test
    fun `default workspace is always accessible without permissions`() {
        assertTrue(StorageWorkspaceProvider.hasStorageAccess(context))
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        assertTrue(ws.exists())
        assertTrue(ws.isDirectory)
    }

    @Test
    fun `custom folder uri can be saved and cleared`() {
        assertFalse(StorageWorkspaceProvider.hasCustomFolder(context))
        assertNull(StorageWorkspaceProvider.getCustomFolderUri(context))

        val fakeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        StorageWorkspaceProvider.setCustomFolderUri(context, fakeUri)

        assertTrue(StorageWorkspaceProvider.hasCustomFolder(context))
        assertEquals(fakeUri, StorageWorkspaceProvider.getCustomFolderUri(context))

        StorageWorkspaceProvider.clearCustomFolder(context)
        assertFalse(StorageWorkspaceProvider.hasCustomFolder(context))
        assertNull(StorageWorkspaceProvider.getCustomFolderUri(context))
    }

    @Test
    fun `resolveFile resolves relative paths inside workspace`() {
        val file = StorageWorkspaceProvider.resolveFile(context, "my_folder/notes.txt")
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        assertTrue(file.canonicalPath.startsWith(ws.canonicalPath))
        assertEquals("notes.txt", file.name)
    }

    @Test(expected = SecurityException::class)
    fun `resolveFile rejects path traversal`() {
        StorageWorkspaceProvider.resolveFile(context, "../../etc/passwd")
    }

    @Test(expected = SecurityException::class)
    fun `resolveFile rejects forbidden system directories`() {
        StorageWorkspaceProvider.resolveFile(context, "/data/system/users")
    }

    @Test
    fun `resolveFile maps inaccessible sdcard path into workspace`() {
        val file = StorageWorkspaceProvider.resolveFile(context, "/sdcard/OpenDroid/document.pdf")
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        assertTrue(file.canonicalPath.startsWith(ws.canonicalPath))
        assertTrue(file.canonicalPath.endsWith("OpenDroid/document.pdf"))
    }

    @Test
    fun `app workspace file operations execute successfully without permissions`() = runBlocking {
        // 1. Create directory
        val createDirResult = StorageWorkspaceProvider.createDirectory(context, "subfolder")
        assertTrue(createDirResult.success)

        // 2. Write file
        val writeResult = StorageWorkspaceProvider.writeFile(context, "subfolder/hello.txt", "Hello OpenDroid!")
        assertTrue(writeResult.success)

        // 3. Read file
        val readResult = StorageWorkspaceProvider.readFile(context, "subfolder/hello.txt")
        assertTrue(readResult.success)
        assertEquals("Hello OpenDroid!", readResult.data)

        // 4. List files
        val listResult = StorageWorkspaceProvider.listFiles(context, "subfolder")
        assertTrue(listResult.success)
        assertTrue(listResult.data?.contains("hello.txt") == true)

        // 5. Copy file
        val copyResult = StorageWorkspaceProvider.copyFile(context, "subfolder/hello.txt", "subfolder/hello_copy.txt")
        assertTrue(copyResult.success)
        val readCopy = StorageWorkspaceProvider.readFile(context, "subfolder/hello_copy.txt")
        assertEquals("Hello OpenDroid!", readCopy.data)

        // 6. Move file
        val moveResult = StorageWorkspaceProvider.moveFile(context, "subfolder/hello_copy.txt", "subfolder/hello_moved.txt")
        assertTrue(moveResult.success)
        val movedCheck = StorageWorkspaceProvider.readFile(context, "subfolder/hello_moved.txt")
        assertEquals("Hello OpenDroid!", movedCheck.data)
        assertFalse(StorageWorkspaceProvider.readFile(context, "subfolder/hello_copy.txt").success)

        // 7. Zip and Unzip
        val zipResult = StorageWorkspaceProvider.zipFiles(context, "subfolder", "backup.zip")
        assertTrue(zipResult.success)
        val unzipResult = StorageWorkspaceProvider.unzipFile(context, "backup.zip", "unzipped")
        assertTrue(unzipResult.success)
        val unzippedContent = StorageWorkspaceProvider.readFile(context, "unzipped/subfolder/hello.txt")
        assertTrue(unzippedContent.success)
        assertEquals("Hello OpenDroid!", unzippedContent.data)

        // 8. Delete file
        val deleteResult = StorageWorkspaceProvider.deleteFile(context, "subfolder/hello.txt")
        assertTrue(deleteResult.success)
        assertFalse(StorageWorkspaceProvider.readFile(context, "subfolder/hello.txt").success)
    }

    @Test
    fun `advanced control actions file operations execute seamlessly`() = runBlocking {
        val actions = AdvancedControlActions()
        val actionsMap = actions.getActions().associateBy { it.name }

        // Write
        val writeAction = actionsMap["WRITE_FILE"]!!
        val writeRes = writeAction.execute(
            mapOf("filePath" to "agent_log.txt", "content" to "Task completed successfully."),
            context
        )
        assertTrue(writeRes.success)

        // Read
        val readAction = actionsMap["READ_FILE"]!!
        val readRes = readAction.execute(
            mapOf("filePath" to "agent_log.txt"),
            context
        )
        assertTrue(readRes.success)
        assertEquals("Task completed successfully.", readRes.data)

        // List
        val listAction = actionsMap["LIST_FILES"]!!
        val listRes = listAction.execute(emptyMap(), context)
        assertTrue(listRes.success)
        assertTrue(listRes.data?.contains("agent_log.txt") == true)

        // Delete
        val deleteAction = actionsMap["DELETE_FILE"]!!
        val deleteRes = deleteAction.execute(
            mapOf("filePath" to "agent_log.txt"),
            context
        )
        assertTrue(deleteRes.success)
    }

    // ── Overlapping source and destination ───────────────────────────────

    private val workspace: File
        get() = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)

    /** A folder holding both a top-level file and a nested folder, so a listing can start with either. */
    private fun givenNotesFolder(): File {
        val notes = File(workspace, "notes").apply { mkdirs() }
        File(notes, "todo.txt").writeText("todo")
        File(notes, "mmm").mkdirs()
        File(notes, "mmm/inner.txt").writeText("inner")
        return notes
    }

    private fun assertNotesFolderIntact() {
        assertEquals("todo", File(workspace, "notes/todo.txt").readText())
        assertEquals("inner", File(workspace, "notes/mmm/inner.txt").readText())
    }

    @Test
    fun `moving a folder into its own subfolder fails and keeps the data under either listing order`() {
        // The new folder sorts before or after the existing children, which covers a listing that
        // reaches the destination first and one that reaches the files first.
        for (destName in listOf("aaa", "zzz")) {
            val notes = givenNotesFolder()

            val result = StorageWorkspaceProvider.moveFile(context, "notes", "notes/$destName")

            assertFalse(result.data, result.success)
            assertNotesFolderIntact()
            assertFalse(File(notes, destName).exists())
        }
    }

    @Test
    fun `moving a folder onto a folder that contains it fails and keeps the data`() {
        // Merging parent/child into parent would write child/child/deep.txt to child/deep.txt,
        // the one place the move then deletes.
        File(workspace, "parent/child/child").mkdirs()
        File(workspace, "parent/child/child/deep.txt").writeText("deep")
        File(workspace, "parent/child/shallow.txt").writeText("shallow")

        val result = StorageWorkspaceProvider.moveFile(context, "parent/child", "parent")

        assertFalse(result.data, result.success)
        assertEquals("deep", File(workspace, "parent/child/child/deep.txt").readText())
        assertEquals("shallow", File(workspace, "parent/child/shallow.txt").readText())
    }

    @Test
    fun `moving a path onto itself fails and keeps the data`() {
        givenNotesFolder()

        val result = StorageWorkspaceProvider.moveFile(context, "notes", "./notes")

        assertFalse(result.data, result.success)
        assertNotesFolderIntact()
    }

    @Test
    fun `copying a folder into its own subfolder fails and keeps the data under either listing order`() {
        for (destName in listOf("aaa", "zzz")) {
            val notes = givenNotesFolder()

            val result = StorageWorkspaceProvider.copyFile(context, "notes", "notes/$destName")

            assertFalse(result.data, result.success)
            assertNotesFolderIntact()
            assertFalse(File(notes, destName).exists())
        }
    }

    @Test
    fun `copying a file onto itself fails and does not empty it`() {
        File(workspace, "Download").mkdirs()
        File(workspace, "Download/report.pdf").writeText("report")

        val result = StorageWorkspaceProvider.copyFile(context, "Download/report.pdf", "Download/../Download/report.pdf")

        assertFalse(result.data, result.success)
        assertEquals("report", File(workspace, "Download/report.pdf").readText())
    }

    @Test
    fun `copying a folder onto itself fails and does not empty its files`() {
        givenNotesFolder()

        val result = StorageWorkspaceProvider.copyFile(context, "notes", "./notes")

        assertFalse(result.data, result.success)
        assertNotesFolderIntact()
    }

    @Test
    fun `copying onto a path that differs only by case fails because device storage ignores case`() {
        givenNotesFolder()

        val result = StorageWorkspaceProvider.copyFile(context, "notes/todo.txt", "NOTES/TODO.txt")

        assertFalse(result.data, result.success)
        assertNotesFolderIntact()
    }

    @Test
    fun `moving a file to a name that differs only by case renames it`() {
        val report = File(workspace, "report.txt").apply { writeText("report") }

        val result = StorageWorkspaceProvider.moveResolved(report, File(workspace, "Report.txt"))

        assertTrue(result.error, result.success)
        assertTrue(workspace.list()!!.contains("Report.txt"))
        assertFalse(workspace.list()!!.contains("report.txt"))
        assertEquals("report", File(workspace, "Report.txt").readText())
    }

    @Test
    fun `moving a folder to a name that differs only by case renames it and keeps its files`() {
        val notes = givenNotesFolder()

        val result = StorageWorkspaceProvider.moveResolved(notes, File(workspace, "Notes"))

        assertTrue(result.error, result.success)
        assertTrue(workspace.list()!!.contains("Notes"))
        assertFalse(workspace.list()!!.contains("notes"))
        assertEquals("inner", File(workspace, "Notes/mmm/inner.txt").readText())
    }

    @Test
    fun `a failed case only rename reports failure and never falls back to a copy`() {
        val report = File(workspace, "report.txt").apply { writeText("report") }

        val result = StorageWorkspaceProvider.moveResolved(report, File(workspace, "Report.txt")) { _, _ -> false }

        assertFalse(result.data, result.success)
        assertNotNull(result.error)
        assertEquals(listOf("report.txt"), workspace.list()!!.toList())
        assertEquals("report", report.readText())
    }

    @Test
    fun `moveFile renames a file when the resolved paths keep the requested case`() {
        File(workspace, "report.txt").writeText("report")
        // Windows folds a canonical path to the case on disk, so resolveFile hides the change there.
        assumeTrue(File(workspace, "Report.txt").canonicalPath.endsWith("Report.txt"))

        val result = StorageWorkspaceProvider.moveFile(context, "report.txt", "Report.txt")

        assertTrue(result.error, result.success)
        assertTrue(workspace.list()!!.contains("Report.txt"))
        assertFalse(workspace.list()!!.contains("report.txt"))
    }

    @Test
    fun `moving a folder into its own subfolder spelled in another case fails and keeps the data`() {
        givenNotesFolder()

        val result = StorageWorkspaceProvider.moveFile(context, "notes", "NOTES/backup")

        assertFalse(result.data, result.success)
        assertNotesFolderIntact()
    }

    @Test
    fun `a sibling that only shares a name prefix does not count as an overlap`() {
        givenNotesFolder()

        assertTrue(StorageWorkspaceProvider.copyFile(context, "notes", "notes2").success)
        assertEquals("inner", File(workspace, "notes2/mmm/inner.txt").readText())

        assertTrue(StorageWorkspaceProvider.moveFile(context, "notes2", "notes3").success)
        assertEquals("inner", File(workspace, "notes3/mmm/inner.txt").readText())
        assertFalse(File(workspace, "notes2").exists())

        assertTrue(StorageWorkspaceProvider.zipFiles(context, "notes", "notes.zip").success)
    }

    @Test(timeout = 10_000)
    fun `zipping the workspace into a zip inside it fails instead of growing without limit`() {
        givenNotesFolder()

        val result = StorageWorkspaceProvider.zipFiles(context, ".", "backup.zip")

        assertFalse(result.data, result.success)
        assertFalse(File(workspace, "backup.zip").exists())
        assertNotesFolderIntact()
    }

    @Test(timeout = 10_000)
    fun `zipping a folder into a zip inside it fails instead of growing without limit`() {
        val notes = givenNotesFolder()

        val result = StorageWorkspaceProvider.zipFiles(context, "notes", "notes/mmm/backup.zip")

        assertFalse(result.data, result.success)
        assertFalse(File(notes, "mmm/backup.zip").exists())
        assertNotesFolderIntact()
    }

    @Test
    fun `zipping a file onto itself fails and does not empty it`() {
        givenNotesFolder()

        val result = StorageWorkspaceProvider.zipFiles(context, "notes/todo.txt", "notes/todo.txt")

        assertFalse(result.data, result.success)
        assertNotesFolderIntact()
    }

    @Test
    fun `copy lists the children before creating the destination so it never copies into itself`() {
        val notes = givenNotesFolder()

        StorageWorkspaceProvider.copyRecursively(notes, File(notes, "aaa"))

        assertEquals("todo", File(notes, "aaa/todo.txt").readText())
        assertEquals("inner", File(notes, "aaa/mmm/inner.txt").readText())
        assertFalse(File(notes, "aaa/aaa").exists())
    }

    @Test
    fun `move fails and keeps the source when the copy fails part way`() {
        val notes = givenNotesFolder()
        // A folder where the copy has to write todo.txt makes the fallback copy throw.
        File(workspace, "dest/todo.txt/occupied").mkdirs()

        val result = StorageWorkspaceProvider.moveFile(context, "notes", "dest")

        assertFalse(result.data, result.success)
        assertNotesFolderIntact()
        assertTrue(notes.exists())
    }

    @Test
    fun `move reports failure when the source cannot be fully deleted after the copy`() {
        val notes = givenNotesFolder()
        val dest = File(workspace, "dest")

        val result = StorageWorkspaceProvider.moveByCopy(notes, dest) { false }

        assertFalse(result.data, result.success)
        assertNotNull(result.error)
        assertEquals("inner", File(dest, "mmm/inner.txt").readText())
        assertNotesFolderIntact()
    }

    @Test
    fun `move deletes the source and reports success after a good copy`() {
        val notes = givenNotesFolder()
        val dest = File(workspace, "dest")

        val result = StorageWorkspaceProvider.moveByCopy(notes, dest)

        assertTrue(result.error, result.success)
        assertEquals("inner", File(dest, "mmm/inner.txt").readText())
        assertFalse(notes.exists())
    }

    // ── Confinement to the workspace ─────────────────────────────────────

    /** The path as an action would send it. Windows drops the drive so the path still starts with "/". */
    private val File.actionPath: String
        get() = absolutePath.substringAfter(':').replace('\\', '/')

    private fun givenModelFile(): File =
        File(context.getExternalFilesDir(null), "models/model.litertlm").apply {
            parentFile?.mkdirs()
            writeText("model")
        }

    private fun givenDatastoreFile(): File =
        File(context.filesDir, "datastore/settings.preferences_pb").apply {
            parentFile?.mkdirs()
            writeText("settings")
        }

    @Test
    fun `delete by absolute path leaves the downloaded models alone`() {
        val model = givenModelFile()

        val fileResult = StorageWorkspaceProvider.deleteFile(context, model.actionPath)
        val dirResult = StorageWorkspaceProvider.deleteFile(context, model.parentFile!!.actionPath)

        assertFalse(fileResult.data, fileResult.success)
        assertFalse(dirResult.data, dirResult.success)
        assertEquals("model", model.readText())
    }

    @Test
    fun `write and delete by absolute path leave the settings datastore alone`() {
        val datastore = givenDatastoreFile()

        val writeResult = StorageWorkspaceProvider.writeFile(context, datastore.actionPath, "pwned")
        val deleteResult = StorageWorkspaceProvider.deleteFile(context, datastore.actionPath)

        assertFalse(writeResult.data, writeResult.success)
        assertFalse(deleteResult.data, deleteResult.success)
        assertEquals("settings", datastore.readText())
    }

    @Test
    fun `read by absolute path can't see app private files`() {
        val result = StorageWorkspaceProvider.readFile(context, givenDatastoreFile().actionPath)

        assertFalse(result.data, result.success)
        assertNull(result.data)
    }

    @Test
    fun `copy move zip and unzip can't reach app private directories`() {
        val notes = givenNotesFolder()
        val model = givenModelFile()
        val outside = File(context.getExternalFilesDir(null), "outside")
        assertTrue(StorageWorkspaceProvider.zipFiles(context, "notes", "notes.zip").success)

        val results = listOf(
            StorageWorkspaceProvider.copyFile(context, "notes", outside.actionPath),
            StorageWorkspaceProvider.copyFile(context, model.actionPath, "stolen"),
            StorageWorkspaceProvider.moveFile(context, "notes", outside.actionPath),
            StorageWorkspaceProvider.zipFiles(context, "notes", File(outside, "n.zip").actionPath),
            StorageWorkspaceProvider.unzipFile(context, "notes.zip", outside.actionPath),
        )

        results.forEach { assertFalse(it.data, it.success) }
        assertFalse(outside.exists())
        assertFalse(File(workspace, "stolen").exists())
        assertTrue(notes.exists())
    }

    @Test
    fun `a sibling directory sharing the workspace name prefix is outside the workspace`() {
        val sibling = File(workspace.parentFile, "${workspace.name}X").apply { mkdirs() }
        val victim = File(sibling, "victim.txt").apply { writeText("keep") }
        try {
            val relative = StorageWorkspaceProvider.deleteFile(context, "../${sibling.name}/victim.txt")
            val absolute = StorageWorkspaceProvider.deleteFile(context, victim.actionPath)

            assertFalse(relative.data, relative.success)
            assertFalse(absolute.data, absolute.success)
            assertEquals("keep", victim.readText())
        } finally {
            sibling.deleteRecursively()
        }
    }

    @Test
    fun `a sibling directory sharing the files dir name prefix is outside the workspace`() {
        val sibling = File(context.filesDir.parentFile, "${context.filesDir.name}_other").apply { mkdirs() }
        val victim = File(sibling, "victim.txt").apply { writeText("keep") }
        try {
            val result = StorageWorkspaceProvider.deleteFile(context, victim.actionPath)

            assertFalse(result.data, result.success)
            assertEquals("keep", victim.readText())
        } finally {
            sibling.deleteRecursively()
        }
    }

    @Test
    fun `absolute paths outside shared storage are rejected instead of flattened into the workspace`() {
        for (path in listOf("/foo/bar.txt", "/sdcardfoo/bar.txt", "/sdcard/../../etc/passwd", "/")) {
            val result = StorageWorkspaceProvider.writeFile(context, path, "x")

            assertFalse(path, result.success)
        }
        assertFalse(File(workspace, "bar.txt").exists())
        assertFalse(File(workspace, "foo/bar.txt").exists())
    }

    @Test
    fun `shared storage paths still map into the workspace`() {
        val fromSdcard = StorageWorkspaceProvider.writeFile(context, "/sdcard/Download/x.txt", "a")
        val fromEmulated = StorageWorkspaceProvider.writeFile(context, "/storage/emulated/0/Download/y.txt", "b")

        assertTrue(fromSdcard.error, fromSdcard.success)
        assertTrue(fromEmulated.error, fromEmulated.success)
        assertEquals("a", File(workspace, "Download/x.txt").readText())
        assertEquals("b", File(workspace, "Download/y.txt").readText())
    }

    @Test
    fun `an absolute path inside the workspace still works`() {
        val notes = givenNotesFolder()

        val result = StorageWorkspaceProvider.readFile(context, File(notes, "todo.txt").actionPath)

        assertTrue(result.error, result.success)
        assertEquals("todo", result.data)
    }

    // ── Refusing the workspace root ──────────────────────────────────────

    private val rootLikePaths = listOf(
        "", " ", ".", "./", "/", "/sdcard", "/sdcard/", "/storage/emulated/0", "/storage/emulated/0/", "notes/..",
    )

    @Test
    fun `delete of a root like path deletes nothing`() {
        givenNotesFolder()

        for (path in rootLikePaths) {
            val result = StorageWorkspaceProvider.deleteFile(context, path)

            assertFalse("'$path'", result.success)
            assertNotesFolderIntact()
        }
    }

    @Test
    fun `the workspace root can't be a copy move zip or unzip destination`() {
        givenNotesFolder()
        File(workspace, "report.txt").writeText("report")
        assertTrue(StorageWorkspaceProvider.zipFiles(context, "notes", "notes.zip").success)
        File(workspace, "notes/todo.txt").writeText("edited")

        for (path in listOf("", ".", "/sdcard")) {
            val results = listOf(
                StorageWorkspaceProvider.copyFile(context, "report.txt", path),
                StorageWorkspaceProvider.moveFile(context, "report.txt", path),
                StorageWorkspaceProvider.zipFiles(context, "notes", path),
                StorageWorkspaceProvider.unzipFile(context, "notes.zip", path),
            )

            results.forEach { assertFalse("'$path': ${it.data}", it.success) }
        }
        assertEquals("edited", File(workspace, "notes/todo.txt").readText())
        assertEquals("report", File(workspace, "report.txt").readText())
    }

    @Test
    fun `the workspace root can't be moved away`() {
        givenNotesFolder()

        val result = StorageWorkspaceProvider.moveFile(context, ".", "elsewhere")

        assertFalse(result.data, result.success)
        assertNotesFolderIntact()
    }

    @Test
    fun `listing the workspace root still works`() {
        givenNotesFolder()

        val result = StorageWorkspaceProvider.listFiles(context, ".")

        assertTrue(result.error, result.success)
        assertTrue(result.data, result.data!!.contains("notes"))
    }

    // ── Refusing the custom folder root ──────────────────────────────────

    /** Answers every document query with one row, and records what the app asks it to change. */
    class FakeDocumentsProvider : ContentProvider() {
        val calls = mutableListOf<String>()

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)).apply {
            addRow(arrayOfNulls<Any>(columnCount).also { it[0] = "root" })
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            calls += method
            return Bundle()
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Test
    fun `delete of a root like path can't delete the custom folder`() {
        val authority = "com.opendroid.test.documents"
        val provider = Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, authority)
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        StorageWorkspaceProvider.setCustomFolderUri(context, tree)

        for (path in listOf("", " ", ".", "./", "/sdcard", "/sdcard/", "/storage/emulated/0", "/storage/emulated/0/")) {
            val result = StorageWorkspaceProvider.deleteFile(context, path)

            assertFalse("'$path'", result.success)
            assertEquals("'$path'", emptyList<String>(), provider.calls)
        }
    }

    @Test
    fun `absolute paths outside shared storage don't reach the custom folder`() {
        val authority = "com.opendroid.test.documents"
        Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, authority)
        StorageWorkspaceProvider.setCustomFolderUri(context, DocumentsContract.buildTreeDocumentUri(authority, "root"))

        assertFalse(StorageWorkspaceProvider.shouldUseCustomFolder(context, "/"))
        assertFalse(StorageWorkspaceProvider.shouldUseCustomFolder(context, "/foo/bar.txt"))
        assertFalse(StorageWorkspaceProvider.shouldUseCustomFolder(context, givenModelFile().actionPath))
        assertTrue(StorageWorkspaceProvider.shouldUseCustomFolder(context, "/sdcard/Download/x.txt"))
    }
}
