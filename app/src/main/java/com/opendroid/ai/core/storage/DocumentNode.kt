package com.opendroid.ai.core.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal view of one node (file or directory) in the user's chosen SAF folder.
 *
 * [StorageWorkspaceProvider] runs every custom-folder operation against this interface so the
 * logic can be exercised in plain JVM tests with an in-memory fake. Production code uses
 * [DocumentFileNode], which delegates to [DocumentFile].
 */
interface DocumentNode {
    val name: String?
    val isDirectory: Boolean
    fun exists(): Boolean
    fun length(): Long
    fun listFiles(): List<DocumentNode>
    fun findFile(displayName: String): DocumentNode?
    fun createDirectory(displayName: String): DocumentNode?
    fun createFile(mimeType: String, displayName: String): DocumentNode?
    fun delete(): Boolean
    fun openInputStream(): InputStream?

    /** Opens the node for writing, truncating any existing content. */
    fun openOutputStream(): OutputStream?
}

/**
 * [DocumentNode] backed by a real [DocumentFile] inside a SAF tree.
 */
class DocumentFileNode(
    private val context: Context,
    private val doc: DocumentFile,
) : DocumentNode {
    override val name: String? get() = doc.name
    override val isDirectory: Boolean get() = doc.isDirectory
    override fun exists(): Boolean = doc.exists()
    override fun length(): Long = doc.length()
    override fun listFiles(): List<DocumentNode> = doc.listFiles().map { DocumentFileNode(context, it) }
    override fun findFile(displayName: String): DocumentNode? =
        doc.findFile(displayName)?.let { DocumentFileNode(context, it) }
    override fun createDirectory(displayName: String): DocumentNode? =
        doc.createDirectory(displayName)?.let { DocumentFileNode(context, it) }
    override fun createFile(mimeType: String, displayName: String): DocumentNode? =
        doc.createFile(mimeType, displayName)?.let { DocumentFileNode(context, it) }
    override fun delete(): Boolean = doc.delete()
    override fun openInputStream(): InputStream? = context.contentResolver.openInputStream(doc.uri)
    override fun openOutputStream(): OutputStream? = context.contentResolver.openOutputStream(doc.uri, "wt")
}
