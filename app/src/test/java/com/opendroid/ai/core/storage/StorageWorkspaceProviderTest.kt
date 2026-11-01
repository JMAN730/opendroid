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

    // ── Confinement: absolute paths into app-private storage ────────────

    @Test
    fun `sdcard download path still maps into the workspace for write and read`() = runBlocking {
        val writeRes = StorageWorkspaceProvider.writeFile(context, "/sdcard/Download/x.txt", "hello")
        assertTrue(writeRes.success)

        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        val mapped = File(ws, "Download/x.txt")
        assertTrue(mapped.exists())
        assertEquals("hello", mapped.readText())

        val readRes = StorageWorkspaceProvider.readFile(context, "/storage/emulated/0/Download/x.txt")
        assertTrue(readRes.success)
        assertEquals("hello", readRes.data)
    }

    @Test
    fun `absolute path inside the workspace is honoured`() = runBlocking {
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        assertTrue(StorageWorkspaceProvider.writeFile(context, "notes/a.txt", "inside").success)

        val absolute = File(ws, "notes/a.txt").absolutePath
        val resolved = StorageWorkspaceProvider.resolveFile(context, absolute)
        assertEquals(File(ws, "notes/a.txt").canonicalPath, resolved.canonicalPath)

        val readRes = StorageWorkspaceProvider.readFile(context, absolute)
        assertTrue(readRes.success)
        assertEquals("inside", readRes.data)
    }

    @Test(expected = SecurityException::class)
    fun `resolveFile rejects absolute path into app filesDir`() {
        StorageWorkspaceProvider.resolveFile(
            context,
            File(context.filesDir, "datastore/settings.preferences_pb").absolutePath
        )
    }

    @Test(expected = SecurityException::class)
    fun `resolveFile rejects absolute path into app dataDir`() {
        StorageWorkspaceProvider.resolveFile(
            context,
            File(context.applicationInfo.dataDir, "shared_prefs/opendroid_storage_prefs.xml").absolutePath
        )
    }

    @Test(expected = SecurityException::class)
    fun `resolveFile rejects sibling directory sharing the workspace prefix`() {
        // ".../workspaceX" starts with ".../workspace" but is not inside it
        StorageWorkspaceProvider.resolveFile(context, "../workspaceX/secret.txt")
    }

    @Test
    fun `absolute sibling directory sharing the workspace prefix is never reachable`() {
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        val sibling = File(ws.canonicalPath + "X/secret.txt")
        // Either rejected outright, or mapped to somewhere strictly inside the workspace;
        // it must never resolve to the sibling itself.
        val resolved = try {
            StorageWorkspaceProvider.resolveFile(context, sibling.absolutePath).canonicalPath
        } catch (_: SecurityException) {
            null
        }
        assertTrue(resolved == null || resolved.startsWith(ws.canonicalPath + File.separator))
        assertTrue(resolved != sibling.canonicalPath)
    }

    @Test(expected = SecurityException::class)
    fun `resolveFile rejects filesystem root`() {
        StorageWorkspaceProvider.resolveFile(context, "/")
    }

    @Test
    fun `downloaded models under external files dir survive DELETE_FILE and WRITE_FILE by absolute path`() = runBlocking {
        val extFiles = context.getExternalFilesDir(null) ?: context.filesDir
        val modelFile = File(extFiles, "models/gemma/model.litertlm").apply {
            parentFile?.mkdirs()
            writeText("MODEL_BYTES")
        }
        val modelsDir = File(extFiles, "models")

        val deleteDir = StorageWorkspaceProvider.deleteFile(context, modelsDir.absolutePath)
        assertFalse(deleteDir.success)
        assertTrue(modelsDir.isDirectory)
        assertTrue(modelFile.exists())

        val deleteFileRes = StorageWorkspaceProvider.deleteFile(context, modelFile.absolutePath)
        assertFalse(deleteFileRes.success)
        assertTrue(modelFile.exists())

        StorageWorkspaceProvider.writeFile(context, modelFile.absolutePath, "GARBAGE")
        assertEquals("MODEL_BYTES", modelFile.readText())

        // Whatever the write did, it never left the workspace.
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context).canonicalPath
        val resolved = try {
            StorageWorkspaceProvider.resolveFile(context, modelFile.absolutePath).canonicalPath
        } catch (_: SecurityException) {
            null
        }
        assertTrue(resolved == null || resolved.startsWith(ws + File.separator))
    }

    @Test
    fun `datastore file in filesDir survives DELETE_FILE and WRITE_FILE by absolute path`() = runBlocking {
        val settings = File(context.filesDir, "datastore/settings.preferences_pb").apply {
            parentFile?.mkdirs()
            writeText("SETTINGS")
        }

        val writeRes = StorageWorkspaceProvider.writeFile(context, settings.absolutePath, "")
        assertFalse(writeRes.success)
        assertEquals("SETTINGS", settings.readText())

        val deleteRes = StorageWorkspaceProvider.deleteFile(context, settings.absolutePath)
        assertFalse(deleteRes.success)
        assertTrue(settings.exists())
        assertEquals("SETTINGS", settings.readText())

        val unzipRes = StorageWorkspaceProvider.unzipFile(context, "nothing.zip", settings.parentFile!!.absolutePath)
        assertFalse(unzipRes.success)
    }

    // ── Root-like paths never touch the whole workspace ─────────────────

    @Test
    fun `blank dot slash and sdcard root paths never delete the workspace`() = runBlocking {
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        val keep = File(ws, "keep/important.txt").apply {
            parentFile?.mkdirs()
            writeText("keep me")
        }

        listOf("", "   ", ".", "./", "/", "/sdcard", "/sdcard/", "/storage/emulated/0", "/storage/emulated/0/", ws.absolutePath)
            .forEach { path ->
                val res = StorageWorkspaceProvider.deleteFile(context, path)
                assertFalse("DELETE_FILE '$path' must not succeed", res.success)
                assertTrue("workspace must survive DELETE_FILE '$path'", ws.isDirectory)
                assertTrue("files must survive DELETE_FILE '$path'", keep.exists())
            }
        assertEquals("keep me", keep.readText())
    }

    @Test
    fun `DELETE_FILE action with blank filePath does not wipe the workspace`() = runBlocking {
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        val keep = File(ws, "keep.txt").apply { writeText("keep me") }

        val actions = AdvancedControlActions()
        val deleteAction = actions.getActions().first { it.name == "DELETE_FILE" }
        val res = deleteAction.execute(mapOf("filePath" to ""), context)

        assertFalse(res.success)
        assertTrue(ws.isDirectory)
        assertTrue(keep.exists())
    }

    @Test
    fun `move copy zip and unzip refuse the workspace root`() = runBlocking {
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        assertTrue(StorageWorkspaceProvider.writeFile(context, "sub/file.txt", "data").success)
        assertTrue(StorageWorkspaceProvider.zipFiles(context, "sub", "sub.zip").success)

        assertFalse(StorageWorkspaceProvider.moveFile(context, "sub", "").success)
        assertFalse(StorageWorkspaceProvider.moveFile(context, "", "elsewhere").success)
        assertFalse(StorageWorkspaceProvider.copyFile(context, "sub", "/sdcard").success)
        assertFalse(StorageWorkspaceProvider.copyFile(context, ".", "copy").success)
        assertFalse(StorageWorkspaceProvider.zipFiles(context, "sub", "").success)
        assertFalse(StorageWorkspaceProvider.unzipFile(context, "sub.zip", "").success)

        assertTrue(ws.isDirectory)
        assertTrue(File(ws, "sub/file.txt").exists())
        assertEquals("data", File(ws, "sub/file.txt").readText())
        assertFalse(File(ws, "elsewhere").exists())
        assertFalse(File(ws, "copy").exists())
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

    // ── Overlapping source/destination (issue #89) ───────────────────────

    private fun seedNotesFolder() {
        assertTrue(StorageWorkspaceProvider.writeFile(context, "notes/a.txt", "alpha").success)
        assertTrue(StorageWorkspaceProvider.writeFile(context, "notes/sub/b.txt", "beta").success)
    }

    private fun assertNotesFolderIntact() {
        assertEquals("alpha", StorageWorkspaceProvider.readFile(context, "notes/a.txt").data)
        assertEquals("beta", StorageWorkspaceProvider.readFile(context, "notes/sub/b.txt").data)
    }

    @Test
    fun `moveFile refuses to move a folder into its own subfolder and keeps the data`() {
        seedNotesFolder()

        val result = StorageWorkspaceProvider.moveFile(context, "notes", "notes/backup")

        assertFalse(result.success)
        assertNotNull(result.error)
        assertNotesFolderIntact()
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        assertFalse(File(ws, "notes/backup").exists())
    }

    @Test
    fun `moveFile refuses to move a path onto itself`() {
        seedNotesFolder()

        val result = StorageWorkspaceProvider.moveFile(context, "notes", "./notes")

        assertFalse(result.success)
        assertNotesFolderIntact()
    }

    @Test
    fun `copyFile refuses to copy a folder into its own subfolder and keeps the data`() {
        seedNotesFolder()

        val result = StorageWorkspaceProvider.copyFile(context, "notes", "notes/sub/copy")

        assertFalse(result.success)
        assertNotNull(result.error)
        assertNotesFolderIntact()
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        assertFalse(File(ws, "notes/sub/copy").exists())
    }

    @Test
    fun `copyFile refuses to copy a file onto itself via an aliased path and keeps its content`() {
        assertTrue(StorageWorkspaceProvider.writeFile(context, "Download/report.txt", "report body").success)

        // Both spellings resolve to workspace/Download/report.txt.
        val result = StorageWorkspaceProvider.copyFile(
            context,
            "/sdcard/Download/report.txt",
            "Download/report.txt"
        )

        assertFalse(result.success)
        assertNotNull(result.error)
        assertEquals("report body", StorageWorkspaceProvider.readFile(context, "Download/report.txt").data)
    }

    @Test
    fun `copyFile refuses to copy a folder onto itself and keeps every file`() {
        seedNotesFolder()

        val result = StorageWorkspaceProvider.copyFile(context, "/sdcard/notes", "notes")

        assertFalse(result.success)
        assertNotesFolderIntact()
    }

    @Test
    fun `copyFile still copies a folder to a sibling location`() {
        seedNotesFolder()

        val result = StorageWorkspaceProvider.copyFile(context, "notes", "notes_copy")

        assertTrue(result.success)
        assertNotesFolderIntact()
        assertEquals("alpha", StorageWorkspaceProvider.readFile(context, "notes_copy/a.txt").data)
        assertEquals("beta", StorageWorkspaceProvider.readFile(context, "notes_copy/sub/b.txt").data)
    }

    @Test(timeout = 10_000)
    fun `zipFiles refuses to create the zip inside the workspace being zipped`() {
        seedNotesFolder()

        val result = StorageWorkspaceProvider.zipFiles(context, ".", "backup.zip")

        assertFalse(result.success)
        assertNotNull(result.error)
        assertNotesFolderIntact()
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        assertFalse(File(ws, "backup.zip").exists())
    }

    @Test(timeout = 10_000)
    fun `zipFiles refuses to create the zip inside the source folder`() {
        seedNotesFolder()

        val result = StorageWorkspaceProvider.zipFiles(context, "notes", "notes/sub/notes.zip")

        assertFalse(result.success)
        assertNotesFolderIntact()
        val ws = StorageWorkspaceProvider.getDefaultWorkspaceDir(context)
        assertFalse(File(ws, "notes/sub/notes.zip").exists())
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
}
