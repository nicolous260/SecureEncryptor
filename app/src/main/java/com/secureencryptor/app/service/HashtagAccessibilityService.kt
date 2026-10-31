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

/**
 * WhatsApp # mode
 *
 *  PASSWORD   type  #password#      saved in memory (type again with a new word to change it). Opens the reader.
 *  SEND       type  your text#      encrypted with the saved password and sent automatically
 *  READ       tap the green 🔓 button (right edge) -> panel with the decrypted messages on screen
 *  LOCK       type  ##   or tap "Lock" in the panel (also auto-locks after 2 h idle)
 *  ONE-OFF    type  #password,text#   (no saved password needed)
 */
class HashtagAccessibilityService : AccessibilityService() {

    private data class Item(val text: String, val rect: Rect)
    private data class Msg(val plain: String, val mine: Boolean)

    companion object {
        /** After encrypting, tap WhatsApp's send button automatically. */
        const val AUTO_SEND = true
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private var sessionPassword: String? = null
    private var lastUse = 0L
    private var sheetWanted = false

    // ciphertext -> plaintext ("" = failed / other password). Main thread only.
    private val results = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 200
    }
    private val pending = HashSet<String>()
    private var lastItems: List<Item> = emptyList()
    private var scanScheduled = false

    private var sheet: LinearLayout? = null
    private var sheetList: LinearLayout? = null
    private var sheetScroll: ScrollView? = null
    private var sheetTitle: TextView? = null
    private var sheetSignature = ""
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
            results.clear(); pending.clear(); sheetSignature = ""
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
        results.clear(); pending.clear(); lastItems = emptyList(); sheetSignature = ""
        hideAll()
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
        if (!whatsappInForeground()) { hideAll(); return }
        val root = rootInActiveWindow
        if (root == null) { hideAll(); return }

        val items = ArrayList<Item>()
        collect(root, items)
        lastItems = items

        for (item in items) {
            val key = item.text
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

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Item>) {
        val t = n.text?.toString()
        if (t != null && t.length >= 40 && t.startsWith("RU5DMg") && !n.isEditable && n.isVisibleToUser) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (!r.isEmpty) out.add(Item(t.trim(), r))
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collect(c, out)
        }
    }

    private fun decryptedMessages(): List<Msg> {
        val w = resources.displayMetrics.widthPixels
        return lastItems
            .sortedBy { it.rect.top }
            .filter { !results[it.text].isNullOrEmpty() }
            .distinctBy { it.text }
            .map { Msg(results[it.text]!!, it.rect.left > w * 0.10) }
    }

    // ------------------------------------------------------------ UI: pill + panel

    private fun refreshUi() {
        if (sessionPassword == null || !whatsappInForeground()) { hideAll(); return }
        val msgs = decryptedMessages()
        if (msgs.isNotEmpty()) lastUse = System.currentTimeMillis()
        if (sheetWanted) {
            hidePill(); showSheet(msgs)
        } else if (msgs.isNotEmpty()) {
            hideSheet(); showPill(msgs.size)
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

    private fun showSheet(msgs: List<Msg>) {
        if (sheet == null) createSheet()
        sheetTitle?.text = if (msgs.isEmpty()) "🔓 No encrypted messages in view" else "🔓 Decrypted · ${msgs.size}"
        val sig = msgs.joinToString("\u0001") { (if (it.mine) "M" else "T") + it.plain }
        if (sig == sheetSignature) return
        sheetSignature = sig
        val list = sheetList ?: return
        list.removeAllViews()
        for (m in msgs) {
            val tv = TextView(this).apply {
                text = m.plain
                textSize = 16f
                setTextColor(0xFFE9EDEF.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
                background = GradientDrawable().apply {
                    setColor(if (m.mine) 0xFF0B5D4B.toInt() else 0xFF2A343B.toInt())
                    cornerRadius = dp(14).toFloat()
                }
                setOnClickListener { copyText(m.plain) }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (m.mine) Gravity.END else Gravity.START
                bottomMargin = dp(6)
            }
            list.addView(tv, lp)
        }
        sheetScroll?.post { sheetScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun createSheet() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(10))
            val r = dp(20).toFloat()
            background = GradientDrawable().apply {
                setColor(0xF2121A1F.toInt())
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
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
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.42).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        wm.addView(root, lp)
        sheet = root; sheetList = list; sheetScroll = scroll; sheetTitle = title
    }

    private fun hideSheet() {
        sheet?.let { runCatching { wm.removeView(it) } }
        sheet = null; sheetList = null; sheetScroll = null; sheetTitle = null; sheetSignature = ""
    }

    private fun hideAll() {
        sheetWanted = false
        hideSheet(); hidePill()
        main.removeCallbacks(watchdog)
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
            if (!whatsappInForeground()) { hideAll(); return }
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

    override fun onInterrupt() { dismissPopup(); hideAll() }

    override fun onDestroy() {
        dismissPopup(); hideAll()
        sessionPassword = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
