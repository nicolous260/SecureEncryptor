package com.nicolous.statussaver

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile

/** One simple screen: three steps to set up, nothing else. */
class MainActivity : Activity() {

    private val bg = 0xFF0B141A.toInt()
    private val card = 0xFF161E2E.toInt()
    private val green = 0xFF128C7E.toInt()

    private lateinit var stepAccess: TextView
    private lateinit var stepFolder: TextView
    private lateinit var lastSaved: TextView
    private lateinit var triggerBox: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        col.addView(text("Status Saver", 26f, 0xFFE9EDEF.toInt(), bold = true))
        col.addView(text("Watch a WhatsApp status, tap Reply, type your trigger (for example nic#). " +
            "The status is saved to your gallery.", 14f, 0xFF8696A0.toInt()).apply { setPadding(0, dp(6), 0, dp(18)) })

        // Step 1
        stepAccess = text("", 13f, 0xFF8696A0.toInt())
        col.addView(section("1. Turn on the trigger", stepAccess, button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }))

        // Step 2
        stepFolder = text("", 13f, 0xFF8696A0.toInt())
        val hint = text("In the picker the folder opens by itself. Tap  Use this folder  then  Allow. " +
            "If it does not open there: Android > media > com.whatsapp > WhatsApp > Media > .Statuses",
            12f, 0xFF8696A0.toInt())
        col.addView(section("2. Choose the status folder", stepFolder, hint,
            button("Choose WhatsApp folder") { pick(false) },
            button("Choose WhatsApp Business folder", secondary = true) { pick(true) }))

        // Step 3
        triggerBox = EditText(this).apply {
            setText(Store.trigger(this@MainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8696A0.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(12))
            background = GradientDrawable().apply { setColor(0xFF0F172A.toInt()); cornerRadius = dp(10).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        col.addView(section("3. Your trigger word", text("You type this word followed by #", 13f, 0xFF8696A0.toInt()),
            triggerBox,
            button("Save trigger") {
                val w = triggerBox.text.toString().trim().lowercase()
                if (w.isNotEmpty() && w.all { it.isLetterOrDigit() }) {
                    Store.setTrigger(this, w)
                    Toast.makeText(this, "Trigger saved: $w#", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Use letters and numbers only", Toast.LENGTH_SHORT).show()
                }
            }))

        lastSaved = text("", 13f, 0xFF8696A0.toInt()).apply { setPadding(dp(4), dp(14), dp(4), 0) }
        col.addView(lastSaved)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true
        stepAccess.text = if (on) "Status: ON" else "Status: OFF. Find Status Saver in the list and turn it on."
        stepAccess.setTextColor(if (on) 0xFF25D366.toInt() else 0xFF8696A0.toInt())
        val wa = Store.tree(this, "com.whatsapp") != null
        val biz = Store.tree(this, "com.whatsapp.w4b") != null
        stepFolder.text = "WhatsApp folder: " + (if (wa) "set" else "not set") +
            "\nWhatsApp Business folder: " + (if (biz) "set" else "not set")
        lastSaved.text = Store.last(this)?.let { "Last saved: $it" } ?: "Nothing saved yet."
    }

    private fun pick(business: Boolean) {
        val pkg = if (business) "com.whatsapp.w4b" else "com.whatsapp"
        val app = if (business) "WhatsApp Business" else "WhatsApp"
        val rel = if (Build.VERSION.SDK_INT >= 30) "Android/media/$pkg/$app/Media/.Statuses" else "$app/Media/.Statuses"
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        startActivityForResult(i, if (business) 2 else 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK || (requestCode != 1 && requestCode != 2)) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { /* some pickers grant temporary access only */ }
        val pkg = if (requestCode == 2) "com.whatsapp.w4b" else "com.whatsapp"
        Store.setTree(this, pkg, uri.toString())
        val name = DocumentFile.fromTreeUri(this, uri)?.name
        Toast.makeText(
            this,
            if (name == ".Statuses") "Folder saved" else "Saved, but this is \"$name\". You need the .Statuses folder",
            Toast.LENGTH_LONG
        ).show()
        onResume()
    }

    // ---- small view helpers

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(label: String, secondary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(if (secondary) 0xFF1F2C34.toInt() else green)
            cornerRadius = dp(12).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun section(title: String, vararg views: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(16).toFloat() }
        addView(text(title, 16f, 0xFFE9EDEF.toInt(), bold = true))
        for (v in views) {
            addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
    }
}
