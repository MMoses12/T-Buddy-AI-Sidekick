// LastMessageExtractor.kt
package com.example.myaccessibilityapp

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object LastMessageExtractor {

    private const val TAG = "LastMessageExtractor"

    data class UiMessage(val sender: String, val text: String, val bottom: Int)

    fun extractLast(root: AccessibilityNodeInfo?): UiMessage? {
        if (root == null) {
            Log.w(TAG, "Root is null")
            return null
        }

        val results = ArrayList<UiMessage>()
        val rect = Rect()
        val rootRect = Rect().also { root.getBoundsInScreen(it) }
        val topIgnoreY = rootRect.top + (rootRect.height() * 0.18f).toInt()   // ignore top ~18%
        val bottomIgnoreY = rootRect.bottom - (rootRect.height() * 0.06f).toInt() // ignore bottom strip (tab bar)

        fun nodeText(n: AccessibilityNodeInfo): String {
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) return t
            val cd = n.contentDescription?.toString()?.trim().orEmpty()
            return cd
        }

        fun isLikelyMessageText(n: AccessibilityNodeInfo): Boolean {
            if (n.isEditable) return false
            val t = nodeText(n)
            if (t.isEmpty()) return false

            // Skip common non-message UI strings (en/gr; add more as you see them)
            val lower = t.lowercase()
            val blacklist = listOf(
                "δείτε περισσότερα", "η ιστορία σας", "ιστορίες", "αναζήτηση", "ζητούμενο",
                "see more", "your story", "stories", "search", "create", "new message", "calls",
                "camera", "marketplace", "inbox", "home", "reels", "status"
            )
            if (blacklist.any { lower.contains(it) }) return false

            // Skip short numeric badges and clock-like strings
            if (t.all { it.isDigit() } && t.length <= 3) return false
            if (t.matches(Regex("""^\d{1,2}:\d{2}(\s?[AP]M)?$"""))) return false

            // Screen position heuristics: avoid top app bar and bottom nav bar
            n.getBoundsInScreen(rect)
            if (rect.top < topIgnoreY) return false
            if (rect.bottom > bottomIgnoreY) return false

            // Avoid obvious buttons/images
            val cls = n.className?.toString().orEmpty()
            if (cls.contains("Image", true) || cls.contains("Button", true)) return false

            return true
        }

        fun guessSender(n: AccessibilityNodeInfo): String {
            n.contentDescription?.toString()?.let { if (it.isNotBlank()) return it }
            n.parent?.contentDescription?.toString()?.let { if (it.isNotBlank()) return it }
            return ""
        }

        fun dfs(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (isLikelyMessageText(n)) {
                n.getBoundsInScreen(rect)
                val txt = nodeText(n)
                val msg = UiMessage(guessSender(n), txt, rect.bottom)
                results.add(msg)
                Log.d(TAG, "Candidate: '${msg.text}' bottom=${msg.bottom}")
            }
            for (i in 0 until n.childCount) dfs(n.getChild(i))
        }

        dfs(root)

        val last = results.maxByOrNull { it.bottom }
        if (last != null) {
            Log.i(TAG, "Last (bottommost) message: '${last.text}' sender='${last.sender}'")
        } else {
            Log.w(TAG, "No bottom message found")
        }
        return last
    }
}
