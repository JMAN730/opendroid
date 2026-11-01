package com.opendroid.ai.core.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * In-memory [DocumentNode] tree standing in for a SAF folder in tests.
 *
 * Mirrors the semantics of `DocumentFile` that the provider relies on: `findFile` matches on
 * display name, `createFile`/`createDirectory` return null when the parent is a file, `delete`
 * removes the node (and its subtree) from its parent, and `openOutputStream` truncates.
 */
class FakeDocumentNode private constructor(
    override val name: String?,
    override val isDirectory: Boolean,
    private val parent: FakeDocumentNode?,
) : DocumentNode {

    private val children = mutableListOf<FakeDocumentNode>()
    private var content = ByteArray(0)
    private var deleted = false

    companion object {
        fun root(name: String = "Documents"): FakeDocumentNode = FakeDocumentNode(name, true, null)
    }

    /** Test helper: creates (or overwrites) a file at [path] below this node, making directories. */
    fun putFile(path: String, text: String): FakeDocumentNode {
        val parts = path.split("/").filter { it.isNotEmpty() }
        var current = this
        for (part in parts.dropLast(1)) {
            current = current.findChild(part) ?: current.createDirectory(part) as FakeDocumentNode
        }
        val leaf = parts.last()
        val file = current.findChild(leaf) ?: current.createFile("text/plain", leaf) as FakeDocumentNode
        file.content = text.toByteArray()
        return file
    }

    /** Test helper: creates a directory at [path] below this node. */
    fun putDir(path: String): FakeDocumentNode {
        var current = this
        for (part in path.split("/").filter { it.isNotEmpty() }) {
            current = current.findChild(part) ?: current.createDirectory(part) as FakeDocumentNode
        }
        return current
    }

    /** Test helper: the node at [path] below this node, or null. */
    fun nodeAt(path: String): FakeDocumentNode? {
        var current = this
        for (part in path.split("/").filter { it.isNotEmpty() }) {
            current = current.findChild(part) ?: return null
        }
        return current
    }

    /** Test helper: the text content of the file at [path], or null if absent. */
    fun textAt(path: String): String? = nodeAt(path)?.takeIf { !it.isDirectory }?.content?.toString(Charsets.UTF_8)

    private fun findChild(displayName: String): FakeDocumentNode? = children.firstOrNull { it.name == displayName }

    override fun exists(): Boolean = !deleted

    override fun length(): Long = content.size.toLong()

    override fun listFiles(): List<DocumentNode> = children.toList()

    override fun findFile(displayName: String): DocumentNode? = findChild(displayName)

    override fun createDirectory(displayName: String): DocumentNode? {
        if (!isDirectory || findChild(displayName) != null) return null
        return FakeDocumentNode(displayName, true, this).also { children.add(it) }
    }

    override fun createFile(mimeType: String, displayName: String): DocumentNode? {
        if (!isDirectory || findChild(displayName) != null) return null
        return FakeDocumentNode(displayName, false, this).also { children.add(it) }
    }

    override fun delete(): Boolean {
        val p = parent ?: return false
        p.children.remove(this)
        deleted = true
        return true
    }

    override fun openInputStream(): InputStream? {
        if (isDirectory) return null
        return ByteArrayInputStream(content)
    }

    override fun openOutputStream(): OutputStream? {
        if (isDirectory) return null
        val node = this
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                node.content = toByteArray()
            }
        }
    }
}
