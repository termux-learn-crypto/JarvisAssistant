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

    /** Maira ki apni window chhod ke, sabse upar wali app window. */
    private fun root(): AccessibilityNodeInfo? {
        try {
            for (w in windows) {
                if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() != packageName) return r
            }
        } catch (_: Exception) {}
        return rootInActiveWindow
    }

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

    // ======================= MOUSE / GESTURES / LABELS / KEYBOARD =======================

    private val wm by lazy { getSystemService(WINDOW_SERVICE) as android.view.WindowManager }
    private var cursorView: android.view.View? = null
    private var labelsView: android.widget.FrameLayout? = null
    private var labeled: List<AccessibilityNodeInfo> = emptyList()
    private var cx = -1f
    private var cy = -1f

    private fun dpx(v: Int) = v * resources.displayMetrics.density

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m); m.widthPixels to m.heightPixels
        }
    }

    /** Main thread pe chalao aur result ka wait karo (Brain background thread se bulata hai). */
    private fun <T> onMain(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val latch = java.util.concurrent.CountDownLatch(1)
        var res: Result<T>? = null
        handler.post { res = runCatching(block); latch.countDown() }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return res?.getOrThrow() ?: throw RuntimeException("timeout")
    }

    private fun overlayParams(w: Int, h: Int) = android.view.WindowManager.LayoutParams(
        w, h,
        android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }

    private fun guard(): String? {
        val pkg = currentPackage()
        return if (Safety.isBlocked(pkg)) "Is app mein screen control safety ke liye band hai." else null
    }

    // ---------- Mouse ----------

    fun mouse(action: String, direction: String, amount: String, position: String): String {
        guard()?.let { return it }
        val (w, h) = screenSize()
        if (cx < 0) { cx = w / 2f; cy = h / 2f }
        return when (action) {
            "show" -> { onMain { showCursor() }; "Mouse dikha diya. Bolo upar, neeche, left, right, click." }
            "hide" -> { onMain { hideCursor() }; "Mouse hata diya." }
            "move" -> {
                val step = when (amount) { "small" -> 0.05f; "large" -> 0.25f; else -> 0.12f }
                when (direction) {
                    "up" -> cy -= h * step; "down" -> cy += h * step
                    "left" -> cx -= w * step; "right" -> cx += w * step
                }
                clampCursor(w, h); onMain { showCursor() }; "Mouse hila diya."
            }
            "goto" -> {
                cx = when { position.contains("left") -> w * 0.12f; position.contains("right") -> w * 0.88f; else -> w / 2f }
                cy = when { position.contains("top") -> h * 0.12f; position.contains("bottom") -> h * 0.85f; else -> h / 2f }
                onMain { showCursor() }; "Mouse $position pe."
            }
            "tap" -> if (tap(cx, cy, 60)) "Click kar diya." else "Click nahi hua."
            "double_tap" -> if (doubleTap(cx, cy)) "Double click kar diya." else "Double click nahi hua."
            "long_press" -> if (tap(cx, cy, 900)) "Der tak daba diya." else "Long press nahi hua."
            else -> "Mouse action samajh nahi aaya: $action"
        }
    }

    private fun clampCursor(w: Int, h: Int) {
        cx = cx.coerceIn(4f, w - 4f); cy = cy.coerceIn(4f, h - 4f)
    }

    private fun showCursor() {
        val size = dpx(34).toInt()
        val v = cursorView ?: object : android.view.View(this) {
            val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x664FC3F7 }
            val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1565C0.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = dpx(3)
            }
            val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF1744.toInt() }
            override fun onDraw(c: android.graphics.Canvas) {
                val r = width / 2f
                c.drawCircle(r, r, r - dpx(2), fill); c.drawCircle(r, r, r - dpx(2), ring); c.drawCircle(r, r, dpx(3), dot)
            }
        }.also { cursorView = it; wm.addView(it, overlayParams(size, size)) }
        val lp = v.layoutParams as android.view.WindowManager.LayoutParams
        lp.x = (cx - size / 2f).toInt(); lp.y = (cy - size / 2f).toInt()
        wm.updateViewLayout(v, lp)
    }

    private fun hideCursor() {
        cursorView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        cursorView = null
    }

    // ---------- Gestures ----------

    private fun stroke(path: android.graphics.Path, start: Long, dur: Long) =
        android.accessibilityservice.GestureDescription.StrokeDescription(path, start, dur)

    private fun dispatchSync(g: android.accessibilityservice.GestureDescription): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        val cb = object : GestureResultCallback() {
            override fun onCompleted(d: android.accessibilityservice.GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: android.accessibilityservice.GestureDescription?) { latch.countDown() }
        }
        val sent = onMain { dispatchGesture(g, cb, handler) }
        if (!sent) return false
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) latch.await(3, java.util.concurrent.TimeUnit.SECONDS) else ok = true
        return ok
    }

    fun tap(x: Float, y: Float, dur: Long): Boolean {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        return dispatchSync(android.accessibilityservice.GestureDescription.Builder().addStroke(stroke(path, 0, dur)).build())
    }

    private fun doubleTap(x: Float, y: Float): Boolean {
        val p1 = android.graphics.Path().apply { moveTo(x, y) }
        val p2 = android.graphics.Path().apply { moveTo(x, y) }
        return dispatchSync(android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke(p1, 0, 50)).addStroke(stroke(p2, 160, 50)).build())
    }

    fun gesture(action: String): String {
        guard()?.let { return it }
        val (w, h) = screenSize()
        val (x1, y1, x2, y2) = when (action) {
            "swipe_up" -> listOf(w / 2f, h * 0.75f, w / 2f, h * 0.25f)
            "swipe_down" -> listOf(w / 2f, h * 0.25f, w / 2f, h * 0.75f)
            "swipe_left" -> listOf(w * 0.85f, h / 2f, w * 0.15f, h / 2f)
            "swipe_right" -> listOf(w * 0.15f, h / 2f, w * 0.85f, h / 2f)
            else -> return "Swipe samajh nahi aaya: $action"
        }
        val path = android.graphics.Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val ok = dispatchSync(android.accessibilityservice.GestureDescription.Builder().addStroke(stroke(path, 0, 350)).build())
        if (labelsView != null) handler.postDelayed({ try { renderLabels() } catch (_: Exception) {} }, 900)
        return if (ok) "Swipe kar diya." else "Swipe nahi hua."
    }

    // ---------- Number labels (har button pe number) ----------

    fun labels(action: String, number: Int): String {
        guard()?.let { return it }
        return when (action) {
            "show" -> { val n = onMain { renderLabels() }; if (n == 0) "Screen pe dabane layak kuch nahi mila." else "$n buttons pe number laga diye. Bolo, kaunsa number dabaun?" }
            "hide" -> { onMain { hideLabels() }; "Number hata diye." }
            "tap", "long_press" -> {
                if (labeled.isEmpty()) onMain { renderLabels() }
                val node = labeled.getOrNull(number - 1) ?: return "Number $number nahi mila."
                val r = android.graphics.Rect(); node.getBoundsInScreen(r)
                val ok = tap(r.exactCenterX(), r.exactCenterY(), if (action == "long_press") 900 else 60)
                handler.postDelayed({ try { if (labelsView != null) renderLabels() } catch (_: Exception) {} }, 1000)
                if (ok) "$number daba diya." else "$number dab nahi paya."
            }
            else -> "Label action samajh nahi aaya: $action"
        }
    }

    private fun renderLabels(): Int {
        hideLabels()
        val (w, h) = screenSize()
        val nodes = allNodes().filter {
            it.isVisibleToUser && (it.isClickable || it.isEditable || it.isLongClickable) &&
                it.packageName?.toString() != packageName
        }.distinctBy { val r = android.graphics.Rect(); it.getBoundsInScreen(r); r.toShortString() }
            .filter { val r = android.graphics.Rect(); it.getBoundsInScreen(r); r.width() > 8 && r.height() > 8 && r.top >= 0 && r.left < w && r.top < h }
            .take(60)
        labeled = nodes
        if (nodes.isEmpty()) return 0
        val frame = android.widget.FrameLayout(this)
        nodes.forEachIndexed { i, n ->
            val r = android.graphics.Rect(); n.getBoundsInScreen(r)
            val tv = android.widget.TextView(this).apply {
                text = (i + 1).toString()
                textSize = 12f
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dpx(4).toInt(), 0, dpx(4).toInt(), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xE6D500F9.toInt()); cornerRadius = dpx(6)
                }
            }
            frame.addView(tv, android.widget.FrameLayout.LayoutParams(-2, -2).apply { leftMargin = r.left; topMargin = r.top })
        }
        wm.addView(frame, overlayParams(w, h))
        labelsView = frame
        return nodes.size
    }

    private fun hideLabels() {
        labelsView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        labelsView = null
    }

    // ---------- Keyboard ----------

    private fun focusedField(): AccessibilityNodeInfo? {
        val nodes = allNodes()
        return nodes.firstOrNull { it.isFocused && it.isEditable } ?: nodes.firstOrNull { it.isEditable && it.isVisibleToUser }
    }

    private fun currentText(f: AccessibilityNodeInfo): String {
        val t = f.text?.toString() ?: ""
        val hint = if (Build.VERSION.SDK_INT >= 26) f.hintText?.toString() else null
        return if (Build.VERSION.SDK_INT >= 26 && f.isShowingHintText) "" else if (t == hint) "" else t
    }

    private fun setText(f: AccessibilityNodeInfo, text: String): Boolean {
        val ok = f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        f.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
        })
        return ok
    }

    fun keyboard(action: String, text: String, count: Int): String {
        guard()?.let { return it }
        if (action == "enter") return pressEnter()
        val f = focusedField() ?: return "Likhne ki jagah (text box) nahi mili. Pehle box pe click karwao."
        if (f.isPassword) return "Password field mein main kuch nahi karti."
        f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val cur = currentText(f)
        return when (action) {
            "type" -> {
                val sep = if (cur.isNotEmpty() && !cur.endsWith(" ") && text.isNotEmpty() && text != " ") " " else ""
                if (setText(f, cur + sep + text)) "Likh diya." else "Type nahi ho paya."
            }
            "replace" -> if (setText(f, text)) "Likh diya." else "Type nahi ho paya."
            "space" -> if (setText(f, "$cur ")) "Space daal diya." else "Nahi hua."
            "backspace" -> if (setText(f, cur.dropLast(count.coerceAtLeast(1)))) "Mita diya." else "Nahi mita."
            "delete_word" -> {
                val words = cur.trimEnd().split(" ").toMutableList()
                repeat(count.coerceAtLeast(1)) { if (words.isNotEmpty()) words.removeAt(words.size - 1) }
                if (setText(f, words.joinToString(" "))) "Shabd mita diya." else "Nahi mita."
            }
            "clear" -> if (setText(f, "")) "Sab mita diya." else "Nahi mita."
            "select_all" -> if (f.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cur.length)
                })) "Sab select kar diya." else "Select nahi hua."
            "copy" -> {
                if (cur.isNotEmpty()) {
                    f.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cur.length)
                    })
                }
                if (f.performAction(AccessibilityNodeInfo.ACTION_COPY)) "Copy kar liya." else "Copy nahi hua."
            }
            "cut" -> if (f.performAction(AccessibilityNodeInfo.ACTION_CUT)) "Cut kar diya." else "Cut nahi hua."
            "paste" -> if (f.performAction(AccessibilityNodeInfo.ACTION_PASTE)) "Paste kar diya." else "Paste nahi hua."
            "next_field" -> {
                val fields = allNodes().filter { it.isEditable && it.isVisibleToUser }
                val i = fields.indexOfFirst { it.isFocused }
                val next = fields.getOrNull(i + 1) ?: return "Aage koi aur box nahi hai."
                next.performAction(AccessibilityNodeInfo.ACTION_CLICK); next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                "Agle box pe aa gaye."
            }
            else -> "Keyboard action samajh nahi aaya: $action"
        }
    }

    // ======================= AGENT (screen dekh ke step-by-step) =======================

    @Volatile private var agentNodes: List<AccessibilityNodeInfo> = emptyList()

    /** Screen ke kaam ke elements numbered list mein: [3] button "Send". */
    fun snapshot(max: Int): String {
        val out = StringBuilder()
        val list = mutableListOf<AccessibilityNodeInfo>()
        val seen = HashSet<String>()
        for (n in allNodes()) {
            if (!n.isVisibleToUser) continue
            val r = android.graphics.Rect(); n.getBoundsInScreen(r)
            if (r.width() < 8 || r.height() < 8) continue
            val raw = if (n.isPassword) "(password)" else label(n).replace(Regex("\\s+"), " ").trim()
            val interactive = n.isClickable || n.isEditable || n.isLongClickable || n.isCheckable
            if (!interactive && raw.length < 2) continue
            val kind = when {
                n.isEditable -> "input"
                n.isCheckable -> if (n.isChecked) "switch(on)" else "switch(off)"
                n.isClickable || n.isLongClickable -> "button"
                else -> "text"
            }
            var txt = Safety.maskOtp(raw).take(60)
            if (txt.isEmpty()) {
                // bina naam ka button: andar ke text se naam lo
                txt = (0 until n.childCount).mapNotNull { n.getChild(it)?.let { c -> label(c) } }
                    .firstOrNull { it.isNotBlank() }?.take(40) ?: (n.viewIdResourceName?.substringAfter("/")?.take(30) ?: "")
            }
            val key = "$kind|$txt|${r.centerX() / 20}|${r.centerY() / 20}"
            if (!seen.add(key)) continue
            if (kind == "text" && list.size >= max * 3 / 4) continue
            list.add(n)
            out.append("[").append(list.size).append("] ").append(kind).append(" \"").append(txt).append("\"")
            if (n.isFocused && n.isEditable) out.append(" (focused)")
            if (n.isSelected) out.append(" (selected)")
            out.append("\n")
            if (list.size >= max) break
        }
        agentNodes = list
        return out.toString().ifBlank { "(screen khali / padh nahi paayi)" }
    }

    private fun agentNode(id: Int): AccessibilityNodeInfo? = agentNodes.getOrNull(id - 1)

    fun agentLabel(id: Int): String = agentNode(id)?.let { label(it) } ?: ""

    fun agentTap(id: Int, long: Boolean = false): String {
        guard()?.let { return it }
        val n = agentNode(id) ?: return "Number $id screen pe nahi hai."
        var c: AccessibilityNodeInfo? = n
        while (c != null && !(if (long) c.isLongClickable else c.isClickable)) c = c.parent
        val act = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        if (c != null && c.performAction(act)) return "[$id] daba diya."
        val r = android.graphics.Rect(); n.getBoundsInScreen(r)
        return if (tap(r.exactCenterX(), r.exactCenterY(), if (long) 700L else 60L)) "[$id] tap kiya." else "[$id] dab nahi paya."
    }

    fun agentType(id: Int, text: String): String {
        guard()?.let { return it }
        val n = agentNode(id)?.takeIf { it.isEditable } ?: return typeText(text)
        if (n.isPassword) return "Password field mein main nahi likhti."
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return if (setText(n, text)) "Likh diya: $text" else "Type nahi ho paya."
    }

    fun clearOverlays() { onMain { hideCursor(); hideLabels() } }

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
