package com.nova.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Nova's Android "eyes and hands".
 *
 * This service intentionally exposes generic UI operations. It does not contain
 * app-specific rules for WhatsApp, Play Store, YouTube, etc. The agent decides
 * what an element means; this class only observes and operates the Android UI.
 */
class NovaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: NovaAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun get(): NovaAccessibilityService? = instance
    }

    @Volatile
    private var lastPackageName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        lastPackageName = event.packageName?.toString()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Returns a compact, AI-friendly description of the currently visible UI. */
    fun getScreenSnapshot(maxNodes: Int = 160): String {
        val root = rootInActiveWindow ?: return "NO_ACTIVE_WINDOW"
        val packageName = root.packageName?.toString() ?: lastPackageName ?: "unknown"
        val windowTitle = root.contentDescription?.toString().orEmpty()
        val lines = ArrayList<String>()
        val visited = HashSet<Int>()

        collectNodes(root, lines, visited, maxNodes, 0)

        val header = buildString {
            append("PACKAGE=").append(packageName).append('\n')
            if (windowTitle.isNotBlank()) append("WINDOW=").append(clean(windowTitle)).append('\n')
            append("ELEMENTS=").append(lines.size).append('\n')
        }
        return header + lines.joinToString("\n")
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<String>,
        visited: MutableSet<Int>,
        maxNodes: Int,
        depth: Int
    ) {
        if (output.size >= maxNodes) return

        val identity = System.identityHashCode(node)
        if (!visited.add(identity)) return

        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        val className = node.className?.toString().orEmpty()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val useful = text.isNotBlank() || desc.isNotBlank() || id.isNotBlank() ||
            node.isClickable || node.isEditable || node.isScrollable ||
            node.isFocusable || node.isCheckable

        if (useful) {
            val indent = "  ".repeat(depth.coerceAtMost(8))
            output += buildString {
                append(indent)
                append("[class=").append(simpleClassName(className))
                append(", text=").append(quote(clean(text)))
                append(", desc=").append(quote(clean(desc)))
                append(", id=").append(quote(id))
                append(", bounds=").append(bounds.left).append(',').append(bounds.top)
                    .append('-').append(bounds.right).append(',').append(bounds.bottom)
                append(", clickable=").append(node.isClickable)
                append(", editable=").append(node.isEditable)
                append(", scrollable=").append(node.isScrollable)
                append(", checkable=").append(node.isCheckable)
                append(", checked=").append(node.isChecked)
                append(", enabled=").append(node.isEnabled)
                append("]")
            }
        }

        for (i in 0 until node.childCount) {
            if (output.size >= maxNodes) break
            node.getChild(i)?.let { child ->
                collectNodes(child, output, visited, maxNodes, depth + 1)
            }
        }
    }

    /** Clicks the first visible node whose text contains the supplied text. */
    fun clickText(target: String): Boolean = withRoot { root ->
        findBestNode(root, target, MatchMode.TEXT)?.let { clickNode(it) } ?: false
    }

    /** Clicks the first visible node whose content description contains the supplied text. */
    fun clickDescription(target: String): Boolean = withRoot { root ->
        findBestNode(root, target, MatchMode.DESCRIPTION)?.let { clickNode(it) } ?: false
    }

    /** Clicks an element by its Android resource-id when available. */
    fun clickViewId(viewId: String): Boolean = withRoot { root ->
        val node = findByViewId(root, viewId) ?: return@withRoot false
        clickNode(node)
    }

    /** Types text into the currently focused editable field. */
    fun typeText(text: String): Boolean = withRoot { root ->
        val focused = findFocusedEditable(root)
            ?: findFirstEditable(root)
            ?: return@withRoot false

        if (!focused.isFocused) focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Finds an editable field and places focus in it, without typing. */
    fun focusTextField(target: String? = null): Boolean = withRoot { root ->
        val node = if (!target.isNullOrBlank()) {
            findBestNode(root, target, MatchMode.TEXT)
        } else {
            findFocusedEditable(root) ?: findFirstEditable(root)
        }
        node?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ?: false
    }

    /** Scrolls the best matching scrollable container forward/backward. */
    fun scroll(forward: Boolean = true): Boolean = withRoot { root ->
        val node = findScrollable(root) ?: return@withRoot false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        node.performAction(action)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Taps the center of a screen coordinate. */
    fun tap(x: Float, y: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Returns whether the requested visible text exists right now. */
    fun hasText(target: String): Boolean = withRoot { root ->
        findBestNode(root, target, MatchMode.TEXT) != null
    }

    /** Returns whether the requested content description exists right now. */
    fun hasDescription(target: String): Boolean = withRoot { root ->
        findBestNode(root, target, MatchMode.DESCRIPTION) != null
    }

    /** Returns text from the best matching visible node. Useful for passing data between agent steps. */
    fun readText(target: String): String? {
        val root = rootInActiveWindow ?: return null
        return findBestNode(root, target, MatchMode.TEXT)?.text?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** Rich semantic view of interactive controls for difficult UI screens. */
    fun getInteractiveElementsSnapshot(maxNodes: Int = 220): String {
        val root = rootInActiveWindow ?: return "NO_ACTIVE_WINDOW"
        val out = ArrayList<String>()
        val seen = HashSet<Int>()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (out.size >= maxNodes) return
            val identity = System.identityHashCode(node)
            if (!seen.add(identity)) return
            val clickable = node.isClickable || node.isLongClickable || node.isCheckable ||
                node.isEditable || node.isScrollable || node.isFocusable
            if (clickable) {
                val b = Rect().also { node.getBoundsInScreen(it) }
                val text = clean(node.text?.toString().orEmpty())
                val desc = clean(node.contentDescription?.toString().orEmpty())
                val id = node.viewIdResourceName.orEmpty()
                val cls = simpleClassName(node.className?.toString().orEmpty())
                out += buildString {
                    append("[role=").append(roleFor(node))
                    append(", class=").append(cls)
                    append(", text=").append(quote(text))
                    append(", desc=").append(quote(desc))
                    append(", id=").append(quote(id))
                    append(", checked=").append(node.isChecked)
                    append(", enabled=").append(node.isEnabled)
                    append(", bounds=").append(b.left).append(',').append(b.top)
                        .append('-').append(b.right).append(',').append(b.bottom).append(']')
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return "PACKAGE=${root.packageName?.toString() ?: lastPackageName ?: "unknown"}\nINTERACTIVE_ELEMENTS=${out.size}\n" + out.joinToString("\n")
    }

    fun longClickText(target: String): Boolean = withRoot { root ->
        findBestNode(root, target, MatchMode.TEXT)?.let { node ->
            if (!node.isEnabled) return@withRoot false
            if (node.isLongClickable) return@withRoot node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (b.isEmpty) false else tap(b.centerX().toFloat(), b.centerY().toFloat())
        } ?: false
    }

    fun clearFocusedText(): Boolean = withRoot { root ->
        val node = findFocusedEditable(root) ?: findFirstEditable(root) ?: return@withRoot false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun roleFor(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString().orEmpty().substringAfterLast('.')
        return when {
            node.isEditable -> "text-field"
            node.isCheckable -> if (cls.contains("Radio", true)) "radio" else "checkbox"
            node.isScrollable -> "scroll-container"
            cls.contains("Button", true) -> "button"
            cls.contains("Image", true) -> "image"
            cls.contains("TextView", true) -> "text"
            else -> "interactive"
        }
    }

    fun getCurrentPackage(): String? = rootInActiveWindow?.packageName?.toString() ?: lastPackageName

    private enum class MatchMode { TEXT, DESCRIPTION }

    private fun findBestNode(
        root: AccessibilityNodeInfo,
        target: String,
        mode: MatchMode
    ): AccessibilityNodeInfo? {
        val wanted = normalize(target)
        if (wanted.isBlank()) return null

        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        fun visit(node: AccessibilityNodeInfo) {
            val value = when (mode) {
                MatchMode.TEXT -> node.text?.toString().orEmpty()
                MatchMode.DESCRIPTION -> node.contentDescription?.toString().orEmpty()
            }
            if (value.isNotBlank()) {
                val normalized = normalize(value)
                val score = when {
                    normalized == wanted -> 100
                    normalized.contains(wanted) -> 80
                    wanted.contains(normalized) && normalized.length >= 3 -> 65
                    else -> 0
                }
                if (score > bestScore && score > 0) {
                    bestScore = score
                    best = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }

        visit(root)
        return best
    }

    private fun findByViewId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        if (id.isBlank()) return null
        if (root.viewIdResourceName == id) return root
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findByViewId(child, id)?.let { return it }
            }
        }
        return null
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable && root.isFocused) return root
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findFocusedEditable(child)?.let { return it }
            }
        }
        return null
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable && root.isEnabled) return root
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findFirstEditable(child)?.let { return it }
            }
        }
        return null
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable && root.isEnabled) return root
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findScrollable(child)?.let { return it }
            }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        var parent = node.parent
        while (parent != null) {
            if (parent.isEnabled && parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) return tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        return false
    }

    private fun withRoot(block: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        return block(root)
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ").trim()

    private fun clean(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()

    private fun quote(value: String): String =
        if (value.isBlank()) "\"\"" else "\"${value.replace("\"", "'")}\""

    private fun simpleClassName(value: String): String =
        value.substringAfterLast('.')
}
