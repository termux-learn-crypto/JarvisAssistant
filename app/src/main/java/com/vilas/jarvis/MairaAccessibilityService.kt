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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
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
    }
}
