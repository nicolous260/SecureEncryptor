package com.secureencryptor.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.secureencryptor.app.crypto.CryptoEngine
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * WhatsApp # mode
 *
 *  PASSWORD   type  #password#      saved in memory (type again with a new word to change it). Opens the reader.
 *  SEND       type  your text#      encrypted with the saved password and sent automatically
 *  READ       the reader replaces the whole chat area with the decrypted conversation.
 *             Scroll it; at the top/bottom it scrolls WhatsApp for you to load older/newer messages.
 *             ✕ closes it (a green 🔓 button stays on the right edge to reopen it).
 *  LOCK       type  ##   or tap "Lock" (also auto-locks after 2 h idle)
 *  ONE-OFF    type  #password,text#   (no saved password needed)
 */
class HashtagAccessibilityService : AccessibilityService() {

    private data class Row(val text: String, val enc: Boolean, val rect: Rect)
    private data class Hist(val text: String, val enc: Boolean, val mine: Boolean)

    companion object {
        /** After encrypting, tap WhatsApp's send button automatically. */
        const val AUTO_SEND = true
        private const val MAX_HISTORY = 400
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private var sessionPassword: String? = null
    private var lastUse = 0L
    private var sheetWanted = false

    // ciphertext -> plaintext ("" = failed / other password). Main thread only.
    private val results = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 600
    }
    private val pending = HashSet<String>()
    private var lastRows: List<Row> = emptyList()
    private var history: List<Hist> = emptyList()
    private var chatRect: Rect? = null
    private var scanScheduled = false

    private var sheet: LinearLayout? = null
    private var sheetAnchored = false
    private var sheetList: LinearLayout? = null
    private var sheetScroll: ScrollView? = null
    private var sheetTitle: TextView? = null
    private var sheetSignature = ""
    private var rebuilding = false
    private var lastChatScroll = 0L
    private var pill: TextView? = null
    private var popup: TextView? = null

    private val encryptRe = Regex("""^#([^,#]+),(.+)#$""", RegexOption.DOT_MATCHES_ALL)
    private val unlockRe = Regex("""^#([^,#]+)#$""")
    private val quickRe = Regex("""^([^#]+)#$""", RegexOption.DOT_MATCHES_ALL)
    private val pasteDecryptRe = Regex("""^([A-Za-z0-9+/=\s]{40,})#([^#]+)#$""")

