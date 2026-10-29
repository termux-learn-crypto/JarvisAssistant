package com.vilas.jarvis

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Screen control (dusre apps mein type, click, scroll, back, home).
 * User ko Settings > Accessibility > Maira on karna hota hai.
 */
class MairaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private var lastSkipCheck = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> captureNotification(e, pkg)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Keyboard / system UI ko foreground app mat samjho
                if (pkg != "com.android.systemui" && !pkg.contains("inputmethod") && !pkg.contains("keyboard")) fgPkg = pkg
                if (fgPkg == YT) startPolling()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (pkg == YT) { fgPkg = YT; startPolling(); maybeSkipAd() }
        }
    }

    private fun captureNotification(e: AccessibilityEvent, pkg: String) {
        if (pkg == packageName || pkg == "android" || Safety.isBlocked(pkg)) return
        val n = e.parcelableData as? android.app.Notification
        val title = n?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
        val body = n?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
            ?: e.text.joinToString(" ")
        if (title.isBlank() && body.isBlank()) return
        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        if (Safety.isBlocked(appLabel)) return
        val time = java.text.SimpleDateFormat("HH:mm", Locale.ENGLISH).format(java.util.Date())
        val line = Safety.maskOtp("[$time] $appLabel - $title: $body").take(400)
        synchronized(notes) {
            if (notes.firstOrNull() == line) return
            notes.add(0, line)
            while (notes.size > 40) notes.removeAt(notes.size - 1)
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var fgPkg = ""
    private var polling = false
    private var lastTap = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (fgPkg != YT) { polling = false; return }
            try { maybeSkipAd() } catch (_: Exception) {}
            handler.postDelayed(this, 800)
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(poll)
    }

    private fun skipLabel(raw: String): Boolean {
        val l = raw.lowercase(Locale.ROOT).trim()
        if (l.isEmpty() || l.any { it.isDigit() }) return false   // "Skip in 5" abhi skip nahi hota
        return l == "skip" || l.startsWith("skip ad") || l.startsWith("skip ads") ||
            l.contains("स्किप") || l.contains("छोड़ें") || l.contains("वगळा")
    }

    private fun maybeSkipAd() {
        if (!getSharedPreferences("jarvis", MODE_PRIVATE).getBoolean("autoskip", true)) return
        val now = System.currentTimeMillis()
        if (now - lastSkipCheck < 500) return
        lastSkipCheck = now
        val r = root() ?: return
        if (r.packageName?.toString() != YT) return
        val byId = listOf("skip_ad_button", "modern_skip_ad_text", "skip_ad_button_text")
            .flatMap { r.findAccessibilityNodeInfosByViewId("$YT:id/$it") }
            .filter { it.isVisibleToUser && !(it.text?.toString() ?: "").any { c -> c.isDigit() } }
        val target = byId.firstOrNull()
            ?: allNodes().firstOrNull { it.isVisibleToUser && (skipLabel(it.text?.toString() ?: "") || skipLabel(it.contentDescription?.toString() ?: "")) }
            ?: return
        if (now - lastTap < 1500) return
        lastTap = now
        tapNode(target)
    }

    /** Asli ungli jaisa tap (naye YouTube ke button ACTION_CLICK nahi lete). */
    private fun tapNode(n: AccessibilityNodeInfo) {
        val rect = android.graphics.Rect()
        n.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            val path = android.graphics.Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
            val g = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            if (dispatchGesture(g, null, null)) return
        }
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        (c ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Screen pe dikh raha text (password chhod ke), upar se neeche. */
    fun screenText(): String {
        val seen = LinkedHashSet<String>()
        for (n in allNodes()) {
            if (!n.isVisibleToUser || n.isPassword) continue
            val t = label(n).replace(Regex("\\s+"), " ").trim()
            if (t.length >= 2) seen.add(t)
        }
        return Safety.maskOtp(seen.joinToString("\n")).take(5000)
    }
    override fun onInterrupt() {}

    private fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    fun currentPackage(): String = root()?.packageName?.toString() ?: ""

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val r = root() ?: return out
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(r)
        while (stack.isNotEmpty() && out.size < 3000) {
            val n = stack.removeLast()
            out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return out
    }

    private fun label(n: AccessibilityNodeInfo): String =
        ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "") +
            (if (Build.VERSION.SDK_INT >= 26) " " + (n.hintText?.toString() ?: "") else "")).trim()

    fun click(target: String): String {
        val t = target.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return "Kya dabana hai, wo naam nahi mila."
        val nodes = allNodes().filter { it.isVisibleToUser }
        val match = nodes.firstOrNull { label(it).lowercase(Locale.ROOT) == t }
            ?: nodes.firstOrNull { label(it).lowercase(Locale.ROOT).contains(t) }
            ?: return "Screen pe \"$target\" nahi dikha."
        var n: AccessibilityNodeInfo? = match
        while (n != null && !n.isClickable) n = n.parent
        val ok = (n ?: match).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (ok) "\"$target\" daba diya." else "\"$target\" dab nahi paya."
    }

    fun typeText(text: String): String {
        val nodes = allNodes()
        val field = nodes.firstOrNull { it.isFocused && it.isEditable }
            ?: nodes.firstOrNull { it.isEditable && it.isVisibleToUser }
            ?: return "Likhne ki jagah (text box) nahi mili. Pehle search ya message box pe click karwao."
        if (field.isPassword) return "Password field mein main nahi likhti."
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) "Likh diya: $text" else "Type nahi ho paya."
    }

    fun pressEnter(): String {
        if (Build.VERSION.SDK_INT < 30) return "Enter dabana Android 11+ pe hi chalta hai."
        val field = allNodes().firstOrNull { it.isFocused && it.isEditable }
            ?: allNodes().firstOrNull { it.isEditable && it.isVisibleToUser }
            ?: return "Enter ke liye text box nahi mila."
        val ok = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return if (ok) "Enter daba diya." else "Enter nahi dab paya."
    }

    fun scroll(down: Boolean): String {
        val nodes = allNodes().filter { it.isScrollable && it.isVisibleToUser }
        val target = nodes.maxByOrNull {
            val r = android.graphics.Rect(); it.getBoundsInScreen(r); r.width() * r.height()
        } ?: return "Yahan scroll karne layak kuch nahi mila."
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (target.performAction(action)) (if (down) "Neeche scroll kiya." else "Upar scroll kiya.")
        else "Aur scroll nahi ho sakta."
    }

    fun global(action: Int, done: String): String =
        if (performGlobalAction(action)) done else "Ye nahi ho paya."

    companion object {
        @Volatile var instance: MairaAccessibilityService? = null
        private const val YT = "com.google.android.youtube"
        private val notes = mutableListOf<String>()

        fun recentNotifications(app: String): List<String> = synchronized(notes) {
            val a = app.trim().lowercase(Locale.ROOT)
            notes.filter { a.isEmpty() || it.lowercase(Locale.ROOT).contains(a) }.take(15)
        }
    }
}
