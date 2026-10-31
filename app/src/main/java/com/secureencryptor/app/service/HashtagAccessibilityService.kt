package com.secureencryptor.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.secureencryptor.app.crypto.CryptoEngine
import java.util.concurrent.Executors

/**
 * Watches the WhatsApp message box.
 *
 *  ENCRYPT: type  #password,your secret text#   -> box becomes ciphertext, then press send
 *  DECRYPT: paste received ciphertext, then type  #password#  right after it
 *           -> plaintext shown in a private overlay (tap to dismiss); box is cleared, never sent.
 *
 * The closing '#' is the trigger, so nothing fires while you are still typing.
 */
class HashtagAccessibilityService : AccessibilityService() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    private var overlay: TextView? = null

    private val encryptRe = Regex("""^#([^,#]+),(.+)#$""", RegexOption.DOT_MATCHES_ALL)
    private val decryptRe = Regex("""^([A-Za-z0-9+/=\s]{40,})#([^#]+)#$""")

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || busy) return
        val node = e.source ?: return
        val text = node.text?.toString()?.trim().orEmpty()
        if (!node.isEditable || !text.endsWith("#")) { node.recycle(); return }

        val enc = encryptRe.matchEntire(text)
        val dec = if (enc == null) decryptRe.matchEntire(text) else null
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
                    } catch (ex: Exception) {
                        "Wrong password or not an encrypted message"
                    }
                    main.post { setField(node, ""); showOverlay(plain) }
                }
            } catch (ex: Exception) {
                main.post { showOverlay("Error: ${ex.message}") }
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

    private fun showOverlay(msg: String) {
        dismissOverlay()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            text = "$msg\n\n(tap to dismiss)"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xF2101018.toInt())
            textSize = 16f
            setPadding(48, 40, 48, 40)
            setOnClickListener { dismissOverlay() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; y = 120 }
        wm.addView(tv, lp)
        overlay = tv
    }

    private fun dismissOverlay() {
        overlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        overlay = null
    }

    override fun onInterrupt() = dismissOverlay()

    override fun onDestroy() {
        dismissOverlay()
        worker.shutdownNow()
        super.onDestroy()
    }
}
