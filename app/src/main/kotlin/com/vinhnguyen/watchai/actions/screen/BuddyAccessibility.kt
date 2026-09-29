package com.vinhnguyen.watchai.actions.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Buddy's hands on the phone's screen, once the user switches it on in Android's accessibility
 * settings: it reads the app in front and taps, types and scrolls in it for the voice. It doesn't
 * watch in the background: it looks only when a tool asks ([ScreenActions]), and never at all in
 * the apps [com.vinhnguyen.watchai.actions.AppLimits] keeps it out of.
 */
class BuddyAccessibility : AccessibilityService() {
    override fun onServiceConnected() {
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /** The app in front and what's on its screen, or null when there's no window to read. */
    fun look(): Screen? {
        val root = rootInActiveWindow ?: return null
        val nodes = mutableListOf<ScreenNode>()
        val handles = mutableMapOf<Int, AccessibilityNodeInfo>()
        fun walk(
            node: AccessibilityNodeInfo,
            inList: Boolean,
            depth: Int,
        ) {
            if (depth > MAX_DEPTH || nodes.size >= MAX_NODES) return
            if (!node.isVisibleToUser) return
            val id = nodes.size + 1
            val kind = kindOf(node)
            val listHere = node.collectionInfo != null || kind == "list"
            val interesting = node.isClickable || node.isEditable || node.isScrollable || !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            if (interesting) {
                nodes +=
                    ScreenNode(
                        id = id,
                        kind = kind,
                        text = node.text?.toString().orEmpty().take(TEXT_MAX),
                        description = node.contentDescription?.toString().orEmpty().take(TEXT_MAX),
                        hint = node.hintText?.toString().orEmpty(),
                        viewId = node.viewIdResourceName.orEmpty(),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        password = node.isPassword,
                        inList = inList,
                    )
                handles[id] = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, inList || listHere, depth + 1) }
        }
        walk(root, inList = false, depth = 0)
        return Screen(root.packageName?.toString().orEmpty(), nodes, handles)
    }

    /**
     * Taps [node], or the nearest thing around it that can be tapped; a gesture on its middle if nothing can.
     * Inside a web page it's always the gesture: Chrome says yes to the accessibility click on a link and
     * then does nothing (29.09).
     */
    suspend fun tap(node: AccessibilityNodeInfo): Boolean {
        if (!inWebPage(node)) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        val box = Rect().also { node.getBoundsInScreen(it) }
        if (box.isEmpty) return false
        val path = Path().apply { moveTo(box.exactCenterX(), box.exactCenterY()) }
        val done = CompletableDeferred<Boolean>()
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS)).build()
        val started =
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        done.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        done.complete(false)
                    }
                },
                null,
            )
        return started && withTimeoutOrNull(GESTURE_TIMEOUT_MS) { done.await() } == true
    }

    fun type(
        node: AccessibilityNodeInfo,
        text: String,
    ): Boolean {
        if (node.isPassword) return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(
        node: AccessibilityNodeInfo?,
        down: Boolean,
    ): Boolean {
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return node?.performAction(action) == true
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    private fun inWebPage(node: AccessibilityNodeInfo): Boolean {
        var at: AccessibilityNodeInfo? = node
        while (at != null) {
            if (at.className?.toString()?.endsWith("WebView") == true) return true
            at = at.parent
        }
        return false
    }

    /** One look at the screen: [handles] are the live views behind the nodes' ids. */
    class Screen(
        val packageName: String,
        val nodes: List<ScreenNode>,
        val handles: Map<Int, AccessibilityNodeInfo>,
    )

    companion object {
        /** The running service, or null while the user hasn't switched it on. */
        @Volatile var current: BuddyAccessibility? = null
            private set

        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 400
        private const val TEXT_MAX = 500
        private const val TAP_MS = 60L
        private const val GESTURE_TIMEOUT_MS = 2_000L

        private fun kindOf(node: AccessibilityNodeInfo): String {
            val cls = node.className?.toString().orEmpty()
            return when {
                node.isEditable || cls.endsWith("EditText") -> "field"
                cls.endsWith("Switch") || cls.endsWith("CheckBox") || cls.endsWith("ToggleButton") -> "switch"
                cls.contains("Tab") -> "tab"
                cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("GridView") -> "list"
                cls.endsWith("Button") || cls.endsWith("ImageButton") -> "button"
                node.isClickable -> "button"
                else -> "text"
            }
        }
    }
}
