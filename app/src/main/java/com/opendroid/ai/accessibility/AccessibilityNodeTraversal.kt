package com.opendroid.ai.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import javax.inject.Inject

/**
 * Result of [AccessibilityNodeTraversal.findAndType]. [overwrittenText] is the
 * field's earlier content when the whole field was replaced, so the step result
 * can say what was lost; null when the field was empty or only a match was swapped.
 */
data class TypeOutcome(val typed: Boolean, val overwrittenText: String? = null) {
    companion object {
        val NOT_FOUND = TypeOutcome(typed = false)
    }
}

/**
 * Pure node-tree traversal/matching logic used by [OpenDroidAccessibilityService],
 * extracted so it can be exercised in tests without a bound accessibility service.
 *
 * Every function here takes the root [AccessibilityNodeInfo] as a parameter instead
 * of reading `rootInActiveWindow` itself, so a test can hand it either the real
 * service's root or a root obtained straight from `UiAutomation` - same behavior,
 * no service required. See #66 (prototype) / #105 (this extraction).
 *
 * Stateless and dependency-free: production wires it in through Hilt, tests can
 * just `AccessibilityNodeTraversal()` it directly.
 *
 * Callers remain responsible for recycling the root node they obtained; this class
 * only recycles the intermediate/child nodes it creates while walking.
 */
class AccessibilityNodeTraversal @Inject constructor() {

    /** Concatenates the text/contentDescription of every node in the tree, depth-first. */
    fun screenText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val sb = StringBuilder()
        collectText(root, sb)
        return sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val nodeText = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()

        if (!nodeText.isNullOrEmpty()) {
            sb.append(nodeText).append("\n")
        } else if (!contentDesc.isNullOrEmpty()) {
            sb.append(contentDesc).append("\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb)
            child.recycle()
        }
    }

    /**
     * Finds the first node matching [text] (or the nearest clickable ancestor,
     * for leaves that aren't themselves clickable) and performs ACTION_CLICK on it.
     * Returns whether a click was dispatched.
     */
    fun findAndClick(root: AccessibilityNodeInfo?, text: String): Boolean {
        if (root == null) return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                return true
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    node.recycle()
                    return true
                }
                parent = parent.parent
            }
            node.recycle()
        }
        return false
    }

    /**
     * Finds an editable field for [searchText] and types [content] into it.
     *
     * A field is matched by its own label first: its hint, the text of the node
     * that labels it (`labelFor`), or its contentDescription. That label is what
     * names an empty field, and the whole field is replaced. Only when no label
     * matches is a field matched by the text already in it, and then just the first
     * case-insensitive occurrence of [searchText] is swapped for [content], so
     * "milk" -> "oat milk" in a note edits one line instead of wiping the note (#165).
     */
    fun findAndType(root: AccessibilityNodeInfo?, searchText: String, content: String): TypeOutcome {
        if (root == null || searchText.isEmpty()) return TypeOutcome.NOT_FOUND
        val fields = mutableListOf<AccessibilityNodeInfo>()
        collectEditable(root, fields, isRoot = true)
        try {
            fields.firstOrNull { labelMatches(it, searchText) }?.let { field ->
                val previous = currentText(field)
                setText(field, content)
                return TypeOutcome(typed = true, overwrittenText = previous.ifEmpty { null })
            }
            for (field in fields) {
                val current = currentText(field)
                val start = current.indexOf(searchText, ignoreCase = true)
                if (start < 0) continue
                setText(field, current.replaceRange(start, start + searchText.length, content))
                return TypeOutcome(typed = true)
            }
            return TypeOutcome.NOT_FOUND
        } finally {
            fields.forEach { if (it !== root) it.recycle() }
        }
    }

    private fun collectEditable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, isRoot: Boolean) {
        val keep = node.isEditable
        if (keep) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditable(child, out, isRoot = false)
        }
        if (!keep && !isRoot) node.recycle()
    }

    private fun labelMatches(field: AccessibilityNodeInfo, searchText: String): Boolean {
        val label = field.labeledBy
        val labelText = label?.text?.toString()
        label?.recycle()
        return listOf(field.hintText?.toString(), labelText, field.contentDescription?.toString())
            .any { it != null && it.contains(searchText, ignoreCase = true) }
    }

    /** The field's real content; an empty field reports its hint as its text. */
    private fun currentText(field: AccessibilityNodeInfo): String {
        val text = field.text?.toString() ?: return ""
        val hint = field.hintText?.toString()
        return if (field.isShowingHintText || (!hint.isNullOrEmpty() && text == hint)) "" else text
    }

    private fun setText(field: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Walks the tree depth-first for a clickable node whose text or
     * contentDescription (case-insensitive) is one of [labels], and clicks it.
     * This is the IME-submit fallback used when ACTION_IME_ENTER isn't
     * available or fails.
     */
    fun findAndClickSubmitControl(root: AccessibilityNodeInfo?, labels: List<String>): Boolean {
        if (root == null) return false
        return findSubmitNode(root, labels)
    }

    private fun findSubmitNode(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase()
        val contentDesc = node.contentDescription?.toString()?.lowercase()
        if (node.isClickable && (labels.contains(text) || labels.contains(contentDesc))) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findSubmitNode(child, labels)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }
}
