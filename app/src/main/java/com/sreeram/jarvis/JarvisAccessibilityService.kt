package com.sreeram.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JarvisAccessibilityService : AccessibilityService() {
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    fun snapshot(): String {
        // Buddy's own screen would otherwise hide the app being operated.
        if (rootInActiveWindow?.packageName == packageName) { Session.moveToBack?.invoke(); Thread.sleep(1000) }
        val root = rootInActiveWindow ?: return "No screen content available."
        val list = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder("App: ${root.packageName}\n")
        val chat = SendGuard.isMessagingApp(root.packageName?.toString())
        val dm = resources.displayMetrics
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || list.size >= 150 || !n.isVisibleToUser) return
            val text = if (n.isPassword) "(password field)" else n.text?.toString()
            val desc = n.contentDescription?.toString()
            if (n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || !text.isNullOrBlank() || !desc.isNullOrBlank()) {
                sb.append("[${list.size}] ${n.className?.toString()?.substringAfterLast('.')}")
                if (!text.isNullOrBlank()) sb.append(" \"${text.take(80)}\"")
                if (chat && !text.isNullOrBlank() && !n.isEditable && n.viewIdResourceName?.endsWith(":id/message_text") == true) sb.append(sideTag(n, dm.widthPixels, dm.heightPixels, dm.density))
                if (!desc.isNullOrBlank() && desc != text) sb.append(" desc=\"${desc.take(60)}\"")
                if (n.isClickable) sb.append(" clickable")
                if (n.isEditable) sb.append(" editable")
                if (n.isScrollable) sb.append(" scrollable")
                if (n.isChecked) sb.append(" checked")
                sb.append('\n')
                list.add(n)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        nodes = list
        return sb.toString().take(4000)
    }

    private fun sideTag(n: AccessibilityNodeInfo, width: Int, height: Int, density: Float): String {
        val r = Rect().also { n.getBoundsInScreen(it) }
        if (r.top < height * 0.12f || r.bottom > height * 0.93f) return "" // toolbar and typing area are not messages
        return when (com.sreeram.jarvis.msg.ChatParser.side(r.left, r.right, width, density)) {
            com.sreeram.jarvis.msg.Side.YOU -> " [YOU SENT THIS]"
            com.sreeram.jarvis.msg.Side.THEM -> " [THEY SENT THIS]"
            null -> ""
        }
    }

    // A copy of the visible screen as plain data (see msg/ScreenNode), for the logic that must be testable without a phone.
    fun screenNodes(): List<com.sreeram.jarvis.msg.ScreenNode> {
        val out = ArrayList<com.sreeram.jarvis.msg.ScreenNode>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || out.size > 600) return
            if (n.isVisibleToUser) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                out.add(com.sreeram.jarvis.msg.ScreenNode(n.viewIdResourceName?.substringAfter(":id/").orEmpty(), n.text?.toString(), n.contentDescription?.toString(),
                    r.left, r.top, r.right, r.bottom, n.isEditable, n.isClickable))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return out
    }

    fun chatScreen(): com.sreeram.jarvis.msg.ChatScreen {
        val dm = resources.displayMetrics
        return com.sreeram.jarvis.msg.ChatParser.parse(screenNodes(), dm.widthPixels, dm.heightPixels, dm.density)
    }

    // Presses a button by its resource id, which (unlike its label) does not change with the phone's language.
    fun pressById(fullId: String): Boolean {
        var t: AccessibilityNodeInfo? = rootInActiveWindow?.findAccessibilityNodeInfosByViewId(fullId)?.firstOrNull { it.isVisibleToUser }
        while (t != null && !t.isClickable) t = t.parent
        return t?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    // The delivery status of the newest message the user sent on screen; null when no tick can be read.
    fun newestOutgoingStatus(): String? =
        chatScreen().messages.lastOrNull { it.side == com.sreeram.jarvis.msg.Side.YOU }?.delivery?.name?.lowercase()

    // Grab the foreground app's text and a screenshot, for "what's on my screen" when Android's own assist capture is unavailable.
    fun captureContext() {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length > 4000 || !n.isVisibleToUser) return
            if (!n.isPassword) (n.text ?: n.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        ScreenContext.text = sb.toString()
        ScreenContext.screenshot = null
        ScreenContext.time = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT < 30) return
        val latch = CountDownLatch(1)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                ScreenContext.screenshot = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                latch.countDown()
            }
            override fun onFailure(errorCode: Int) { latch.countDown() }
        })
        latch.await(2, TimeUnit.SECONDS)
    }

    // Click a visible control by its label or description (used for WhatsApp's Send button).
    fun clickLabeled(label: String): Boolean {
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isVisibleToUser && (n.contentDescription?.toString().equals(label, true) || n.text?.toString().equals(label, true))) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        var target: AccessibilityNodeInfo? = find(rootInActiveWindow)
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    // Types text into the field the user is working in. If Buddy's own screen is in front, it steps aside first.
    fun dictate(text: String): String {
        if (rootInActiveWindow?.packageName == packageName) { Session.moveToBack?.invoke(); Thread.sleep(1000) }
        val root = rootInActiveWindow ?: return "I can't see the screen."
        fun firstEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isEditable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) firstEditable(n.getChild(i))?.let { return it }
            return null
        }
        val target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: firstEditable(root)?.also { it.performAction(AccessibilityNodeInfo.ACTION_CLICK); Thread.sleep(400) }
            ?: return "I couldn't find a text box here. Tap the box you want me to type in, then ask again."
        val existing = if (target.isShowingHintText) "" else target.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, if (existing.isBlank()) text else "$existing $text")
        }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed it" else "That box wouldn't accept text."
    }

    // In a music/video app's search results, tap the play button for the query or else the first result whose title matches it.
    fun tapFirstResult(pkg: String, query: String): String? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != pkg) return null
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
        if (tokens.isEmpty()) return null
        var playButton: AccessibilityNodeInfo? = null
        var title: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || !n.isVisibleToUser) return
            if (!n.isEditable) {
                val desc = n.contentDescription?.toString().orEmpty()
                val text = n.text?.toString().orEmpty()
                if (playButton == null && n.isClickable && desc.startsWith("Play ", true) && tokens.any { desc.lowercase().contains(it) }) playButton = n
                // Some apps (YouTube) put a result's title in the description of a clickable button, not in its text.
                val label = text.ifBlank { desc }
                if (title == null && label.isNotBlank() && label.length < 300 && !label.equals(query, true) && label.lowercase().contains(tokens[0]) &&
                    !desc.startsWith("Go to channel", true) && (text.isNotBlank() || n.isClickable)) title = n
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        val target = playButton ?: title ?: return null
        val label = (target.contentDescription ?: target.text)?.toString().orEmpty()
        var t: AccessibilityNodeInfo? = target
        while (t != null && !t.isClickable) t = t.parent
        return if (t != null && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) label else null
    }

    // A small JPEG of the screen right now, or null if Android won't take one (a secure screen, or too soon after the last).
    fun screenshotJpeg(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        var out: ByteArray? = null
        val latch = CountDownLatch(1)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)?.let { out = Vision.encode(it) }
                result.hardwareBuffer.close(); latch.countDown()
            }
            override fun onFailure(errorCode: Int) { latch.countDown() }
        })
        latch.await(2, TimeUnit.SECONDS)
        return out
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    private fun labelOfNode(n: AccessibilityNodeInfo?): String {
        var t = n
        // A button's label is often on the button or on a child/parent; look at the node and its nearest clickable parent.
        repeat(3) { if (t != null) { val l = (t!!.text ?: t!!.contentDescription)?.toString().orEmpty(); if (l.isNotBlank()) return l; t = t!!.parent } }
        return n?.let { x -> (0 until x.childCount).mapNotNull { x.getChild(it) }.map { c -> (c.text ?: c.contentDescription)?.toString().orEmpty() }.firstOrNull { it.isNotBlank() } }.orEmpty()
    }

    fun labelOf(index: Int): String = labelOfNode(nodes.getOrNull(index))

    // The label of the smallest clickable element under a screen point, so a raw tap on "Send" is caught too.
    fun labelAt(x: Int, y: Int): String {
        var best: AccessibilityNodeInfo? = null; var bestArea = Long.MAX_VALUE
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || !n.isVisibleToUser) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.contains(x, y) && n.isClickable) { val area = r.width().toLong() * r.height(); if (area < bestArea) { best = n; bestArea = area } }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return labelOfNode(best)
    }

    fun tap(index: Int): String {
        val n = nodes.getOrNull(index) ?: return "No element [$index]. Call read_screen again."
        n.refresh()
        var target: AccessibilityNodeInfo? = n
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped [$index]"
        val r = Rect().also { n.getBoundsInScreen(it) }
        return tapXY(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun tapXY(x: Float, y: Float): String =
        if (gesture(Path().apply { moveTo(x, y) }, 60)) "Tapped at $x,$y" else "Tap failed"

    fun typeText(text: String, index: Int?): String {
        val target = (index?.let { nodes.getOrNull(it) })
            ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: nodes.firstOrNull { it.isEditable }
            ?: return "No text field found."
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed text" else "Could not type into that field."
    }

    fun scroll(direction: String): String {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val path = Path()
        when (direction.lowercase()) {
            "up" -> { path.moveTo(w / 2, h * 0.3f); path.lineTo(w / 2, h * 0.7f) }
            "left" -> { path.moveTo(w * 0.2f, h / 2); path.lineTo(w * 0.8f, h / 2) }
            "right" -> { path.moveTo(w * 0.8f, h / 2); path.lineTo(w * 0.2f, h / 2) }
            else -> { path.moveTo(w / 2, h * 0.7f); path.lineTo(w / 2, h * 0.3f) }
        }
        return if (gesture(path, 350)) "Scrolled $direction" else "Scroll failed"
    }

    fun pressKey(key: String): String {
        val ok = when (key.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lock" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            "enter" -> Build.VERSION.SDK_INT >= 30 &&
                findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
            else -> return "Unknown key $key"
        }
        return if (ok) "Pressed $key" else "Could not press $key"
    }

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var done = false
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        return sent && latch.await(3, TimeUnit.SECONDS) && done
    }

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
    }
}
