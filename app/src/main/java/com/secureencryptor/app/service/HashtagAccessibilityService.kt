package com.secureencryptor.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.secureencryptor.app.crypto.CryptoEngine
import java.util.concurrent.Executors

/**
 * WhatsApp # mode
 *
 *  SEND    type  #password,your secret text#  -> box becomes ciphertext, press send
 *  UNLOCK  type  #password#                   -> encrypted messages on screen are decrypted in place
 *  LOCK    type  ##                           -> forget the password (also auto-locks after 15 min idle)
 *  (fallback) paste ciphertext + #password#   -> plaintext shown in a popup
 */
class HashtagAccessibilityService : AccessibilityService() {

    private data class Item(val text: String, val rect: Rect)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private var sessionPassword: String? = null
    private var lastUse = 0L

    // ciphertext -> plaintext ("" = failed / other password). Main thread only.
    private val results = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 200
    }
    private val pending = HashSet<String>()
    private var lastItems: List<Item> = emptyList()
    private var scanScheduled = false

    private var layer: FrameLayout? = null
    private var popup: TextView? = null

    private val encryptRe = Regex("""^#([^,#]+),(.+)#$""", RegexOption.DOT_MATCHES_ALL)
    private val unlockRe = Regex("""^#([^,#]+)#$""")
    private val pasteDecryptRe = Regex("""^([A-Za-z0-9+/=\s]{40,})#([^#]+)#$""")

    private val waPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val autoLockMs = 15 * 60 * 1000L

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            handleTyping(e)
        } else {
            scheduleScan()
        }
    }

    // ---------------------------------------------------------------- typing triggers

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
            results.clear(); pending.clear()
            setField(node, "")
            Toast.makeText(this, "Unlocked. Type ## to lock", Toast.LENGTH_SHORT).show()
            node.recycle()
            scheduleScan()
            return
        }

        val enc = encryptRe.matchEntire(text)
        val dec = if (enc == null) pasteDecryptRe.matchEntire(text) else null
        if (enc == null && dec == null) { node.recycle(); return }

        busy = true
        worker.execute {
            try {
                if (enc != null) {
                    val out = CryptoEngine.encryptText(enc.groupValues[1], enc.groupValues[2])
                    main.post { setField(node, out) }
                } else {
                    val plain = try {
                        CryptoEngine.decryptText(dec!!.groupValues[2], dec.groupValues[1])
                    } catch (ex: Exception) { "Wrong password or not an encrypted message" }
                    main.post { setField(node, ""); showPopup(plain) }
                }
            } catch (ex: Exception) {
                main.post { showPopup("Error: ${ex.message}") }
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

    // ---------------------------------------------------------------- auto-decrypt on screen

    private fun lock(announce: Boolean) {
        sessionPassword = null
        results.clear(); pending.clear(); lastItems = emptyList()
        clearLayer()
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
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() !in waPackages) { clearLayer(); return }

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
                    if (sessionPassword == pw) { results[key] = plain; render() }
                }
            }
        }
        render()
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

    private fun render() {
        val shown = lastItems.filter { !results[it.text].isNullOrEmpty() }
        if (shown.isEmpty()) { clearLayer(); return }
        lastUse = System.currentTimeMillis()

        val l = layer ?: createLayer()
        val loc = IntArray(2)
        if (l.isAttachedToWindow) l.getLocationOnScreen(loc)
        l.removeAllViews()
        for (item in shown) {
            val pad = 6
            val tv = TextView(this).apply {
                text = results[item.text]
                setTextColor(Color.WHITE)
                textSize = 15f
                setBackgroundColor(0xFF0B3D2E.toInt())
                setPadding(12, 6, 12, 6)
                gravity = Gravity.TOP or Gravity.START
            }
            val lp = FrameLayout.LayoutParams(item.rect.width() + pad * 2, item.rect.height() + pad * 2)
            lp.leftMargin = item.rect.left - pad - loc[0]
            lp.topMargin = item.rect.top - pad - loc[1]
            l.addView(tv, lp)
        }
    }

    private fun createLayer(): FrameLayout {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val fl = FrameLayout(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(fl, lp)
        layer = fl
        main.postDelayed({ render() }, 120)   // re-render once attached so offsets are exact
        main.postDelayed(watchdog, 1000)
        return fl
    }

    /** Remove the overlay as soon as WhatsApp is no longer the active window. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (layer == null) return
            val root = rootInActiveWindow
            if (root == null || root.packageName?.toString() !in waPackages) { clearLayer(); return }
            main.postDelayed(this, 1000)
        }
    }

    private fun clearLayer() {
        main.removeCallbacks(watchdog)
        layer?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        layer = null
    }

    // ---------------------------------------------------------------- fallback popup

    private fun showPopup(msg: String) {
        dismissPopup()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
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
        popup?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        popup = null
    }

    override fun onInterrupt() { dismissPopup(); clearLayer() }

    override fun onDestroy() {
        dismissPopup(); clearLayer()
        sessionPassword = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
