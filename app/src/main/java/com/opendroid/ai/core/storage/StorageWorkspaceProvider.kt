package com.opendroid.ai.core.storage

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.opendroid.ai.actions.base.ActionResult
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Provides storage management for OpenDroid agent file operations.
 *
 * Combines:
 * 1. Storage Access Framework (SAF) folder selection (Option 1):
 *    User can select a custom folder (e.g. Documents, Downloads) via ACTION_OPEN_DOCUMENT_TREE.
 * 2. App-specific storage fallback (Option 2):
 *    When no custom folder is chosen, operations seamlessly execute in the app's external files directory
 *    (`context.getExternalFilesDir(null)/workspace`), requiring ZERO permissions and avoiding
 *    the Google Play MANAGE_EXTERNAL_STORAGE rejection.
 */
object StorageWorkspaceProvider {

    private const val PREFS_NAME = "opendroid_storage_prefs"
    private const val KEY_CUSTOM_FOLDER_URI = "custom_folder_uri"
    private const val MAX_FILE_SIZE_BYTES = 100 * 1024L // 100 KB

    /**
     * Storage access is always available for the app's own workspace, and optionally
     * extends to a user-selected SAF custom folder.
     */
    fun hasStorageAccess(context: Context): Boolean = true

    /**
     * Persists and stores the custom folder URI selected by the user.
     */
    fun setCustomFolderUri(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // Some providers or testing environments might not support persistable permissions
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_CUSTOM_FOLDER_URI, uri.toString()) }
    }

    /**
     * Clears the configured custom folder URI.
     */
    fun clearCustomFolder(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CUSTOM_FOLDER_URI) }
    }

    /**
     * Returns the custom folder URI if configured and valid.
     */
    fun getCustomFolderUri(context: Context): Uri? {
        val uriStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_FOLDER_URI, null) ?: return null
        return try {
            val uri = uriStr.toUri()
            val persistedList = try {
                context.contentResolver.persistedUriPermissions
            } catch (_: Exception) {
                emptyList()
            }
            val hasPersisted = persistedList.any {
                it.uri == uri && (it.isWritePermission || it.isReadPermission)
            }
            if (hasPersisted || persistedList.isEmpty()) {
                uri
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Checks whether a custom folder is currently configured.
     */
    fun hasCustomFolder(context: Context): Boolean = getCustomFolderUri(context) != null

    /**
     * Returns a human-readable display name for the custom folder, or a default label.
     */
    fun getCustomFolderDisplayName(context: Context): String {
        val uri = getCustomFolderUri(context) ?: return "App Workspace (Default)"
        return try {
            val doc = DocumentFile.fromTreeUri(context, uri)
            doc?.name ?: "Custom Folder"
        } catch (_: Exception) {
            "Custom Folder"
        }
    }

    /**
     * Returns the app's default external workspace directory (Option 2 fallback).
     * Accessible on all Android versions without runtime permissions.
     */
    fun getDefaultWorkspaceDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "workspace").apply { mkdirs() }
    }

    /**
     * Checks if operations for this path should target the user's custom SAF folder.
     */
    fun shouldUseCustomFolder(context: Context, pathStr: String?): Boolean {
        if (!hasCustomFolder(context)) return false
        if (pathStr.isNullOrBlank()) return true
        val trimmed = pathStr.trim()
        if (trimmed.startsWith("content://")) return true
        if (!trimmed.startsWith("/")) return true

        // Only shared storage paths map into the custom folder; resolveFile decides on the rest
        return sharedStorageRelativePath(context, trimmed) != null
    }

    /**
     * Resolves a path to a File in the app's workspace, the only root file actions may touch.
     * Shared storage paths map into the workspace; any other absolute path is rejected.
     */
    fun resolveFile(context: Context, pathStr: String?): File {
        val trimmed = pathStr?.trim().orEmpty()
        if (trimmed.startsWith("content://")) {
            throw SecurityException("content:// URIs must be accessed via Storage Access Framework")
        }

        val workspace = getDefaultWorkspaceDir(context)
        val workspaceCanonical = workspace.canonicalPath

        val candidate = when {
            !trimmed.startsWith("/") -> File(workspace, trimmed)
            canonicalPathOf(File(trimmed)).isSameOrUnder(workspaceCanonical) -> File(trimmed)
            else -> {
                val relative = sharedStorageRelativePath(context, trimmed)
                    ?: throw SecurityException("Only paths inside the workspace are allowed: $trimmed")
                File(workspace, relative)
            }
        }

        val canonicalResult = canonicalPathOf(candidate)
        if (!canonicalResult.isSameOrUnder(workspaceCanonical)) {
            throw SecurityException("Path traversal attempt detected: $canonicalResult")
        }

        return File(canonicalResult)
    }

    /**
     * The workspace-relative form of an absolute path on shared storage, or null if it isn't on
     * shared storage. The app's own external directory sits there too, but is never shared.
     */
    // lint false positive: these paths are only matched in a typed path string to remap it into the workspace; they are never used to open storage.
    @SuppressLint("SdCardPath")
    private fun sharedStorageRelativePath(context: Context, absolutePath: String): String? {
        val canonical = canonicalPathOf(File(absolutePath))
        val appExternalDir = context.getExternalFilesDir(null)?.parentFile?.canonicalPath
        if (appExternalDir != null && canonical.isSameOrUnder(appExternalDir)) return null

        val normalized = File(absolutePath).normalize().invariantSeparatorsPath
        for (root in listOf("/sdcard", "/storage/emulated/0")) {
            if (normalized == root || normalized.startsWith("$root/")) {
                return normalized.removePrefix(root).trimStart('/')
            }
        }
        val extRoot = Environment.getExternalStorageDirectory().canonicalPath
        return if (canonical.isSameOrUnder(extRoot)) canonical.removePrefix(extRoot).trimStart(File.separatorChar) else null
    }

    private fun canonicalPathOf(file: File): String = try {
        file.canonicalPath
    } catch (_: IOException) {
        throw SecurityException("Invalid path: cannot resolve canonical path")
    }

    private fun String.isSameOrUnder(root: String): Boolean = this == root || startsWith(root + File.separator)

    private fun isWorkspaceRoot(context: Context, file: File): Boolean =
        file.canonicalPath == getDefaultWorkspaceDir(context).canonicalPath

    // ── File Operations ──────────────────────────────────────────────────

    fun listFiles(context: Context, pathStr: String?): ActionResult {
        return if (shouldUseCustomFolder(context, pathStr)) {
            try {
                val customUri = getCustomFolderUri(context)
                    ?: return listFilesLocal(context, pathStr)
                val root = DocumentFile.fromTreeUri(context, customUri)
                    ?: return listFilesLocal(context, pathStr)
                val cleanPath = cleanRelativePath(pathStr)
                val targetDir = if (cleanPath.isEmpty()) root else findDocumentByPath(root, cleanPath)
                if (targetDir == null || !targetDir.exists()) {
                    return ActionResult(false, null, "Directory does not exist: ${pathStr ?: ""}")
                }
                if (!targetDir.isDirectory) {
                    return ActionResult(false, null, "Path is not a directory: ${pathStr ?: ""}")
                }
                val files = targetDir.listFiles()
                val fileList = files.joinToString("\n") { file ->
                    val type = if (file.isDirectory) "DIR" else "FILE"
                    "${file.name ?: "unnamed"} [$type] (${file.length()} bytes)"
                }
                ActionResult(true, if (fileList.isEmpty()) "Directory is empty." else fileList, null)
            } catch (e: Exception) {
                listFilesLocal(context, pathStr)
            }
        } else {
            listFilesLocal(context, pathStr)
        }
    }

    private fun listFilesLocal(context: Context, pathStr: String?): ActionResult {
        return try {
            val dir = resolveFile(context, pathStr)
            if (!dir.exists()) {
                return ActionResult(false, null, "Directory does not exist: ${dir.absolutePath}")
            }
            if (!dir.isDirectory) {
                return ActionResult(false, null, "Path is not a directory: ${dir.absolutePath}")
            }
            val files = dir.listFiles() ?: emptyArray()
            val fileList = files.joinToString("\n") { file ->
                val type = if (file.isDirectory) "DIR" else "FILE"
                "${file.name} [$type] (${file.length()} bytes)"
            }
            ActionResult(true, if (fileList.isEmpty()) "Directory is empty." else fileList, null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't list files: ${e.localizedMessage}")
        }
    }

    fun readFile(context: Context, filePath: String): ActionResult {
        return if (shouldUseCustomFolder(context, filePath)) {
            try {
                val customUri = getCustomFolderUri(context)
                    ?: return readFileLocal(context, filePath)
                val root = DocumentFile.fromTreeUri(context, customUri)
                    ?: return readFileLocal(context, filePath)
                val cleanPath = cleanRelativePath(filePath)
                val file = findDocumentByPath(root, cleanPath)
                if (file == null || !file.exists()) {
                    return ActionResult(false, null, "File does not exist: $filePath")
                }
                if (file.isDirectory) {
                    return ActionResult(false, null, "Path is a directory, not a file: $filePath")
                }
                if (file.length() > MAX_FILE_SIZE_BYTES) {
                    return ActionResult(false, null, "File exceeds size limit of 100KB: $filePath")
                }
                val content = context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
                    ?: return ActionResult(false, null, "Could not open file stream: $filePath")
                ActionResult(true, content, null)
            } catch (e: Exception) {
                readFileLocal(context, filePath)
            }
        } else {
            readFileLocal(context, filePath)
        }
    }

    private fun readFileLocal(context: Context, filePath: String): ActionResult {
        return try {
            val file = resolveFile(context, filePath)
            if (!file.exists()) {
                return ActionResult(false, null, "File does not exist: ${file.absolutePath}")
            }
            if (file.isDirectory) {
                return ActionResult(false, null, "Path is a directory, not a file: ${file.absolutePath}")
            }
            if (file.length() > MAX_FILE_SIZE_BYTES) {
                return ActionResult(false, null, "File exceeds size limit of 100KB: ${file.absolutePath}")
            }
            val text = file.readText()
            ActionResult(true, text, null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't read file: ${e.localizedMessage}")
        }
    }

    fun writeFile(context: Context, filePath: String, content: String): ActionResult {
        return if (shouldUseCustomFolder(context, filePath)) {
            try {
                val customUri = getCustomFolderUri(context)
                    ?: return writeFileLocal(context, filePath, content)
                val root = DocumentFile.fromTreeUri(context, customUri)
                    ?: return writeFileLocal(context, filePath, content)
                val cleanPath = cleanRelativePath(filePath)
                if (pathSegments(cleanPath).isEmpty()) {
                    return ActionResult(false, null, "Path is the custom folder itself, not a file: $filePath")
                }
                val doc = findOrCreateDocumentByPath(root, cleanPath, isDirectory = false)
                    ?: return writeFileLocal(context, filePath, content)
                context.contentResolver.openOutputStream(doc.uri, "wt")?.use { stream ->
                    stream.bufferedWriter().use { it.write(content) }
                } ?: return ActionResult(false, null, "Could not open file stream to write: $filePath")
                ActionResult(true, "File saved at ${doc.name ?: filePath}", null)
            } catch (e: Exception) {
                writeFileLocal(context, filePath, content)
            }
        } else {
            writeFileLocal(context, filePath, content)
        }
    }

    private fun writeFileLocal(context: Context, filePath: String, content: String): ActionResult {
        return try {
            val file = resolveFile(context, filePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            ActionResult(true, "File saved at ${file.absolutePath}", null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't write to file: ${e.localizedMessage}")
        }
    }

    fun deleteFile(context: Context, filePath: String): ActionResult {
        return if (shouldUseCustomFolder(context, filePath)) {
            try {
                val customUri = getCustomFolderUri(context)
                    ?: return deleteFileLocal(context, filePath)
                val root = DocumentFile.fromTreeUri(context, customUri)
                    ?: return deleteFileLocal(context, filePath)
                val cleanPath = cleanRelativePath(filePath)
                if (pathSegments(cleanPath).isEmpty()) {
                    return ActionResult(false, null, "Refusing to delete the whole custom folder: '$filePath'")
                }
                val doc = findDocumentByPath(root, cleanPath)
                if (doc == null || !doc.exists()) {
                    return ActionResult(false, null, "File/directory does not exist: $filePath")
                }
                val deleted = doc.delete()
                if (deleted) {
                    ActionResult(true, "Deleted $filePath!", null)
                } else {
                    ActionResult(false, null, "Failed to delete: $filePath")
                }
            } catch (e: Exception) {
                deleteFileLocal(context, filePath)
            }
        } else {
            deleteFileLocal(context, filePath)
        }
    }

    private fun deleteFileLocal(context: Context, filePath: String): ActionResult {
        return try {
            val file = resolveFile(context, filePath)
            if (isWorkspaceRoot(context, file)) {
                return ActionResult(false, null, "Refusing to delete the whole workspace: '$filePath'")
            }
            if (!file.exists()) {
                return ActionResult(false, null, "File/directory does not exist: ${file.absolutePath}")
            }
            val deleted = file.deleteRecursively()
            if (deleted) {
                ActionResult(true, "Deleted ${file.absolutePath}!", null)
            } else {
                ActionResult(false, null, "Failed to delete path: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't delete: ${e.localizedMessage}")
        }
    }

    fun createDirectory(context: Context, pathStr: String): ActionResult {
        return if (shouldUseCustomFolder(context, pathStr)) {
            try {
                val customUri = getCustomFolderUri(context)
                    ?: return createDirectoryLocal(context, pathStr)
                val root = DocumentFile.fromTreeUri(context, customUri)
                    ?: return createDirectoryLocal(context, pathStr)
                val cleanPath = cleanRelativePath(pathStr)
                val dir = findOrCreateDocumentByPath(root, cleanPath, isDirectory = true)
                if (dir != null && dir.isDirectory) {
                    ActionResult(true, "Folder created at $pathStr!", null)
                } else {
                    createDirectoryLocal(context, pathStr)
                }
            } catch (e: Exception) {
                createDirectoryLocal(context, pathStr)
            }
        } else {
            createDirectoryLocal(context, pathStr)
        }
    }

    private fun createDirectoryLocal(context: Context, pathStr: String): ActionResult {
        return try {
            val dir = resolveFile(context, pathStr)
            if (dir.exists()) {
                if (dir.isDirectory) {
                    ActionResult(true, "That folder already exists at ${dir.absolutePath}.", null)
                } else {
                    ActionResult(false, null, "Path exists but is a file, not a directory: ${dir.absolutePath}")
                }
            } else {
                val created = dir.mkdirs()
                if (created) {
                    ActionResult(true, "Folder created at ${dir.absolutePath}!", null)
                } else {
                    ActionResult(false, null, "Failed to create directory: ${dir.absolutePath}")
                }
            }
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't create folder: ${e.localizedMessage}")
        }
    }

    fun copyFile(context: Context, srcPath: String, destPath: String): ActionResult {
        return try {
            val src = resolveFile(context, srcPath)
            val dest = resolveFile(context, destPath)
            if (!src.exists()) {
                return ActionResult(false, null, "Source path does not exist: ${src.absolutePath}")
            }
            overlapError(src, dest)?.let { return ActionResult(false, null, it) }
            copyRecursively(src, dest)
            ActionResult(true, "Copied from ${src.absolutePath} to ${dest.absolutePath}!", null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't copy file: ${e.localizedMessage}")
        }
    }

    fun moveFile(context: Context, srcPath: String, destPath: String): ActionResult {
        return try {
            val src = resolveFile(context, srcPath)
            val dest = resolveFile(context, destPath)
            if (!src.exists()) {
                return ActionResult(false, null, "Source path does not exist: ${src.absolutePath}")
            }
            moveResolved(src, dest)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't move file: ${e.localizedMessage}")
        }
    }

    /** Moves between paths already resolved by [resolveFile]. */
    internal fun moveResolved(
        src: File,
        dest: File,
        rename: (File, File) -> Boolean = File::renameTo,
    ): ActionResult {
        // A rename is safe here, but on case-insensitive storage a copy would write the file onto itself.
        val caseOnly = src.path != dest.path && src.path.equals(dest.path, ignoreCase = true)
        if (!caseOnly) {
            overlapError(src, dest)?.let { return ActionResult(false, null, it) }
        }
        dest.parentFile?.mkdirs()
        return when {
            rename(src, dest) -> ActionResult(true, "Moved from ${src.absolutePath} to ${dest.absolutePath}!", null)
            caseOnly -> ActionResult(false, null, "Couldn't rename ${src.absolutePath} to ${dest.absolutePath}")
            else -> moveByCopy(src, dest)
        }
    }

    internal fun moveByCopy(
        src: File,
        dest: File,
        deleteSource: (File) -> Boolean = File::deleteRecursively,
    ): ActionResult {
        copyRecursively(src, dest)
        return if (deleteSource(src)) {
            ActionResult(true, "Moved from ${src.absolutePath} to ${dest.absolutePath}!", null)
        } else {
            ActionResult(false, null, "Copied to ${dest.absolutePath}, but couldn't fully delete the source: ${src.absolutePath}")
        }
    }

    fun zipFiles(context: Context, srcPath: String, zipFilePath: String): ActionResult {
        return try {
            val src = resolveFile(context, srcPath)
            val zipFile = resolveFile(context, zipFilePath)
            if (!src.exists()) {
                return ActionResult(false, null, "Source path does not exist: ${src.absolutePath}")
            }
            if (isWorkspaceRoot(context, zipFile)) {
                return ActionResult(false, null, "Zip file can't be the workspace itself: '$zipFilePath'")
            }
            if (zipFile.isSameOrInside(src)) {
                return ActionResult(false, null, "Zip file can't be the source or inside it: ${zipFile.absolutePath}")
            }
            zipFile.parentFile?.mkdirs()
            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                zipRecursively(src, src, zos)
            }
            ActionResult(true, "Zipped ${src.absolutePath} into ${zipFile.absolutePath}!", null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't zip files: ${e.localizedMessage}")
        }
    }

    fun unzipFile(context: Context, zipFilePath: String, destDirPath: String): ActionResult {
        return try {
            val zipFile = resolveFile(context, zipFilePath)
            val destDir = resolveFile(context, destDirPath)
            if (!zipFile.exists()) {
                return ActionResult(false, null, "Zip file does not exist: ${zipFile.absolutePath}")
            }
            if (isWorkspaceRoot(context, destDir)) {
                return ActionResult(false, null, "Can't unzip over the workspace root; name a folder inside it: '$destDirPath'")
            }
            destDir.mkdirs()
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val file = File(destDir, entry.name)
                    val canonicalDest = destDir.canonicalPath
                    val canonicalFile = file.canonicalPath
                    if (!canonicalFile.startsWith(canonicalDest + File.separator) && canonicalFile != canonicalDest) {
                        throw SecurityException("ZipSlip: entry '${entry.name}' is outside of target dir")
                    }
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { output ->
                            zis.copyTo(output)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            ActionResult(true, "Unzipped ${zipFile.absolutePath} to ${destDir.absolutePath}!", null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't unzip file: ${e.localizedMessage}")
        }
    }

    // ── Helper Utilities ─────────────────────────────────────────────────

    // lint false positive: these prefixes are only stripped from a typed path string to make it relative; they are never used to open storage.
    @SuppressLint("SdCardPath")
    private fun cleanRelativePath(pathStr: String?): String {
        var clean = pathStr?.trim().orEmpty()
        if (clean == "/sdcard" || clean == "/storage/emulated/0") return ""
        val prefixes = listOf("/sdcard/", "/storage/emulated/0/", "sdcard/", "./")
        for (prefix in prefixes) {
            if (clean.startsWith(prefix)) {
                clean = clean.removePrefix(prefix)
            }
        }
        return clean.trimStart('/')
    }

    private fun pathSegments(path: String): List<String> = path.split("/").filter { it.isNotEmpty() && it != "." }

    private fun findDocumentByPath(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (part in pathSegments(path)) {
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun findOrCreateDocumentByPath(
        root: DocumentFile,
        path: String,
        isDirectory: Boolean = false,
    ): DocumentFile? {
        val parts = pathSegments(path)
        if (parts.isEmpty()) return root
        var current = root
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            var child = current.findFile(part)
            if (child == null) {
                child = current.createDirectory(part) ?: return null
            }
            current = child
        }
        val leaf = parts.last()
        val existing = current.findFile(leaf)
        if (existing != null) {
            return existing
        }
        return if (isDirectory) {
            current.createDirectory(leaf)
        } else {
            val mime = guessMimeType(leaf)
            current.createFile(mime, leaf)
        }
    }

    private fun guessMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "txt", "log" -> "text/plain"
            "json" -> "application/json"
            "md" -> "text/markdown"
            "html", "htm" -> "text/html"
            "csv" -> "text/csv"
            "xml" -> "text/xml"
            "zip" -> "application/zip"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    /** Why a copy or move from [src] to [dest] would destroy data, or null if the paths don't overlap. */
    private fun overlapError(src: File, dest: File): String? {
        val destInSrc = dest.isSameOrInside(src)
        val srcInDest = src.isSameOrInside(dest)
        return when {
            destInSrc && srcInDest -> "Source and destination are the same path: ${src.absolutePath}"
            destInSrc -> "Destination is inside the source: ${dest.absolutePath} is in ${src.absolutePath}"
            srcInDest -> "Source is inside the destination: ${src.absolutePath} is in ${dest.absolutePath}"
            else -> null
        }
    }

    // Ignores case because the workspace lives on external storage, which is case-insensitive on Android.
    private fun File.isSameOrInside(other: File): Boolean =
        File(canonicalPath.lowercase(Locale.ROOT)).startsWith(File(other.canonicalPath.lowercase(Locale.ROOT)))

    internal fun copyRecursively(src: File, dest: File) {
        if (src.isDirectory) {
            // List first: a destination inside src must not show up as one of its children.
            val children = src.listFiles() ?: throw IOException("Can't list ${src.absolutePath}")
            if (!dest.exists()) {
                dest.mkdirs()
            }
            children.forEach { file ->
                copyRecursively(file, File(dest, file.name))
            }
        } else {
            dest.parentFile?.mkdirs()
            src.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun zipRecursively(root: File, file: File, zos: ZipOutputStream) {
        val relativePath = file.absolutePath.substring(root.parentFile?.absolutePath?.length?.plus(1) ?: 0)
        if (file.isDirectory) {
            val entryName = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
            zos.putNextEntry(ZipEntry(entryName))
            zos.closeEntry()
            file.listFiles()?.forEach { child ->
                zipRecursively(root, child, zos)
            }
        } else {
            zos.putNextEntry(ZipEntry(relativePath))
            file.inputStream().use { input ->
                input.copyTo(zos)
            }
            zos.closeEntry()
        }
    }
}
