package com.opendroid.ai.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.opendroid.ai.actions.base.ActionResult
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
    private const val ROOT_REFUSED_MESSAGE =
        "Refusing to operate on the whole workspace folder. Name a file or subfolder instead."

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
            .edit()
            .putString(KEY_CUSTOM_FOLDER_URI, uri.toString())
            .apply()
    }

    /**
     * Clears the configured custom folder URI.
     */
    fun clearCustomFolder(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CUSTOM_FOLDER_URI)
            .apply()
    }

    /**
     * Returns the custom folder URI if configured and valid.
     */
    fun getCustomFolderUri(context: Context): Uri? {
        val uriStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_FOLDER_URI, null) ?: return null
        return try {
            val uri = Uri.parse(uriStr)
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

        // If explicit path is inside app-internal files, use File API
        val appFiles = context.filesDir.canonicalPath
        val extFiles = context.getExternalFilesDir(null)?.canonicalPath
        val canonical = try { File(trimmed).canonicalPath } catch (_: Exception) { trimmed }
        if (canonical.startsWith(appFiles) || (extFiles != null && canonical.startsWith(extFiles))) {
            return false
        }
        return true
    }

    /**
     * Resolves a path to a File confined to the app's workspace.
     *
     * Every result canonicalises to the workspace root or to a path strictly below it.
     * Relative paths are joined onto the workspace. Absolute paths are accepted only when
     * they already point inside the workspace, or when they live under shared external
     * storage (`/sdcard/...`, `/storage/emulated/0/...`), in which case they are mapped onto
     * the same relative location inside the workspace. Every other absolute path, including
     * the app's own private directories (`filesDir`, `dataDir`, the external files dir that
     * holds downloaded models), is rejected with a [SecurityException]: file actions run from
     * LLM-generated plans and must never reach storage the user did not hand them.
     */
    fun resolveFile(context: Context, pathStr: String?): File {
        val trimmed = pathStr?.trim().orEmpty()
        if (trimmed.startsWith("content://")) {
            throw SecurityException("content:// URIs must be accessed via Storage Access Framework")
        }

        val workspace = getDefaultWorkspaceDir(context)
        val workspaceCanonical = workspace.canonicalPath

        if (trimmed.isEmpty() || trimmed == "." || trimmed == "./") {
            return File(workspaceCanonical)
        }

        val candidate = if (trimmed.startsWith("/")) {
            val f = File(trimmed)
            val canonical = try { f.canonicalPath } catch (_: Exception) { f.absolutePath }

            val forbidden = listOf("/data/system", "/data/local", "/data/misc", "/etc", "/proc", "/sys")
            if (forbidden.any { isSameOrUnder(canonical, it) }) {
                throw SecurityException("Access to system directory is not permitted: $canonical")
            }

            if (isSameOrUnder(canonical, workspaceCanonical)) {
                File(canonical)
            } else {
                val extRoot = try {
                    Environment.getExternalStorageDirectory().canonicalPath
                } catch (_: Exception) {
                    null
                }
                val sharedRoots = listOfNotNull(extRoot, "/sdcard", "/storage/emulated/0")
                val matchedRoot = sharedRoots.firstOrNull { isSameOrUnder(canonical, it) }
                    ?: throw SecurityException("Access outside the app workspace is not permitted: $trimmed")
                val relative = canonical.removePrefix(matchedRoot).trimStart('/')
                File(workspace, relative)
            }
        } else {
            File(workspace, trimmed)
        }

        val canonicalResult = try { candidate.canonicalPath } catch (e: Exception) {
            throw SecurityException("Invalid path: cannot resolve canonical path")
        }

        if (!isSameOrUnder(canonicalResult, workspaceCanonical)) {
            throw SecurityException("Path traversal attempt detected: $canonicalResult")
        }

        return File(canonicalResult)
    }

    /** True when [path] equals [root] or lies strictly below it (separator-aware prefix test). */
    private fun isSameOrUnder(path: String, root: String): Boolean {
        val normalizedRoot = root.trimEnd(File.separatorChar)
        if (normalizedRoot.isEmpty()) return path.startsWith(File.separator) // root is "/"
        return path == normalizedRoot || path.startsWith(normalizedRoot + File.separator)
    }

    /**
     * True when [file] is the workspace root itself. Destructive or structural operations
     * (delete, move, copy/zip/unzip destination) refuse the root so a blank or root-like
     * path can never wipe or clobber the user's whole workspace.
     */
    private fun isWorkspaceRoot(context: Context, file: File): Boolean {
        val workspaceCanonical = getDefaultWorkspaceDir(context).canonicalPath
        val canonical = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
        return canonical == workspaceCanonical
    }

    /**
     * True when a cleaned SAF-relative path names the tree root itself (blank, `.`, or only
     * separators), i.e. the whole folder the user picked.
     */
    private fun isSafRootPath(cleanPath: String): Boolean =
        cleanPath.split("/").none { it.isNotEmpty() && it != "." }

    // ── Backend selection ────────────────────────────────────────────────
    //
    // Every public operation picks exactly one backend per call: the user's custom SAF folder
    // (when one is configured and the path is not an explicit app-internal path) or the app's
    // private workspace. A failure in the custom folder is reported as a failure. It must never
    // silently fall back to the workspace, because the caller would then act (or later delete)
    // on a different file than the one the user sees in their folder.

    private const val CUSTOM_FOLDER_UNAVAILABLE =
        "Couldn't open your chosen storage folder. Please select it again in Settings."

    /**
     * Opens the root of the user's custom folder, or null if it is not configured or cannot be
     * opened right now (permission revoked, provider unavailable, ...).
     */
    private fun openCustomRoot(context: Context): DocumentNode? {
        val customUri = getCustomFolderUri(context) ?: return null
        val root: DocumentFile? = try {
            DocumentFile.fromTreeUri(context, customUri)
        } catch (_: Exception) {
            null
        }
        if (root == null) return null
        return DocumentFileNode(context, root)
    }

    private fun customFolderUnavailable(): ActionResult = ActionResult(false, null, CUSTOM_FOLDER_UNAVAILABLE)

    /**
     * For two-path operations both paths must resolve to the same backend; otherwise the
     * operation would silently read from one location and write to another.
     */
    private fun mixedBackends(verb: String): ActionResult = ActionResult(
        false,
        null,
        "Couldn't $verb: source and destination must both be inside your chosen folder or both inside the app workspace.",
    )

    // ── File Operations ──────────────────────────────────────────────────

    fun listFiles(context: Context, pathStr: String?): ActionResult {
        if (!shouldUseCustomFolder(context, pathStr)) return listFilesLocal(context, pathStr)
        val root = openCustomRoot(context) ?: return customFolderUnavailable()
        return listFilesIn(root, pathStr)
    }

    internal fun listFilesIn(root: DocumentNode, pathStr: String?): ActionResult {
        return try {
            val cleanPath = cleanRelativePath(pathStr)
            val targetDir = if (cleanPath.isEmpty()) root else findDocumentByPath(root, cleanPath)
            if (targetDir == null || !targetDir.exists()) {
                return ActionResult(false, null, "Directory does not exist: ${pathStr ?: ""}")
            }
            if (!targetDir.isDirectory) {
                return ActionResult(false, null, "Path is not a directory: ${pathStr ?: ""}")
            }
            val fileList = targetDir.listFiles().joinToString("\n") { file ->
                val type = if (file.isDirectory) "DIR" else "FILE"
                "${file.name ?: "unnamed"} [$type] (${file.length()} bytes)"
            }
            ActionResult(true, if (fileList.isEmpty()) "Directory is empty." else fileList, null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't list files in your chosen folder: ${e.localizedMessage}")
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
        if (!shouldUseCustomFolder(context, filePath)) return readFileLocal(context, filePath)
        val root = openCustomRoot(context) ?: return customFolderUnavailable()
        return readFileIn(root, filePath)
    }

    internal fun readFileIn(root: DocumentNode, filePath: String): ActionResult {
        return try {
            val file = findDocumentByPath(root, cleanRelativePath(filePath))
            if (file == null || !file.exists()) {
                return ActionResult(false, null, "File does not exist: $filePath")
            }
            if (file.isDirectory) {
                return ActionResult(false, null, "Path is a directory, not a file: $filePath")
            }
            if (file.length() > MAX_FILE_SIZE_BYTES) {
                return ActionResult(false, null, "File exceeds size limit of 100KB: $filePath")
            }
            val content = file.openInputStream()?.bufferedReader()?.use { it.readText() }
                ?: return ActionResult(false, null, "Could not open file stream: $filePath")
            ActionResult(true, content, null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't read file from your chosen folder: ${e.localizedMessage}")
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
        if (!shouldUseCustomFolder(context, filePath)) return writeFileLocal(context, filePath, content)
        val root = openCustomRoot(context) ?: return customFolderUnavailable()
        return writeFileIn(root, filePath, content)
    }

    internal fun writeFileIn(root: DocumentNode, filePath: String, content: String): ActionResult {
        return try {
            val cleanPath = cleanRelativePath(filePath)
            if (cleanPath.isEmpty()) {
                return ActionResult(false, null, "Couldn't write file: no file name given.")
            }
            val doc = findOrCreateDocumentByPath(root, cleanPath, isDirectory = false)
                ?: return ActionResult(false, null, "Couldn't create $filePath in your chosen folder.")
            if (doc.isDirectory) {
                return ActionResult(false, null, "Path is a directory, not a file: $filePath")
            }
            val stream = doc.openOutputStream()
                ?: return ActionResult(false, null, "Could not open file stream to write: $filePath")
            stream.use { out -> out.bufferedWriter().use { it.write(content) } }
            ActionResult(true, "File saved at ${doc.name ?: filePath}", null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't write to your chosen folder: ${e.localizedMessage}")
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
        if (!shouldUseCustomFolder(context, filePath)) return deleteFileLocal(context, filePath)
        val root = openCustomRoot(context) ?: return customFolderUnavailable()
        return deleteFileIn(root, filePath)
    }

    internal fun deleteFileIn(root: DocumentNode, filePath: String): ActionResult {
        return try {
            val cleanPath = cleanRelativePath(filePath)
            if (isSafRootPath(cleanPath)) {
                // A blank or root-like path would resolve to the tree root, i.e. the
                // whole folder the user picked (e.g. DCIM). Never delete that.
                return ActionResult(false, null, ROOT_REFUSED_MESSAGE)
            }
            val doc = findDocumentByPath(root, cleanPath)
            if (doc == null || !doc.exists()) {
                return ActionResult(false, null, "File/directory does not exist: $filePath")
            }
            if (doc.delete()) {
                ActionResult(true, "Deleted $filePath!", null)
            } else {
                ActionResult(false, null, "Failed to delete: $filePath")
            }
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't delete from your chosen folder: ${e.localizedMessage}")
        }
    }

    private fun deleteFileLocal(context: Context, filePath: String): ActionResult {
        return try {
            val file = resolveFile(context, filePath)
            if (isWorkspaceRoot(context, file)) {
                return ActionResult(false, null, ROOT_REFUSED_MESSAGE)
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
        if (!shouldUseCustomFolder(context, pathStr)) return createDirectoryLocal(context, pathStr)
        val root = openCustomRoot(context) ?: return customFolderUnavailable()
        return createDirectoryIn(root, pathStr)
    }

    internal fun createDirectoryIn(root: DocumentNode, pathStr: String): ActionResult {
        return try {
            val cleanPath = cleanRelativePath(pathStr)
            if (cleanPath.isEmpty()) {
                return ActionResult(true, "That folder already exists.", null)
            }
            val existing = findDocumentByPath(root, cleanPath)
            if (existing != null && existing.exists()) {
                return if (existing.isDirectory) {
                    ActionResult(true, "That folder already exists at $pathStr.", null)
                } else {
                    ActionResult(false, null, "Path exists but is a file, not a directory: $pathStr")
                }
            }
            val dir = findOrCreateDocumentByPath(root, cleanPath, isDirectory = true)
            if (dir != null && dir.isDirectory) {
                ActionResult(true, "Folder created at $pathStr!", null)
            } else {
                ActionResult(false, null, "Failed to create directory in your chosen folder: $pathStr")
            }
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't create folder in your chosen folder: ${e.localizedMessage}")
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
        val useCustom = shouldUseCustomFolder(context, srcPath)
        if (useCustom != shouldUseCustomFolder(context, destPath)) return mixedBackends("copy")
        if (!useCustom) return copyFileLocal(context, srcPath, destPath)
        val root = openCustomRoot(context) ?: return customFolderUnavailable()
        return copyFileIn(root, srcPath, destPath)
    }

    internal fun copyFileIn(root: DocumentNode, srcPath: String, destPath: String): ActionResult {
        return try {
            val cleanSrc = cleanRelativePath(srcPath)
            val cleanDest = cleanRelativePath(destPath)
            val src = findDocumentByPath(root, cleanSrc)
            if (src == null || !src.exists()) {
                return ActionResult(false, null, "Source path does not exist: $srcPath")
            }
            if (isSafRootPath(cleanDest)) {
                return ActionResult(false, null, ROOT_REFUSED_MESSAGE)
            }
            if (src.isDirectory && isSameOrInside(cleanSrc, cleanDest)) {
                return ActionResult(false, null, "Couldn't copy: a folder can't be copied into itself.")
            }
            val dest = findOrCreateDocumentByPath(root, cleanDest, isDirectory = src.isDirectory)
                ?: return ActionResult(false, null, "Couldn't create $destPath in your chosen folder.")
            copyNode(src, dest)
            ActionResult(true, "Copied $srcPath to $destPath in your chosen folder!", null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't copy file in your chosen folder: ${e.localizedMessage}")
        }
    }

    private fun copyFileLocal(context: Context, srcPath: String, destPath: String): ActionResult {
        return try {
            val src = resolveFile(context, srcPath)
            val dest = resolveFile(context, destPath)
            if (isWorkspaceRoot(context, src) || isWorkspaceRoot(context, dest)) {
                return ActionResult(false, null, ROOT_REFUSED_MESSAGE)
            }
            if (!src.exists()) {
                return ActionResult(false, null, "Source path does not exist: ${src.absolutePath}")
            }
            overlapError(src, dest, "copy")?.let { return ActionResult(false, null, it) }
            copyRecursively(src, dest)
            ActionResult(true, "Copied from ${src.absolutePath} to ${dest.absolutePath}!", null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't copy file: ${e.localizedMessage}")
        }
    }

    fun moveFile(context: Context, srcPath: String, destPath: String): ActionResult {
        val useCustom = shouldUseCustomFolder(context, srcPath)
        if (useCustom != shouldUseCustomFolder(context, destPath)) return mixedBackends("move")
        if (!useCustom) return moveFileLocal(context, srcPath, destPath)
        val root = openCustomRoot(context) ?: return customFolderUnavailable()
        return moveFileIn(root, srcPath, destPath)
    }

    /**
     * Moves by copying to the destination and then deleting the source. The source is only
     * removed once the copy has fully succeeded, so a failed move never loses data.
     */
    internal fun moveFileIn(root: DocumentNode, srcPath: String, destPath: String): ActionResult {
        return try {
            val cleanSrc = cleanRelativePath(srcPath)
            val cleanDest = cleanRelativePath(destPath)
            if (isSafRootPath(cleanSrc)) {
                return ActionResult(false, null, ROOT_REFUSED_MESSAGE)
            }
            val src = findDocumentByPath(root, cleanSrc)
            if (src == null || !src.exists()) {
                return ActionResult(false, null, "Source path does not exist: $srcPath")
            }
            if (isSafRootPath(cleanDest)) {
                return ActionResult(false, null, ROOT_REFUSED_MESSAGE)
            }
            if (isSameOrInside(cleanSrc, cleanDest)) {
                return ActionResult(false, null, "Couldn't move: destination is inside the source.")
            }
            val dest = findOrCreateDocumentByPath(root, cleanDest, isDirectory = src.isDirectory)
                ?: return ActionResult(false, null, "Couldn't create $destPath in your chosen folder.")
            copyNode(src, dest)
            if (!src.delete()) {
                return ActionResult(
                    false,
                    null,
                    "Copied $srcPath to $destPath but couldn't remove the original; both now exist.",
                )
            }
            ActionResult(true, "Moved $srcPath to $destPath in your chosen folder!", null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't move file in your chosen folder: ${e.localizedMessage}")
        }
    }

    private fun moveFileLocal(context: Context, srcPath: String, destPath: String): ActionResult {
        return try {
            val src = resolveFile(context, srcPath)
            val dest = resolveFile(context, destPath)
            if (isWorkspaceRoot(context, src) || isWorkspaceRoot(context, dest)) {
                return ActionResult(false, null, ROOT_REFUSED_MESSAGE)
            }
            if (!src.exists()) {
                return ActionResult(false, null, "Source path does not exist: ${src.absolutePath}")
            }
            overlapError(src, dest, "move")?.let { return ActionResult(false, null, it) }
            dest.parentFile?.mkdirs()
            val renamed = src.renameTo(dest)
            if (renamed) {
                ActionResult(true, "Moved from ${src.absolutePath} to ${dest.absolutePath}!", null)
            } else {
                // copyRecursively throws on any failure, so the source is only removed
                // once every file has been copied.
                copyRecursively(src, dest)
                if (src.deleteRecursively()) {
                    ActionResult(true, "Moved from ${src.absolutePath} to ${dest.absolutePath}!", null)
                } else {
                    ActionResult(
                        false,
                        null,
                        "Copied to ${dest.absolutePath} but couldn't remove the original at ${src.absolutePath}"
                    )
                }
            }
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't move file: ${e.localizedMessage}")
        }
    }

    fun zipFiles(context: Context, srcPath: String, zipFilePath: String): ActionResult {
        val useCustom = shouldUseCustomFolder(context, srcPath)
        if (useCustom != shouldUseCustomFolder(context, zipFilePath)) return mixedBackends("zip")
        if (!useCustom) return zipFilesLocal(context, srcPath, zipFilePath)
        val root = openCustomRoot(context) ?: return customFolderUnavailable()
        return zipFilesIn(root, srcPath, zipFilePath)
    }

    internal fun zipFilesIn(root: DocumentNode, srcPath: String, zipFilePath: String): ActionResult {
        return try {
            val cleanSrc = cleanRelativePath(srcPath)
            val cleanZip = cleanRelativePath(zipFilePath)
            val src = if (cleanSrc.isEmpty()) root else findDocumentByPath(root, cleanSrc)
            if (src == null || !src.exists()) {
                return ActionResult(false, null, "Source path does not exist: $srcPath")
            }
            if (isSafRootPath(cleanZip)) {
                return ActionResult(false, null, ROOT_REFUSED_MESSAGE)
            }
            val zipDoc = findOrCreateDocumentByPath(root, cleanZip, isDirectory = false)
                ?: return ActionResult(false, null, "Couldn't create $zipFilePath in your chosen folder.")
            if (zipDoc.isDirectory) {
                return ActionResult(false, null, "Zip path is a directory, not a file: $zipFilePath")
            }
            // If the archive is being written inside the folder being zipped, leave it out of itself.
            val skipRelative = if (src.isDirectory && isSameOrInside(cleanSrc, cleanZip)) {
                cleanZip.removePrefix(cleanSrc).trimStart('/')
            } else {
                null
            }
            val out = zipDoc.openOutputStream()
                ?: return ActionResult(false, null, "Could not open zip file for writing: $zipFilePath")
            ZipOutputStream(out.buffered()).use { zos ->
                val baseName = src.name ?: cleanSrc.substringAfterLast('/').ifEmpty { "folder" }
                zipNode(src, baseName, "", skipRelative, zos)
            }
            ActionResult(true, "Zipped $srcPath into $zipFilePath in your chosen folder!", null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't zip files in your chosen folder: ${e.localizedMessage}")
        }
    }

    private fun zipFilesLocal(context: Context, srcPath: String, zipFilePath: String): ActionResult {
        return try {
            val src = resolveFile(context, srcPath)
            val zipFile = resolveFile(context, zipFilePath)
            if (isWorkspaceRoot(context, zipFile)) {
                return ActionResult(false, null, ROOT_REFUSED_MESSAGE)
            }
            if (!src.exists()) {
                return ActionResult(false, null, "Source path does not exist: ${src.absolutePath}")
            }
            if (isSameOrInside(zipFile, src)) {
                return ActionResult(
                    false,
                    null,
                    "Can't create the zip inside the folder being zipped: ${zipFile.absolutePath} is inside ${src.absolutePath}"
                )
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
        val useCustom = shouldUseCustomFolder(context, zipFilePath)
        if (useCustom != shouldUseCustomFolder(context, destDirPath)) return mixedBackends("unzip")
        if (!useCustom) return unzipFileLocal(context, zipFilePath, destDirPath)
        val root = openCustomRoot(context) ?: return customFolderUnavailable()
        return unzipFileIn(root, zipFilePath, destDirPath)
    }

    internal fun unzipFileIn(root: DocumentNode, zipFilePath: String, destDirPath: String): ActionResult {
        return try {
            val cleanZip = cleanRelativePath(zipFilePath)
            val cleanDest = cleanRelativePath(destDirPath)
            if (isSafRootPath(cleanDest)) {
                return ActionResult(false, null, ROOT_REFUSED_MESSAGE)
            }
            val zipDoc = findDocumentByPath(root, cleanZip)
            if (zipDoc == null || !zipDoc.exists()) {
                return ActionResult(false, null, "Zip file does not exist: $zipFilePath")
            }
            if (zipDoc.isDirectory) {
                return ActionResult(false, null, "Zip path is a directory, not a file: $zipFilePath")
            }
            val destDir = findOrCreateDocumentByPath(root, cleanDest, isDirectory = true)
                ?: return ActionResult(false, null, "Couldn't create $destDirPath in your chosen folder.")
            if (!destDir.isDirectory) {
                return ActionResult(false, null, "Destination exists but is a file, not a directory: $destDirPath")
            }
            val input = zipDoc.openInputStream()
                ?: return ActionResult(false, null, "Could not open zip file for reading: $zipFilePath")
            ZipInputStream(input.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val parts = entry.name.split("/").filter { it.isNotEmpty() && it != "." }
                    if (entry.name.startsWith("/") || parts.any { it == ".." }) {
                        throw SecurityException("ZipSlip: entry '${entry.name}' is outside of target dir")
                    }
                    if (parts.isNotEmpty()) {
                        val entryPath = parts.joinToString("/")
                        if (entry.isDirectory) {
                            findOrCreateDocumentByPath(destDir, entryPath, isDirectory = true)
                                ?: throw IOException("Couldn't create folder $entryPath")
                        } else {
                            val target = findOrCreateDocumentByPath(destDir, entryPath, isDirectory = false)
                                ?: throw IOException("Couldn't create file $entryPath")
                            if (target.isDirectory) {
                                throw IOException("Couldn't write $entryPath: a folder with that name exists")
                            }
                            val output = target.openOutputStream()
                                ?: throw IOException("Couldn't open $entryPath for writing")
                            output.use { zis.copyTo(it) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            ActionResult(true, "Unzipped $zipFilePath to $destDirPath in your chosen folder!", null)
        } catch (e: Exception) {
            ActionResult(false, null, "Couldn't unzip file in your chosen folder: ${e.localizedMessage}")
        }
    }

    private fun unzipFileLocal(context: Context, zipFilePath: String, destDirPath: String): ActionResult {
        return try {
            val zipFile = resolveFile(context, zipFilePath)
            val destDir = resolveFile(context, destDirPath)
            if (isWorkspaceRoot(context, destDir)) {
                return ActionResult(false, null, ROOT_REFUSED_MESSAGE)
            }
            if (!zipFile.exists()) {
                return ActionResult(false, null, "Zip file does not exist: ${zipFile.absolutePath}")
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

    private fun cleanRelativePath(pathStr: String?): String {
        var clean = pathStr?.trim().orEmpty()
        val prefixes = listOf("/sdcard/", "/storage/emulated/0/", "sdcard/", "./")
        for (prefix in prefixes) {
            if (clean.startsWith(prefix)) {
                clean = clean.removePrefix(prefix)
            }
        }
        return clean.trimStart('/').trimEnd('/')
    }

    /** True when [child] names [parent] itself or something nested under it. */
    private fun isSameOrInside(parent: String, child: String): Boolean {
        if (parent.isEmpty()) return true
        return child == parent || child.startsWith("$parent/")
    }

    private fun findDocumentByPath(root: DocumentNode, path: String): DocumentNode? {
        val parts = path.split("/").filter { it.isNotEmpty() && it != "." }
        var current = root
        for (part in parts) {
            current = current.findFile(part) ?: return null
        }
        return current
    }

    /**
     * Walks [path] below [root], creating missing intermediate directories and the leaf.
     * Returns null when a component cannot be created or an intermediate component is a file.
     */
    private fun findOrCreateDocumentByPath(
        root: DocumentNode,
        path: String,
        isDirectory: Boolean = false,
    ): DocumentNode? {
        val parts = path.split("/").filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return root
        var current = root
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            val child = current.findFile(part) ?: current.createDirectory(part) ?: return null
            if (!child.isDirectory) return null
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
            current.createFile(guessMimeType(leaf), leaf)
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

    /**
     * True when [candidate] is the same path as [base] or lies anywhere beneath it.
     * Compares canonical paths so symlinks and `..` segments can't disguise an overlap.
     */
    private fun isSameOrInside(candidate: File, base: File): Boolean {
        val candidatePath = try { candidate.canonicalPath } catch (_: Exception) { candidate.absolutePath }
        val basePath = try { base.canonicalPath } catch (_: Exception) { base.absolutePath }
        return candidatePath == basePath || candidatePath.startsWith(basePath + File.separator)
    }

    /**
     * Returns a user-facing error when a copy/move from [src] to [dest] would overlap
     * (destination equals the source or lies inside it), or null when the pair is safe.
     */
    private fun overlapError(src: File, dest: File, verb: String): String? {
        if (!isSameOrInside(dest, src)) return null
        val samePath = try { src.canonicalPath == dest.canonicalPath } catch (_: Exception) { src.absolutePath == dest.absolutePath }
        return if (samePath) {
            "Can't $verb ${src.absolutePath} onto itself"
        } else {
            "Can't $verb ${src.absolutePath} into its own subfolder ${dest.absolutePath}"
        }
    }

    /**
     * Copies [src] into [dest], which must already exist and be of the same kind (file or
     * directory). Directory contents are merged into [dest]; files are overwritten.
     */
    private fun copyNode(src: DocumentNode, dest: DocumentNode) {
        if (src.isDirectory) {
            if (!dest.isDirectory) {
                throw IOException("Destination ${dest.name ?: ""} exists but is a file, not a directory")
            }
            for (child in src.listFiles()) {
                val childName = child.name ?: continue
                val existing = dest.findFile(childName)
                val created: DocumentNode? = if (child.isDirectory) {
                    existing ?: dest.createDirectory(childName)
                } else {
                    existing ?: dest.createFile(guessMimeType(childName), childName)
                }
                val target = created ?: throw IOException("Couldn't create $childName in destination")
                copyNode(child, target)
            }
        } else {
            if (dest.isDirectory) {
                throw IOException("Destination ${dest.name ?: ""} is a directory, not a file")
            }
            val input = src.openInputStream()
                ?: throw IOException("Couldn't open ${src.name ?: "source"} for reading")
            input.use { i ->
                val output = dest.openOutputStream()
                    ?: throw IOException("Couldn't open ${dest.name ?: "destination"} for writing")
                output.use { o -> i.copyTo(o) }
            }
        }
    }

    /**
     * Adds [node] to [zos] as `entryName`, recursing into directories. [relative] is the path of
     * [node] below the zip source; an entry equal to [skipRelative] (the archive itself) is skipped.
     */
    private fun zipNode(
        node: DocumentNode,
        entryName: String,
        relative: String,
        skipRelative: String?,
        zos: ZipOutputStream,
    ) {
        if (skipRelative != null && relative == skipRelative) return
        if (node.isDirectory) {
            zos.putNextEntry(ZipEntry("$entryName/"))
            zos.closeEntry()
            for (child in node.listFiles()) {
                val childName = child.name ?: continue
                val childRelative = if (relative.isEmpty()) childName else "$relative/$childName"
                zipNode(child, "$entryName/$childName", childRelative, skipRelative, zos)
            }
        } else {
            zos.putNextEntry(ZipEntry(entryName))
            val input = node.openInputStream()
                ?: throw IOException("Couldn't open ${node.name ?: "file"} for reading")
            input.use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    private fun copyRecursively(src: File, dest: File) {
        if (src.isDirectory) {
            // Snapshot the children before creating dest: if dest lives inside src,
            // creating it first would make it part of the listing and recurse forever.
            val children = src.listFiles()
            if (!dest.exists()) {
                dest.mkdirs()
            }
            children?.forEach { file ->
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
