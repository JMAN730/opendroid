package com.opendroid.ai.core.storage

import android.content.Context
import android.net.Uri
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
}
