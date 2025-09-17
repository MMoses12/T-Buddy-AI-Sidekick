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

class TBuddy : AccessibilityService() {

    private val TAG = "TBuddy"

    // ---- API
    private val gson = Gson()
    private val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl("http://127.0.0.1:5000/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    enum class WarningType { PERSONAL_DATA, SENTIMENT_NEGATIVE, HARM_NEGATIVE, NONE }

    // ---- coroutines
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var uiPollJob: Job? = null           // conversation watcher polling
    private var uiDebounceJob: Job? = null       // debounce for tree/Window changes
    private var typedDebounceJob: Job? = null    // debounce for TYPE_VIEW_TEXT_CHANGED

    // ---- state
    private var currentPkg: String? = null
    private var convoWatcher: ConversationWatcher? = null
    private val lastIncomingHashPerApp = mutableMapOf<String, Int>() // dedupe incoming
    private val lastTypedHashPerApp    = mutableMapOf<String, Int>() // dedupe typed

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
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // Track transitions between messaging vs non-messaging apps
        if (!isMessagingApp(pkg)) {
            if (currentPkg != null && currentPkg != pkg) convoWatcher?.stop()
            currentPkg = null
            return
        }
        currentPkg = pkg

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Outgoing typing (from editable fields)
                val isFromEditable = event.source?.isEditable == true || event.className?.toString()?.contains("EditText", true) == true
                val typedText = event.text?.joinToString(" ")?.trim().orEmpty()
                if (isFromEditable && typedText.isNotBlank()) {
                    // Debounce: user is still typing
                    typedDebounceJob?.cancel()
                    typedDebounceJob = serviceScope.launch {
                        delay(500)
                        analyzeTypedIfNew(pkg, typedText)
                    }
                }
                // Also nudge the convo watcher if we’re clearly in a chat
                pokeOrStartWatcher(pkg)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Incoming/messages UI changed -> debounce and extract last message
                uiDebounceJob?.cancel()
                uiDebounceJob = serviceScope.launch {
                    delay(120)
                    val root = rootInActiveWindow ?: return@launch
                    if (!isConversationScreen(root)) {
                        convoWatcher?.stop()
                        return@launch
                    }
                    pokeOrStartWatcher(pkg)
                    extractIncomingAndSendIfNew(pkg, root)
                }
            }
        }
    }

    override fun onInterrupt() {
        hideCoachBubble()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        convoWatcher?.stop()
        uiPollJob?.cancel()
        uiDebounceJob?.cancel()
        typedDebounceJob?.cancel()
        hideCoachBubble()
    }

    // ---------- ConversationWatcher ----------
    private inner class ConversationWatcher(val pkg: String) {
        private var job: Job? = null
        private var lastSignature: Int? = null
        private val hasPokes = java.util.concurrent.atomic.AtomicBoolean(true)

        fun start() {
            job?.cancel()
            job = serviceScope.launch {
                val startedAt = System.currentTimeMillis()
                while (isActive) {
                    delay(650)
                    val root = rootInActiveWindow ?: continue
                    if (!isConversationScreen(root)) break

                    val sig = buildTreeSignature(root)
                    if (sig != lastSignature) {
                        lastSignature = sig
                        extractIncomingAndSendIfNew(pkg, root)
                    }
                    if (System.currentTimeMillis() - startedAt > 12_000 && !hasPokes.getAndSet(false)) break
                }
                Log.d(TAG, "ConversationWatcher stopped for $pkg")
            }
            Log.d(TAG, "ConversationWatcher started for $pkg")
        }

        fun poke() { hasPokes.set(true) }
        fun stop() { job?.cancel(); job = null }
    }

    private fun pokeOrStartWatcher(pkg: String) {
        if (convoWatcher?.pkg != pkg) {
            convoWatcher?.stop()
            convoWatcher = ConversationWatcher(pkg).also { it.start() }
        } else {
            convoWatcher?.poke()
        }
    }

    // ---------- Incoming extraction ----------
    private suspend fun extractIncomingAndSendIfNew(pkg: String, root: AccessibilityNodeInfo) {
        val last = LastMessageExtractor.extractLast(root) ?: return
        val hash = (pkg + "|" + last.text).hashCode()
        if (lastIncomingHashPerApp[pkg] == hash) return
        lastIncomingHashPerApp[pkg] = hash

//        withContext(Dispatchers.Main) {
//            showCoachBubble("T-Buddy", "🔎 Checking the last message…")
//        }
        val warning = analyzeTextWithApi(last.text)
        withContext(Dispatchers.Main) { showWarningOrHide(warning) }
    }

    // ---------- Outgoing (typed) analysis ----------
    private suspend fun analyzeTypedIfNew(pkg: String, typed: String) {
        val hash = (pkg + "|" + typed).hashCode()
        if (lastTypedHashPerApp[pkg] == hash) return
        lastTypedHashPerApp[pkg] = hash

        Log.d(TAG, "Typed message captured from $pkg: \"$typed\"")

//        withContext(Dispatchers.Main) {
//            showCoachBubble("T-Buddy", "🔎 Checking your message…")
//        }
        val warning = analyzeTextWithApi(typed)
        withContext(Dispatchers.Main) { showWarningOrHide(warning) }
    }

    private fun showWarningOrHide(warning: WarningType) {
        when (warning) {
            WarningType.PERSONAL_DATA -> showCoachBubble("Warning!", "⚠️ Personal data detected.")
            WarningType.SENTIMENT_NEGATIVE -> showCoachBubble("Warning!", "⚠️ Negative sentiment detected.")
            WarningType.HARM_NEGATIVE -> showCoachBubble("Warning!", "⚠️ Harmful language detected.")
            WarningType.NONE -> hideCoachBubble()
        }
    }

    // ---------- Helpers ----------
    private fun isMessagingApp(pkg: String) = when (pkg) {
        "com.facebook.orca", "com.whatsapp", "com.viber.voip", "com.discord",
        "com.google.android.apps.messaging", "org.telegram.messenger",
        "org.thunderdog.challegram", "com.snapchat.android", "com.instagram.android"
            -> true
        else -> false
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
                    // if (it.harmNegativeProbability > 0.70) return WarningType.HARM_NEGATIVE
                }
            } else {
                Log.e(TAG, "API failed: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "API exception: ${e.message}", e)
        }
        return WarningType.NONE
    }
}
