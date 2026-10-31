package com.secureencryptor.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.secureencryptor.app.crypto.CryptoEngine
import com.secureencryptor.app.crypto.SecretStore
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * WhatsApp # mode (light version)
 *
 *  PASSWORD  type  #password#      saved on this phone (encrypted). Same password on both phones.
 *  SEND      type  message#        encrypted with the saved password and sent automatically
 *  READ      tap an encrypted message in the chat -> a small card shows the text
 *  FORGET    type  ##              removes the saved password
 *  ONE-OFF   type  #password,message#   (no saved password needed)
 *  BACKUP    paste an encrypted message and add #  -> the card shows the text
 */
class HashtagAccessibilityService : AccessibilityService() {

    companion object {
        /** After encrypting, tap WhatsApp's send button automatically. */
        const val AUTO_SEND = true
        private const val SEP = '⁣'
        private const val CARD_MS = 12_000L
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private var pwCache: String? = null
    private var pwLoaded = false
    private var password: String?
        get() {
            if (!pwLoaded) { pwCache = SecretStore.getPassword(this); pwLoaded = true }
            return pwCache
        }
        set(v) {
            pwCache = v; pwLoaded = true
            SecretStore.setPassword(this, v)
        }

    private var card: LinearLayout? = null
    private var cardLp: WindowManager.LayoutParams? = null

    private val encryptRe = Regex("""^#([^,#]+),(.+)#$""", RegexOption.DOT_MATCHES_ALL)
    private val unlockRe = Regex("""^#([^,#]+)#$""")
    private val cipherOnlyRe = Regex("""^(RU5DMg[A-Za-z0-9+/=\s]{30,})#$""")
    private val quickRe = Regex("""^([^#]+)#$""", RegexOption.DOT_MATCHES_ALL)
    private val tokenRe = Regex("""RU5DMg[A-Za-z0-9+/=]{30,}""")

    private val waPackages = setOf("com.whatsapp", "com.whatsapp.w4b")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val wm get() = getSystemService(WINDOW_SERVICE) as WindowManager

    private fun say(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTyping(e)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleTap(e)
        }
    }

    // ------------------------------------------------------------ typing triggers

    private fun handleTyping(e: AccessibilityEvent) {
        if (busy) return
        val node = e.source ?: return
        val text = node.text?.toString()?.trim().orEmpty()
        if (!node.isEditable || !text.endsWith("#")) { node.recycle(); return }

        if (text == "##") {
            setField(node, "")
            password = null
            say("Password removed")
            node.recycle(); return
        }

        unlockRe.matchEntire(text)?.let { m ->
            password = m.groupValues[1]
            setField(node, "")
            say("Password saved. Type  message#  to send encrypted. Tap an encrypted message to read it.")
            node.recycle(); return
        }

        cipherOnlyRe.matchEntire(text)?.let { m ->
            setField(node, "")
            decryptAndShow(m.groupValues[1].replace(Regex("\\s"), ""), null)
            node.recycle(); return
        }

        quickRe.matchEntire(text)?.let { m ->
            val pw = password
            if (pw == null) {
                say("No password yet. Type  #password#  first")
                node.recycle(); return
            }
            val plain = m.groupValues[1].trim()
            if (plain.isEmpty()) { node.recycle(); return }
            encryptAndPut(node, pw, plain)
            return
        }

        val enc = encryptRe.matchEntire(text)
        if (enc != null) {
            encryptAndPut(node, enc.groupValues[1], enc.groupValues[2])
            return
        }
        node.recycle()
    }

