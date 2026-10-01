package com.devnamdev.iris

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AppAutomationService
 * Android AccessibilityService enabling Iris Assistant to execute hands-free UI automation:
 * - WhatsApp auto-messaging (search contact, select chat, set text, trigger send)
 * - Inspect screen node hierarchy
 * - Set text and programmatic click dispatches
 * - App launch and navigation controls
 */
class AppAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "IrisAutomation"
        var instance: AppAutomationService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null

        // Pending automation tasks queue
        private var pendingWhatsAppTask: WhatsAppTask? = null

        fun queueWhatsAppMessage(contactName: String?, phoneNumber: String?, message: String, callback: ((Boolean, String) -> Unit)? = null) {
            pendingWhatsAppTask = WhatsAppTask(contactName, phoneNumber, message, callback)
            Log.d(TAG, "Queued WhatsApp task: Contact=${contactName ?: phoneNumber}, Message='$message'")
        }
    }

    data class WhatsAppTask(
        val contactName: String?,
        val phoneNumber: String?,
        val message: String,
        val callback: ((Boolean, String) -> Unit)?,
        var stage: Stage = Stage.START,
        var attempts: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        enum class Stage {
            START,
            SEARCH_CONTACT,
            CLICK_CONTACT,
            INPUT_TEXT,
            CLICK_SEND,
            DONE
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AppAutomationService connected successfully.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "AppAutomationService destroyed.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        // Handle WhatsApp automation
        if (packageName.contains("whatsapp", ignoreCase = true)) {
            handleWhatsAppAutomation(event)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AppAutomationService interrupted.")
    }

    /**
     * Executes multi-step WhatsApp automation pipeline
     */
    private fun handleWhatsAppAutomation(event: AccessibilityEvent) {
        val task = pendingWhatsAppTask ?: return

        // Timeout task if older than 30 seconds
        if (System.currentTimeMillis() - task.createdAt > 30_000) {
            task.callback?.invoke(false, "WhatsApp automation timed out.")
            pendingWhatsAppTask = null
            return
        }

        val rootNode = rootInActiveWindow ?: return

        when (task.stage) {
            WhatsAppTask.Stage.START -> {
                // If direct phone chat opened, try typing directly
                if (typeMessageAndSend(rootNode, task.message)) {
                    task.stage = WhatsAppTask.Stage.CLICK_SEND
                    mainHandler.postDelayed({
                        findAndClickSendButton(rootInActiveWindow ?: rootNode, task)
                    }, 400)
                } else {
                    // Try to search contact if on main chat screen
                    if (!task.contactName.isNullOrBlank()) {
                        task.stage = WhatsAppTask.Stage.SEARCH_CONTACT
                        findAndClickSearchButton(rootNode, task)
                    }
                }
            }

            WhatsAppTask.Stage.SEARCH_CONTACT -> {
                val searchInput = findNodeByTextOrHint(rootNode, listOf("Search", "Search…", "search_src_text", "Search contacts"))
                if (searchInput != null && !task.contactName.isNullOrBlank()) {
                    setNodeText(searchInput, task.contactName)
                    task.stage = WhatsAppTask.Stage.CLICK_CONTACT
                    mainHandler.postDelayed({
                        val updatedRoot = rootInActiveWindow ?: rootNode
                        findAndClickContactResult(updatedRoot, task.contactName)
                    }, 800)
                }
            }

            WhatsAppTask.Stage.CLICK_CONTACT -> {
                if (!task.contactName.isNullOrBlank()) {
                    findAndClickContactResult(rootNode, task.contactName)
                }
            }

            WhatsAppTask.Stage.INPUT_TEXT -> {
                if (typeMessageAndSend(rootNode, task.message)) {
                    task.stage = WhatsAppTask.Stage.CLICK_SEND
                    mainHandler.postDelayed({
                        findAndClickSendButton(rootInActiveWindow ?: rootNode, task)
                    }, 400)
                }
            }

            WhatsAppTask.Stage.CLICK_SEND -> {
                findAndClickSendButton(rootNode, task)
            }

            WhatsAppTask.Stage.DONE -> {
                // Completed
            }
        }
    }

    private fun findAndClickSearchButton(root: AccessibilityNodeInfo, task: WhatsAppTask): Boolean {
        val searchIcons = findNodesByViewId(root, listOf(
            "com.whatsapp:id/menuitem_search",
            "com.whatsapp:id/search_holder",
            "com.whatsapp:id/search_icon"
        )) + findNodesByContentDescription(root, listOf("Search", "Search contacts", "खोजें"))

        for (node in searchIcons) {
            if (performClick(node)) {
                return true
            }
        }
        return false
    }

    private fun findAndClickContactResult(root: AccessibilityNodeInfo, contactName: String): Boolean {
        val candidates = root.findAccessibilityNodeInfosByText(contactName)
        for (node in candidates) {
            if (node.text?.toString()?.contains(contactName, ignoreCase = true) == true) {
                if (performClick(node)) {
                    pendingWhatsAppTask?.stage = WhatsAppTask.Stage.INPUT_TEXT
                    return true
                }
            }
        }
        return false
    }

    private fun typeMessageAndSend(root: AccessibilityNodeInfo, message: String): Boolean {
        val inputNodes = findNodesByViewId(root, listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp:id/message_entry",
            "com.whatsapp:id/input_message"
        )) + findNodesByTextOrHint(root, listOf("Type a message", "Message", "संदेश लिखें"))

        for (inputNode in inputNodes) {
            if (inputNode.isEditable || inputNode.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
                if (setNodeText(inputNode, message)) {
                    return true
                }
            }
        }
        return false
    }

    private fun findAndClickSendButton(root: AccessibilityNodeInfo, task: WhatsAppTask): Boolean {
        val sendButtons = findNodesByViewId(root, listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_button",
            "com.whatsapp:id/voice_note_btn"
        )) + findNodesByContentDescription(root, listOf("Send", "भेजें"))

        for (button in sendButtons) {
            if (performClick(button)) {
                task.stage = WhatsAppTask.Stage.DONE
                task.callback?.invoke(true, "WhatsApp message sent successfully to ${task.contactName ?: task.phoneNumber}")
                pendingWhatsAppTask = null
                return true
            }
        }
        return false
    }

    /**
     * Programmatically sets text in an AccessibilityNodeInfo
     */
    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (!success) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        return success
    }

    /**
     * Performs click on node or its closest clickable parent
     */
    fun performClick(node: AccessibilityNodeInfo?): Boolean {
        var current = node ?: return false
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent ?: break
        }
        return false
    }

    /**
     * Dispatches tap gesture at specific coordinates
     */
    fun tapAt(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    // Helper finders
    fun findNodesByViewId(root: AccessibilityNodeInfo, ids: List<String>): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        for (id in ids) {
            results.addAll(root.findAccessibilityNodeInfosByViewId(id))
        }
        return results
    }

    fun findNodesByContentDescription(root: AccessibilityNodeInfo, descriptions: List<String>): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val desc = node.contentDescription?.toString()
            if (desc != null && descriptions.any { desc.contains(it, ignoreCase = true) }) {
                results.add(node)
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }
        traverse(root)
        return results
    }

    fun findNodesByTextOrHint(root: AccessibilityNodeInfo, keywords: List<String>): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = node.text?.toString() ?: ""
            val hint = node.hintText?.toString() ?: ""
            if (keywords.any { text.contains(it, ignoreCase = true) || hint.contains(it, ignoreCase = true) }) {
                results.add(node)
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }
        traverse(root)
        return results
    }

    private fun findNodeByTextOrHint(root: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val list = findNodesByTextOrHint(root, keywords)
        return list.firstOrNull()
    }
}
