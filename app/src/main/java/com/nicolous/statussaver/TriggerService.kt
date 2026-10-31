package com.nicolous.statussaver

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * While watching a WhatsApp status, type  <trigger>#  (default nic#) in the reply box:
 * the box is cleared and the most recent cached status is saved to the gallery.
 * A small card shows what was saved, with a button to save the next older one if it was the wrong one.
 */
class TriggerService : AccessibilityService() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var card: LinearLayout? = null
    private var candidates: List<Saver.Item> = emptyList()
    private var index = 0
    private val hideCard = Runnable { dismissCard() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val wm get() = getSystemService(WINDOW_SERVICE) as WindowManager

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        val node = e.source ?: return
        val typed = node.text?.toString()?.trim()?.lowercase().orEmpty()
        if (!node.isEditable || typed != Store.trigger(this) + "#") { node.recycle(); return }

        val pkg = e.packageName?.toString() ?: "com.whatsapp"
        node.refresh()
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        })
        node.recycle()
        worker.execute { start(pkg) }
    }

    private fun start(pkg: String) {
        val items = try { Saver.list(this, pkg) } catch (e: Exception) { null }
        if (items == null) {
            toast("Open Status Saver and choose the status folder first")
            return
        }
        if (items.isEmpty()) {
            toast("No statuses found. Watch the status first, then try again")
            return
        }
        candidates = items
        index = 0
        saveCurrent()
    }

    private fun saveCurrent() {
        val item = candidates.getOrNull(index) ?: return
        val already = Store.isSaved(this, item.name)
        var ok = true
        if (!already) {
            ok = Saver.save(this, item) != null
            if (ok) Store.addSaved(this, item.name)
        }
        val bmp = Saver.thumb(this, item)
        val age = DateUtils.getRelativeTimeSpanString(
            item.doc.lastModified(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        val msg = when {
            !ok -> "Could not save this one"
            already -> "Already saved"
            else -> "Saved to your gallery (StatusSaver)"
        } + "\nStatus file from $age"
        val more = index + 1 < candidates.size
        main.post { showCard(bmp, msg, more) }
    }

    // ------------------------------------------------------------ result card

    private fun showCard(bmp: Bitmap?, message: String, hasOlder: Boolean) {
        dismissCard()
        val dm = resources.displayMetrics
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF0B141A.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xFF25D366.toInt())
            }
        }
        if (bmp != null) {
            box.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply { setColor(0xFF1F2C34.toInt()); cornerRadius = dp(10).toFloat() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
        }
        box.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(0xFFE9EDEF.toInt())
            setPadding(0, dp(8), 0, dp(8))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (hasOlder) {
            row.addView(button("Not this one: save older") {
                main.removeCallbacks(hideCard)
                index++
                worker.execute { saveCurrent() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        row.addView(button("Close") { dismissCard() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (hasOlder) 0.6f else 1f))
        box.addView(row)

        val lp = WindowManager.LayoutParams(
            dm.widthPixels - dp(32), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(70)
        }
        wm.addView(box, lp)
        card = box
        main.postDelayed(hideCard, 10_000)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply { setColor(0xFF128C7E.toInt()); cornerRadius = dp(10).toFloat() }
        setOnClickListener { onClick() }
        val m = dp(3)
        (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(m, m, m, m)
    }

    private fun dismissCard() {
        main.removeCallbacks(hideCard)
        card?.let { runCatching { wm.removeView(it) } }
        card = null
    }

    override fun onInterrupt() { dismissCard() }

    override fun onDestroy() {
        dismissCard()
        worker.shutdownNow()
        super.onDestroy()
    }
}