    private fun encryptAndPut(node: AccessibilityNodeInfo, pw: String, plain: String) {
        busy = true
        worker.execute {
            try {
                val out = CryptoEngine.encryptText(pw, plain)
                main.post {
                    setField(node, out)
                    if (AUTO_SEND) main.postDelayed({ clickSend(0) }, 300)
                }
            } catch (ex: Exception) {
                main.post { say("Error: ${ex.message}") }
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

    /** Tap WhatsApp's send button. If it can't be found the encrypted text simply stays in the box. */
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

    // ------------------------------------------------------------ tap an encrypted message

    private fun handleTap(e: AccessibilityEvent) {
        val src = e.source ?: return
        var token: String? = null
        var rect: Rect? = null

        var n: AccessibilityNodeInfo? = src
        var depth = 0
        while (n != null && depth < 5 && token == null) {
            if (n.isScrollable) break            // never climb up to the whole message list
            val hit = findCipher(n, 0)
            if (hit != null) { token = hit.first; rect = hit.second }
            n = n.parent
            depth++
        }
        if (token == null) {
            val m = tokenRe.find(e.text.joinToString(" "))
            if (m != null) {
                token = m.value
                rect = Rect().also { src.getBoundsInScreen(it) }
            }
        }
        val t = token ?: return
        decryptAndShow(t, rect)
    }

    private fun findCipher(n: AccessibilityNodeInfo, depth: Int): Pair<String, Rect>? {
        val t = n.text?.toString()?.trim()
        if (t != null && !n.isEditable && t.length >= 40 && t.startsWith("RU5DMg")) {
            val r = Rect(); n.getBoundsInScreen(r)
            return Pair(t, r)
        }
        if (depth > 6) return null
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findCipher(c, depth + 1)?.let { return it }
        }
        return null
    }

    private fun decryptAndShow(token: String, rect: Rect?) {
        val pw = password
        worker.execute {
            val out = when {
                pw == null -> "No password yet. Type  #password#  in the chat first."
                else -> try {
                    display(CryptoEngine.decryptText(pw, token))
                } catch (ex: Exception) {
                    "Can't decrypt: wrong password, or not an encrypted message."
                }
            }
            main.post { showCard(out, rect) }
        }
    }

    /** Understands the quoted-reply format of earlier builds. */
    private fun display(plain: String): String {
        if (plain.length > 3 && plain[0] == SEP && plain[1] == 'R') {
            val end = plain.indexOf(SEP, 2)
            if (end > 2) return "(re: ${plain.substring(3, end)})\n${plain.substring(end + 1)}"
        }
        return plain
    }

    // ------------------------------------------------------------ the small result card

    private val hideCard = Runnable { dismissCard() }

    private fun showCard(body: String, anchor: Rect?) {
        dismissCard()
        val dm = resources.displayMetrics
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(0xFF0B141A.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF25D366.toInt())
            }
            setOnClickListener { dismissCard() }
            setOnLongClickListener { copyText(body); true }
        }
        box.addView(TextView(this).apply {
            text = body
            textSize = 16f
            setTextColor(0xFFE9EDEF.toInt())
        })
        box.addView(TextView(this).apply {
            text = "Tap to close  -  hold to copy"
            textSize = 11f
            setTextColor(0xFF8696A0.toInt())
            setPadding(0, dp(6), 0, 0)
        })

        val wantedY = (anchor?.top ?: dp(160)).coerceIn(dp(80), (dm.heightPixels * 0.62).toInt())
        val lp = WindowManager.LayoutParams(
            dm.widthPixels - dp(24), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = wantedY
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(box, lp)
        card = box
        cardLp = lp

        // some phones offset the window (display cutout): measure once and correct
        box.post {
            if (box.isAttachedToWindow && card === box) {
                val loc = IntArray(2)
                box.getLocationOnScreen(loc)
                val diff = loc[1] - wantedY
                if (abs(diff) > 2) {
                    lp.y = wantedY - diff
                    runCatching { wm.updateViewLayout(box, lp) }
                }
            }
        }
        main.postDelayed(hideCard, CARD_MS)
    }

    private fun dismissCard() {
        main.removeCallbacks(hideCard)
        card?.let { runCatching { wm.removeView(it) } }
        card = null; cardLp = null
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("decrypted", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() { dismissCard() }

    override fun onDestroy() {
        dismissCard()
        worker.shutdownNow()
        super.onDestroy()
    }
}