    private val waPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val autoLockMs = 2 * 60 * 60 * 1000L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val wm get() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) handleTyping(e) else scheduleScan()
    }

    // ------------------------------------------------------------ typing triggers

    private fun handleTyping(e: AccessibilityEvent) {
        if (busy) return
        val node = e.source ?: return
        val text = node.text?.toString()?.trim().orEmpty()
        if (!node.isEditable || !text.endsWith("#")) { node.recycle(); return }

        if (text == "##") {
            setField(node, "")
            lock(true)
            node.recycle(); return
        }

        unlockRe.matchEntire(text)?.let { m ->
            sessionPassword = m.groupValues[1]
            lastUse = System.currentTimeMillis()
            results.clear(); pending.clear(); sheetSignature = ""; history = emptyList()
            sheetWanted = true
            setField(node, "")
            Toast.makeText(this, "Password set. Write  message#  to send encrypted", Toast.LENGTH_LONG).show()
            node.recycle()
            scheduleScan()
            return
        }

        quickRe.matchEntire(text)?.let { m ->
            val pw = sessionPassword
            if (pw == null) {
                Toast.makeText(this, "No password yet. Type  #password#  first", Toast.LENGTH_SHORT).show()
                node.recycle(); return
            }
            val plain = m.groupValues[1].trim()
            if (plain.isEmpty()) { node.recycle(); return }
            lastUse = System.currentTimeMillis()
            encryptAndPut(node, pw, plain)
            return
        }

        val enc = encryptRe.matchEntire(text)
        if (enc != null) {
            encryptAndPut(node, enc.groupValues[1], enc.groupValues[2])
            return
        }

        val dec = pasteDecryptRe.matchEntire(text)
        if (dec != null) {
            busy = true
            worker.execute {
                val plain = try {
                    CryptoEngine.decryptText(dec.groupValues[2], dec.groupValues[1])
                } catch (ex: Exception) { "Wrong password or not an encrypted message" }
                main.post { setField(node, ""); showPopup(plain) }
                main.postDelayed({ busy = false; node.recycle() }, 250)
            }
            return
        }
        node.recycle()
    }

    private fun encryptAndPut(node: AccessibilityNodeInfo, password: String, plain: String) {
        busy = true
        worker.execute {
            try {
                val out = CryptoEngine.encryptText(password, plain)
                main.post {
                    setField(node, out)
                    if (AUTO_SEND) main.postDelayed({ clickSend(0) }, 300)
                }
            } catch (ex: Exception) {
                main.post { Toast.makeText(this, "Error: ${ex.message}", Toast.LENGTH_SHORT).show() }
            } finally {
                main.postDelayed({ busy = false; node.recycle() }, 250)
            }
        }
    }

    private fun setField(node: AccessibilityNodeInfo, value: String) {
        node.refresh()
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, value.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, value.length)
        })
    }

    /** Tap WhatsApp's send button. If it can't be found the ciphertext simply stays in the box. */
    private fun clickSend(attempt: Int) {
        val root = rootInActiveWindow
        var target: AccessibilityNodeInfo? = null
        if (root != null) {
            for (pkg in waPackages) {
                target = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
                if (target != null) break
            }
            if (target == null) {
                target = root.findAccessibilityNodeInfosByText("Send").firstOrNull { it.isClickable }
            }
        }
        if (target != null && target.isEnabled) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else if (attempt < 3) {
            main.postDelayed({ clickSend(attempt + 1) }, 250)
        }
    }

    // ------------------------------------------------------------ reading

    private fun lock(announce: Boolean) {
        sessionPassword = null
        sheetWanted = false
        results.clear(); pending.clear()
        hideWindows()
        if (announce) Toast.makeText(this, "Locked", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleScan() {
        if (sessionPassword == null || scanScheduled) return
        scanScheduled = true
        main.postDelayed({ scanScheduled = false; scan() }, 150)
    }

    private fun scan() {
        val pw = sessionPassword ?: return
        if (System.currentTimeMillis() - lastUse > autoLockMs) { lock(true); return }
        if (!whatsappInForeground()) { hideWindows(); return }
        val root = rootInActiveWindow
        if (root == null) { hideWindows(); return }

        val rows = ArrayList<Row>()
        val scrollers = ArrayList<Rect>()
        collect(root, rows, scrollers)
        chatRect = scrollers.maxByOrNull { it.width() * it.height() }
        lastRows = rows
        mergeHistory(rows)

        for (row in rows) {
            if (!row.enc) continue
            val key = row.text
            if (results.containsKey(key) || !pending.add(key)) continue
            worker.execute {
                val plain = try { CryptoEngine.decryptText(pw, key) } catch (ex: Exception) { "" }
                main.post {
                    pending.remove(key)
                    if (sessionPassword == pw) { results[key] = plain; refreshUi() }
                }
            }
        }
        refreshUi()
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Row>, scrollers: MutableList<Rect>) {
        val cls = n.className?.toString().orEmpty()
        if (n.isScrollable && (cls.contains("RecyclerView") || cls.contains("ListView"))) {
            val r = Rect(); n.getBoundsInScreen(r); scrollers.add(r)
        }
        val t = n.text?.toString()
        if (t != null && t.isNotBlank() && !n.isEditable && n.isVisibleToUser) {
            val enc = t.length >= 40 && t.startsWith("RU5DMg")
            val id = n.viewIdResourceName.orEmpty()
            if (enc || id.endsWith(":id/message_text")) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty) out.add(Row(t.trim(), enc, r))
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collect(c, out, scrollers)
        }
    }

    /**
     * Keep an ordered conversation: the visible window v is authoritative for the region it covers,
     * older/newer messages already seen stay around it. Encrypted messages (unique) act as anchors.
     */
    private fun mergeHistory(rows: List<Row>) {
        val w = resources.displayMetrics.widthPixels
        val v = rows.sortedBy { it.rect.top }.map { Hist(it.text, it.enc, it.rect.left > w * 0.10) }
        if (v.isEmpty()) return
        val known = history.filter { it.enc }.map { it.text }.toHashSet()
        val firstA = v.indexOfFirst { it.enc && it.text in known }
        val lastA = v.indexOfLast { it.enc && it.text in known }
        if (firstA < 0) { history = v; return }
        val hFirst = history.indexOfFirst { it.enc && it.text == v[firstA].text }
        val hLast = history.indexOfFirst { it.enc && it.text == v[lastA].text }
        if (hFirst < 0 || hLast < hFirst) { history = v; return }
        val head = history.subList(0, maxOf(0, hFirst - firstA))
        val tailStart = minOf(history.size, hLast + 1 + (v.size - 1 - lastA))
        val tail = history.subList(tailStart, history.size)
        var merged = head + v + tail
        if (merged.size > MAX_HISTORY) merged = merged.subList(merged.size - MAX_HISTORY, merged.size)
        history = merged
    }

    // ------------------------------------------------------------ UI: pill + reader

    private fun refreshUi() {
        if (sessionPassword == null || !whatsappInForeground()) { hideWindows(); return }
        val visibleDec = lastRows.count { it.enc && !results[it.text].isNullOrEmpty() }
        if (visibleDec > 0) lastUse = System.currentTimeMillis()
        if (sheetWanted) {
            hidePill()
            if (history.isNotEmpty()) showSheet() else hideSheet()
        } else if (visibleDec > 0) {
            hideSheet(); showPill(visibleDec)
        } else {
            hideSheet(); hidePill()
        }
        if (sheet != null || pill != null) {
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, 300)
        }
    }

    private fun showPill(count: Int) {
        val p = pill ?: createPill()
        p.text = "🔓 $count"
    }

    private fun createPill(): TextView {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(0xF0128C7E.toInt()); cornerRadius = dp(24).toFloat()
            }
            setOnClickListener { sheetWanted = true; refreshUi() }
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = (resources.displayMetrics.heightPixels * 0.30).toInt()
        }
        wm.addView(tv, lp)
        pill = tv
        return tv
    }

    private fun hidePill() {
        pill?.let { runCatching { wm.removeView(it) } }
        pill = null
    }

    private fun showSheet() {
        val cr = chatRect
        val anchored = cr != null && cr.height() > dp(120)
        val y = if (anchored) cr!!.top else 0
        val h = if (anchored) cr!!.height() else (resources.displayMetrics.heightPixels * 0.42).toInt()
        if (sheet != null && sheetAnchored != anchored) hideSheet()
        if (sheet == null) createSheet(y, h, anchored)

        // follow the chat area (e.g. when the keyboard opens)
        val v = sheet!!
        val lp = v.layoutParams as WindowManager.LayoutParams
        if (abs(lp.height - h) > 4 || (anchored && abs(lp.y - y) > 4 && !offsetFixPending)) {
            lp.height = h
            if (anchored) lp.y = y + yCorrection
            runCatching { wm.updateViewLayout(v, lp) }
        }

        val decrypted = history.count { it.enc && !results[it.text].isNullOrEmpty() }
        sheetTitle?.text = "🔓 Decrypted chat · $decrypted"
        val sig = history.joinToString("\u0001") {
            (if (it.mine) "M" else "T") + (if (it.enc) "E" else "P") + (results[it.text] ?: if (it.enc) "?" else it.text)
        }
        if (sig != sheetSignature) { sheetSignature = sig; rebuildList() }
    }

    private var yCorrection = 0
    private var offsetFixPending = false

    private fun createSheet(y: Int, h: Int, anchored: Boolean) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { setColor(0xFF0B1115.toInt()) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            setTextColor(0xFF9FD8CB.toInt()); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(0xFFE9EDEF.toInt()); textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }
        header.addView(button("Lock") { lock(true) })
        header.addView(button("✕") { sheetWanted = false; refreshUi() })
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(list)
            setOnScrollChangeListener { v, _, scrollY, _, oldY ->
                if (rebuilding) return@setOnScrollChangeListener
                val sv = v as ScrollView
                val child = sv.getChildAt(0) ?: return@setOnScrollChangeListener
                if (scrollY < oldY && scrollY <= dp(60)) requestChatScroll(false)
                else if (scrollY > oldY && scrollY + sv.height >= child.height - dp(60)) requestChatScroll(true)
            }
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (anchored) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.y = y
        }
        wm.addView(root, lp)
        sheet = root; sheetAnchored = anchored
        sheetList = list; sheetScroll = scroll; sheetTitle = title
        sheetSignature = ""; yCorrection = 0

        if (anchored) {
            // some phones offset the window (display cutout): measure once and correct
            offsetFixPending = true
            root.post {
                val loc = IntArray(2)
                if (root.isAttachedToWindow) {
                    root.getLocationOnScreen(loc)
                    val diff = loc[1] - y
                    if (abs(diff) > 2) {
                        yCorrection -= diff
                        lp.y = y + yCorrection
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                }
                offsetFixPending = false
            }
        }
    }

    private fun rebuildList() {
        val list = sheetList ?: return
        val sv = sheetScroll ?: return
        val oldContentH = list.height
        val distFromBottom = oldContentH - sv.scrollY - sv.height
        val wasAtBottom = oldContentH == 0 || distFromBottom < dp(24)

        rebuilding = true
        list.removeAllViews()

        val older = TextView(this).apply {
            text = "⬆ older messages"
            setTextColor(0xFF7FA79E.toInt()); textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(10))
            setOnClickListener { lastChatScroll = 0; requestChatScroll(false) }
        }
        list.addView(older, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val maxW = (resources.displayMetrics.widthPixels * 0.82).toInt()
        for (m in history) {
            val dec = if (m.enc) results[m.text] else null
            val label: String
            val bg: Int
            val fg: Int
            var copyable = true
            when {
                !m.enc -> {
                    label = m.text
                    bg = if (m.mine) 0xFF16302B.toInt() else 0xFF1C252B.toInt()
                    fg = 0xFFB9C4CA.toInt()
                }
                dec == null -> {
                    label = "…"
                    bg = 0xFF1C252B.toInt(); fg = 0xFF7D8C94.toInt(); copyable = false
                }
                dec.isEmpty() -> {
                    label = "🔒 Different password"
                    bg = 0xFF2B2230.toInt(); fg = 0xFFC9A9D6.toInt(); copyable = false
                }
                else -> {
                    label = dec
                    bg = if (m.mine) 0xFF0B5D4B.toInt() else 0xFF2A343B.toInt()
                    fg = 0xFFE9EDEF.toInt()
                }
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(fg)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                maxWidth = maxW
                background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(14).toFloat() }
                if (copyable) setOnClickListener { copyText(label) }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (m.mine) Gravity.END else Gravity.START
                bottomMargin = dp(6)
            }
            list.addView(tv, lp)
        }

        sv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                sv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val newH = list.height
                val target = if (wasAtBottom) newH - sv.height else newH - sv.height - distFromBottom
                sv.scrollTo(0, maxOf(0, target))
                rebuilding = false
            }
        })
    }

    /** Scroll WhatsApp's own message list one page so older/newer messages get loaded into the reader. */
    private fun requestChatScroll(forward: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastChatScroll < 700) return
        lastChatScroll = now
        val root = rootInActiveWindow ?: return
        val list = findChatList(root) ?: return
        list.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    private fun findChatList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        fun walk(n: AccessibilityNodeInfo) {
            val cls = n.className?.toString().orEmpty()
            if (n.isScrollable && (cls.contains("RecyclerView") || cls.contains("ListView"))) {
                val r = Rect(); n.getBoundsInScreen(r)
                val a = r.width() * r.height()
                if (a > bestArea) { bestArea = a; best = n }
            }
            for (i in 0 until n.childCount) { n.getChild(i)?.let { walk(it) } }
        }
        walk(root)
        return best
    }

    private fun hideSheet() {
        sheet?.let { runCatching { wm.removeView(it) } }
        sheet = null; sheetList = null; sheetScroll = null; sheetTitle = null
        sheetSignature = ""; rebuilding = false
    }

    /** Remove every window we draw. Keeps the password and the reader on/off choice. */
    private fun hideWindows() {
        hideSheet(); hidePill()
        main.removeCallbacks(watchdog)
        history = emptyList(); lastRows = emptyList()
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("decrypted", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** Hide everything as soon as WhatsApp is no longer the focused app (recents, home, lock screen...). */
    private val watchdog = object : Runnable {
        override fun run() {
            if (sheet == null && pill == null) return
            if (!whatsappInForeground()) { hideWindows(); return }
            main.postDelayed(this, 300)
        }
    }

    private fun whatsappInForeground(): Boolean {
        val ws = windows
        if (!ws.isNullOrEmpty()) {
            for (w in ws) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isFocused || w.isActive)) {
                    val r = w.root ?: return false
                    return r.packageName?.toString() in waPackages
                }
            }
            return false
        }
        val root = rootInActiveWindow
        return root != null && root.packageName?.toString() in waPackages
    }

    // ------------------------------------------------------------ fallback popup (paste + #password#)

    private fun showPopup(msg: String) {
        dismissPopup()
        val tv = TextView(this).apply {
            text = "$msg\n\n(tap to dismiss)"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xF2101018.toInt())
            textSize = 16f
            setPadding(48, 40, 48, 40)
            setOnClickListener { dismissPopup() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; y = 120 }
        wm.addView(tv, lp)
        popup = tv
    }

    private fun dismissPopup() {
        popup?.let { runCatching { wm.removeView(it) } }
        popup = null
    }

    override fun onInterrupt() { dismissPopup(); hideWindows() }

    override fun onDestroy() {
        dismissPopup(); hideWindows()
        sessionPassword = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
