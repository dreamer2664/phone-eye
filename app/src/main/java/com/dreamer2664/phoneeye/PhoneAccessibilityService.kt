package com.dreamer2664.phoneeye

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The eyes and the hand — everything pc-eye does with ImageGrab + SetCursorPos,
 * Android only allows through here (short of root, which phone-eye never asks for):
 *
 *  - eyes:  takeScreenshot() — full physical pixels, one display;
 *  - hand:  dispatchGesture() — tap/swipe with an honest delivered/cancelled verdict;
 *  - words: the window tree, for /ui and tap_text (find it by name, not by guessing);
 *  - keys:  global actions (back/home/recents...) — the only "keyboard" Android offers.
 *
 * The human enables this in Settings; no app can grant it to itself, and that is a
 * feature. If this service is off, /shot answers 503 and pointing /act answers 409,
 * both with the sentence that fixes it. phone-eye never fakes a frame or a tap.
 */
class PhoneAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PhoneAccessibilityService? = null
            private set

        fun get(): PhoneAccessibilityService? = instance

        const val ENABLE_HINT =
            "the accessibility service is off — the human must enable it: phone-eye app → " +
            "\"Open Accessibility settings\" → Installed apps → phone-eye hand → ON (then confirm Allow). " +
            "Until then phone-eye can report but cannot see or touch the screen."
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        EyeLog.log("accessibility: connected (eyes + hand available)")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        try { EyeLog.log("accessibility: disconnected (eyes + hand gone)") } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* polling tool; events unused */ }
    override fun onInterrupt() { /* nothing to interrupt: gestures are fire-and-report */ }

    // ----------------------------------------------------------------------------------
    // Eyes
    // ----------------------------------------------------------------------------------

    data class ShotData(
        val bytes: ByteArray, val fullW: Int, val fullH: Int,
        val sentW: Int, val sentH: Int, val mime: String, val hash: String
    )

    /**
     * One screenshot, honouring /shot's contract: full size + sent size both reported
     * (the agent converts with real = seen * full / sent), plus a content hash for 304s.
     *
     * Android throttles takeScreenshot (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT).
     * That surfaces as 429 with instructions — the phone equivalent of pc-eye's
     * "use ?changed= while you wait" lecture.
     */
    @Synchronized
    fun captureShot(maxWidth: Int, quality: Int, png: Boolean): ShotData {
        val latch = CountDownLatch(1)
        val bitmapRef = AtomicReference<Bitmap?>(null)
        val failureRef = AtomicReference<Refusal?>(null)

        takeScreenshot(
            Display.DEFAULT_DISPLAY, mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hw = result.hardwareBuffer
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        if (wrapped == null) {
                            failureRef.set(Refusal(503, "Android returned a screenshot buffer this phone could not decode"))
                        } else {
                            // Copy off the hardware buffer here (main thread); compression
                            // happens on the request thread after the latch.
                            bitmapRef.set(wrapped.copy(Bitmap.Config.ARGB_8888, false))
                            wrapped.recycle()
                        }
                    } catch (e: Exception) {
                        failureRef.set(Refusal(503, "screenshot decode failed: ${e.javaClass.simpleName}: ${e.message}"))
                    } finally {
                        try { hw.close() } catch (e: Exception) { }
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    failureRef.set(when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> Refusal(
                            429,
                            "Android throttles accessibility screenshots and this one came too soon after the last. " +
                            "Slow the loop down; while waiting for the screen to change, poll /shot?changed=HASH."
                        )
                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> Refusal(
                            409,
                            "Android refused the screenshot capability (NO_ACCESSIBILITY_ACCESS). " +
                            "Ask the human to toggle phone-eye's accessibility service OFF and ON again."
                        )
                        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> Refusal(
                            503, "Android reported an invalid display for screenshots (is the screen fully off?)"
                        )
                        else -> Refusal(
                            503,
                            "screenshot failed with Android error $errorCode — common causes: screen off, " +
                            "or a FLAG_SECURE screen (banking/password apps draw black/refuse capture by design)."
                        )
                    })
                    latch.countDown()
                }
            }
        )

        if (!latch.await(8, TimeUnit.SECONDS)) {
            throw Refusal(503, "screenshot timed out after 8s — the phone may be asleep or the screenshot service is stuck")
        }
        failureRef.get()?.let { throw it }
        val full = bitmapRef.get() ?: throw Refusal(503, "screenshot produced no image and no error — treat the phone as asleep and tell the human")

        try {
            val fullW = full.width
            val fullH = full.height
            var sent = full
            var scaled = false
            if (maxWidth > 0 && fullW > maxWidth) {
                val targetH = (fullH.toDouble() * maxWidth.toDouble() / fullW.toDouble()).toInt().coerceAtLeast(1)
                sent = Bitmap.createScaledBitmap(full, maxWidth, targetH, true)
                scaled = true
            }
            val out = ByteArrayOutputStream()
            val mime: String
            if (png) {
                sent.compress(Bitmap.CompressFormat.PNG, 100, out)
                mime = "image/png"
            } else {
                sent.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 95), out)
                mime = "image/jpeg"
            }
            val bytes = out.toByteArray()
            val data = ShotData(bytes, fullW, fullH, sent.width, sent.height, mime, Core.contentHash(bytes))
            if (scaled && sent !== full) sent.recycle()
            return data
        } finally {
            try { full.recycle() } catch (e: Exception) { }
        }
    }

    // ----------------------------------------------------------------------------------
    // Hand
    // ----------------------------------------------------------------------------------

    /**
     * Dispatch one stroke and report Android's own verdict. onCancelled (a finger on
     * the glass, another gesture racing) is NOT success and is never dressed up as ok.
     */
    private fun dispatchStroke(path: Path, durationMs: Long): Boolean {
        val latch = CountDownLatch(1)
        val deliveredRef = AtomicBoolean(false)
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 60_000))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { deliveredRef.set(true); latch.countDown() }
                override fun onCancelled(g: GestureDescription?) { deliveredRef.set(false); latch.countDown() }
            },
            null
        )
        if (!accepted) return false
        latch.await(durationMs + 5_000, TimeUnit.MILLISECONDS)
        return deliveredRef.get()
    }

    fun tap(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat(), y.toFloat()) }
        return dispatchStroke(path, durationMs)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return dispatchStroke(path, durationMs)
    }

    fun global(action: Int): Boolean = try {
        performGlobalAction(action)
    } catch (e: Exception) {
        false
    }

    /** The currently focused editable field, if any. Typing needs a target like a click does. */
    fun focusedEditable(): AccessibilityNodeInfo? {
        try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        } catch (e: Exception) { }
        val root = rootInActiveWindow ?: return null
        // Fallback: walk the tree for the focused editable (some keyboards/apps differ).
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 1500) {
            val n = stack.removeLast()
            visited++
            if (n.isEditable && n.isFocused) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    /** ACTION_SET_TEXT is the unicode-honest way to type: no key events to drop. */
    fun setFocusedText(text: String, append: Boolean): Boolean {
        val node = focusedEditable() ?: return false
        val current = node.text?.toString() ?: ""
        val newText = if (append) current + text else text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        return try {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id, args)
        } catch (e: Exception) {
            false
        }
    }

    fun pasteFocused(): Boolean {
        val node = focusedEditable() ?: return false
        return try {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE.id)
        } catch (e: Exception) {
            false
        }
    }

    data class TapTextResult(val matched: String, val cx: Int, val cy: Int, val via: String)

    /**
     * Tap by words, not pixels: find a visible node whose text/description matches,
     * click it (or its clickable parent), else tap its centre on the glass.
     * Far safer for an agent than aiming at a downscaled screenshot when it works.
     */
    fun tapText(query: String, index: Int): TapTextResult {
        val root = rootInActiveWindow
            ?: throw Refusal(503, "no active window is visible to the accessibility service (screen off or still locked?)")
        val found = mutableListOf<AccessibilityNodeInfo>()
        try {
            root.findAccessibilityNodeInfosByText(query)?.let { found.addAll(it) }
        } catch (e: Exception) { }
        val visible = found.filter { it.isVisibleToUser }
        val node = visible.getOrNull(index)
            ?: throw Refusal(
                404,
                "no visible element (index $index) matching text \"$query\" among ${visible.size} visible match(es). " +
                "Take a /shot or read /ui and choose again — do not tap a guessed pixel as a substitute for looking."
            )
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: query)
        // Prefer a real ACTION_CLICK on the node or a clickable ancestor: it cannot miss.
        var cursor: AccessibilityNodeInfo? = node
        var hops = 0
        while (cursor != null && hops < 6) {
            if (cursor.isClickable) {
                val ok = try {
                    cursor.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id)
                } catch (e: Exception) { false }
                if (ok) return TapTextResult(label, cx, cy, if (hops == 0) "action_click" else "parent_action_click")
                break
            }
            cursor = cursor.parent
            hops++
        }
        if (!tap(cx, cy, 60)) {
            throw Refusal(409, "found \"$label\" at ($cx,$cy) but the tap gesture was cancelled by Android — look again and retry once")
        }
        return TapTextResult(label, cx, cy, "gesture_tap_centre")
    }

    fun scrollNode(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        var best: AccessibilityNodeInfo? = null
        var visited = 0
        while (stack.isNotEmpty() && visited < 1500) {
            val (n, d) = stack.removeLast()
            visited++
            if (n.isScrollable && n.isVisibleToUser) { best = n; break }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it to d + 1) }
        }
        val target = best ?: return false
        val action = if (forward) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id
                     else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
        return try { target.performAction(action) } catch (e: Exception) { false }
    }

    // ----------------------------------------------------------------------------------
    // Words (/ui)
    // ----------------------------------------------------------------------------------

    data class UiNodeData(
        val depth: Int, val text: String, val desc: String, val className: String,
        val viewId: String, val bounds: IntArray, val clickable: Boolean,
        val editable: Boolean, val scrollable: Boolean, val focused: Boolean,
        val enabled: Boolean, val visible: Boolean
    )

    data class UiDump(val pkg: String, val nodes: List<UiNodeData>, val truncated: Boolean)

    fun dumpUi(maxNodes: Int): UiDump {
        val root = rootInActiveWindow
            ?: throw Refusal(503, "no active window is visible to the accessibility service (screen off or still locked?)")
        val out = mutableListOf<UiNodeData>()
        var truncated = false
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        while (stack.isNotEmpty()) {
            if (out.size >= maxNodes) { truncated = true; break }
            val (n, depth) = stack.removeLast()
            val rect = Rect()
            try { n.getBoundsInScreen(rect) } catch (e: Exception) { }
            out.add(
                UiNodeData(
                    depth = depth,
                    text = n.text?.toString() ?: "",
                    desc = n.contentDescription?.toString() ?: "",
                    className = n.className?.toString() ?: "",
                    viewId = n.viewIdResourceName ?: "",
                    bounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
                    clickable = n.isClickable, editable = n.isEditable, scrollable = n.isScrollable,
                    focused = n.isFocused, enabled = n.isEnabled, visible = n.isVisibleToUser
                )
            )
            // Push children reversed so traversal reads top-to-bottom like the screen.
            val kids = mutableListOf<AccessibilityNodeInfo>()
            for (i in 0 until n.childCount) n.getChild(i)?.let { kids.add(it) }
            for (k in kids.asReversed()) stack.add(k to depth + 1)
        }
        return UiDump(root.packageName?.toString() ?: "", out, truncated)
    }

    fun foregroundPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (e: Exception) {
        null
    }
}
