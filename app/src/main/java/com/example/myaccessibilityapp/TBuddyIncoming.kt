package com.example.myaccessibilityapp

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import android.widget.TextView
import com.google.gson.Gson
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TBuddyIncoming : AccessibilityService() {

    private val TAG = "MyAccessibilityService"

    private val gson = Gson()
    private val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl("http://127.0.0.1:5000/")  // emulator; use LAN IP on device
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    private val apiForUrls: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl("http://10.0.2.2:5000/")  // emulator; use LAN IP on device
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
    enum class WarningType { PERSONAL_DATA, SENTIMENT_NEGATIVE, HARM_NEGATIVE, NONE }

    // ---- state
    private var eventJob: Job? = null
    private var convoWatcher: ConversationWatcher? = null
    private var currentPkg: String? = null
    private val lastMsgHashPerApp = mutableMapOf<String, Int>()

    // ---- bubble UI
    private var coachView: View? = null
    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = 32; y = 32 }
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val pkg = event.packageName?.toString() ?: return
        if (!isMessagingApp(pkg)) {
            // Leaving a messaging app? stop watcher
            if (currentPkg != null && currentPkg != pkg) convoWatcher?.stop()
            currentPkg = null
            return
        }

        currentPkg = pkg

        // Debounce bursts
        eventJob?.cancel()
        eventJob = CoroutineScope(Dispatchers.Default).launch {
            delay(120) // tiny delay; we'll poll too

            val root = rootInActiveWindow ?: return@launch
            val inConv = isConversationScreen(root)
            if (!inConv) {
                // If we were watching and we left the chat, stop watcher
                convoWatcher?.stop()
                return@launch
            }

            // Start or refresh the watcher for this pkg
            if (convoWatcher?.pkg != pkg) {
                convoWatcher?.stop()
                convoWatcher = ConversationWatcher(pkg).also { it.start() }
            } else {
                convoWatcher?.poke() // extend lifetime on activity
            }

            // Also attempt an immediate extract on this event
            extractAndSendIfNew(pkg, root)
        }
    }

    override fun onInterrupt() { hideCoachBubble() }

    // ---------- ConversationWatcher ----------
    private inner class ConversationWatcher(val pkg: String) {
        private var job: Job? = null
        private var lastSignature: Int? = null

        fun start() {
            job?.cancel()
            job = CoroutineScope(Dispatchers.Default).launch {
                val startedAt = System.currentTimeMillis()
                while (isActive) {
                    delay(650) // gentle poll; catches silent updates
                    val root = rootInActiveWindow ?: continue
                    if (!isConversationScreen(root)) break

                    // Simple signature: count + some text hash of the tree to detect change
                    val sig = buildTreeSignature(root)
                    if (sig != lastSignature) {
                        lastSignature = sig
                        extractAndSendIfNew(pkg, root)
                    }

                    // If idle > 12s without pokes, stop to save battery
                    if (System.currentTimeMillis() - startedAt > 12_000 && !hasPokes.getAndSet(false)) break
                }
                Log.d(TAG, "ConversationWatcher stopped for $pkg")
            }
            Log.d(TAG, "ConversationWatcher started for $pkg")
        }

        private val hasPokes = java.util.concurrent.atomic.AtomicBoolean(true)
        fun poke() { hasPokes.set(true) }
        fun stop() { job?.cancel(); job = null }
    }

    private fun buildTreeSignature(root: AccessibilityNodeInfo): Int {
        var count = 0
        fun textOf(n: AccessibilityNodeInfo): String =
            (n.text?.toString()?.trim().orEmpty()).ifEmpty {
                n.contentDescription?.toString()?.trim().orEmpty()
            }
        fun dfs(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = textOf(n)
            if (t.isNotEmpty() && !n.isEditable) count++
            for (i in 0 until n.childCount) dfs(n.getChild(i))
        }
        dfs(root)
        return count
    }

    // ---------- core: extract + send if new ----------
    private suspend fun extractAndSendIfNew(pkg: String, root: AccessibilityNodeInfo) {
        val last = LastMessageExtractor.extractLast(root) ?: return
        val hash = (pkg + "|" + last.text).hashCode()
        if (lastMsgHashPerApp[pkg] == hash) return
        lastMsgHashPerApp[pkg] = hash

        withContext(Dispatchers.Main) {
            showCoachBubble("T-Buddy", "🔎 Checking the last message…")
        }
        val warning = analyzeTextWithApi(last.text)
        withContext(Dispatchers.Main) {
            when (warning) {
                WarningType.PERSONAL_DATA -> showCoachBubble("Warning!", "⚠️ Personal data detected.")
                WarningType.SENTIMENT_NEGATIVE -> showCoachBubble("Warning!", "⚠️ Negative sentiment detected.")
                WarningType.HARM_NEGATIVE -> showCoachBubble("Warning!", "⚠️ Harmful language detected.")
                WarningType.NONE -> hideCoachBubble()
            }
        }
    }

    // ---------- Conversation detection (same as before) ----------
    private fun isConversationScreen(root: AccessibilityNodeInfo): Boolean {
        val screen = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        val bottomZoneY = screen.top + (screen.height() * 0.65f).toInt()
        val midZoneY    = screen.top + (screen.height() * 0.20f).toInt()
        var hasBottomInput = false
        var sendNearBottom = false
        var msgCount = 0

        fun nodeText(n: AccessibilityNodeInfo): String =
            (n.text?.toString()?.trim().orEmpty()).ifEmpty {
                n.contentDescription?.toString()?.trim().orEmpty()
            }
        fun looksLikeMessageText(t: String): Boolean {
            if (t.isBlank()) return false
            val lower = t.lowercase()
            val blacklist = listOf(
                "see more","your story","stories","search","create","new message","calls","camera",
                "marketplace","inbox","home","reels","status",
                "δείτε περισσότερα","η ιστορία σας","ιστορίες","αναζήτηση","εισερχόμενα","καρτέλα","αρχική"
            )
            if (blacklist.any { lower.contains(it) }) return false
            if (t.all { it.isDigit() } && t.length <= 3) return false
            if (t.matches(Regex("""^\d{1,2}:\d{2}(\s?[AP]M)?$"""))) return false
            return true
        }
        fun dfs(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val r = android.graphics.Rect()
            n.getBoundsInScreen(r)
            if (n.isEditable && r.top >= bottomZoneY) hasBottomInput = true

            val t = nodeText(n).lowercase()
            val cls = n.className?.toString().orEmpty()
            val isSend = t.contains("send") || t.contains("αποστολή") ||
                    (n.viewIdResourceName?.contains("send", true) == true)
            if (isSend && r.top >= bottomZoneY) sendNearBottom = true

            val textish = cls.contains("TextView", true) || cls.contains("AppCompatTextView", true) || t.length >= 2
            val notButtonOrImage = !(cls.contains("Button", true) || cls.contains("Image", true))
            if (!n.isEditable && r.centerY() >= midZoneY && textish && notButtonOrImage && looksLikeMessageText(t)) {
                msgCount++
            }
            for (i in 0 until n.childCount) dfs(n.getChild(i))
        }
        dfs(root)
        val inConv = (hasBottomInput || sendNearBottom) && msgCount >= 2
        Log.d(TAG, "isConversationScreen -> input=$hasBottomInput send=$sendNearBottom msgs=$msgCount -> $inConv")
        return inConv
    }

    private fun isMessagingApp(pkg: String) = when (pkg) {
        "com.facebook.orca", "com.whatsapp", "com.viber.voip", "com.discord",
        "com.google.android.apps.messaging", "org.telegram.messenger",
        "org.thunderdog.challegram", "com.snapchat.android", "com.instagram.android"
            -> true
        else -> false
    }

    // ---- bubble helpers (unchanged)
    private fun showCoachBubble(title: String, message: String) {
        hideCoachBubble()
        coachView = LayoutInflater.from(this).inflate(R.layout.view_tbuddy_coach, null)
        coachView!!.findViewById<View>(R.id.tbuddyCoach).visibility = View.VISIBLE
        coachView!!.findViewById<TextView>(R.id.title).text = title
        coachView!!.findViewById<TextView>(R.id.message).text = message
        coachView!!.findViewById<ImageView>(R.id.buddy).setImageResource(R.drawable.robot)
        try {
            windowManager.addView(coachView, params)
            val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 300; fillAfter = true }
            coachView!!.startAnimation(fadeIn)
            coachView!!.postDelayed({ hideCoachBubble() }, 4000)
        } catch (_: Exception) {}
    }
    private fun hideCoachBubble() {
        coachView?.let { view ->
            try {
                val fadeOut = AlphaAnimation(1f, 0f).apply { duration = 300; fillAfter = true }
                view.startAnimation(fadeOut)
                view.postDelayed({ try { windowManager.removeView(view) } catch (_: Exception) {} }, 300)
            } catch (_: Exception) {}
        }
        coachView = null
    }

    private suspend fun analyzeTextWithApi(text: String): WarningType {
        try {
            val request = ApiRequest(text = text)
            Log.i(TAG, "Sending Payload: ${gson.toJson(request)}")
            val response = apiService.analyzeText(request)
            if (response.isSuccessful) {
                response.body()?.finalVerdict?.let {
                    if (it.personalDataProbability > 0.70) return WarningType.PERSONAL_DATA
                    if (it.sentimentNegativeProbability > 0.70) return WarningType.SENTIMENT_NEGATIVE
                }
            } else Log.e(TAG, "API failed: ${response.code()} ${response.message()}")
        } catch (e: Exception) {
            Log.e(TAG, "API exception: ${e.message}", e)
        }
        return WarningType.NONE
    }
}
