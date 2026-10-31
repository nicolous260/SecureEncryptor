package com.secureencryptor.app.notif

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.secureencryptor.app.R
import com.secureencryptor.app.crypto.CryptoEngine
import com.secureencryptor.app.ui.ChatsActivity
import java.util.concurrent.ConcurrentHashMap

/** WhatsApp's own "reply" action of the last notification of each chat (kept in memory). */
object ReplyRegistry {
    class Reply(val intent: PendingIntent, val inputs: Array<RemoteInput>)

    val replies = ConcurrentHashMap<String, Reply>()
    val opens = ConcurrentHashMap<String, PendingIntent>()
}

private const val SEP = '⁣'

/** Turns the decrypted text into what we show; understands the quoted-reply format of the reader. */
fun displayText(plain: String): String {
    if (plain.length > 3 && plain[0] == SEP && plain[1] == 'R') {
        val end = plain.indexOf(SEP, 2)
        if (end > 2) return "(re: ${plain.substring(3, end)})\n${plain.substring(end + 1)}"
    }
    return plain
}

fun toast(ctx: Context, msg: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show() }
}

object NotificationPoster {
    const val CHANNEL = "secure_messages"

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Secure messages", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, conv: String) {
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(app)

        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
        for (m in ChatStore.messages(app, conv).takeLast(10)) {
            style.addMessage(m.text, m.time, if (m.mine) me else Person.Builder().setName(m.sender).build())
        }

        val id = conv.hashCode()
        val flagsImm = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(
            app, id, Intent(app, ChatsActivity::class.java).putExtra("conv", conv).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flagsImm
        )

        val remoteInput = androidx.core.app.RemoteInput.Builder("reply_text").setLabel("Reply (encrypted)").build()
        val replyPi = PendingIntent.getBroadcast(
            app, id, Intent(app, ReplyReceiver::class.java).putExtra("conv", conv),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        )
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_launcher_foreground, "Reply", replyPi)
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(false)
            .build()

        val b = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(replyAction)
        ReplyRegistry.opens[conv]?.let { b.addAction(0, "WhatsApp", it) }

        NotificationManagerCompat.from(app).notify(id, b.build())
    }
}

object Replier {
    /** Encrypts [text] and sends it through WhatsApp's own reply action. Returns true if it was handed to WhatsApp. */
    fun send(ctx: Context, conv: String, text: String): Boolean {
        val app = ctx.applicationContext
        val pw = SecretStore.getPassword(app)
        if (pw == null) { toast(app, "Set a password first"); return false }
        val enc = CryptoEngine.encryptText(pw, text)
        ChatStore.add(app, conv, ChatMsg("You", text, System.currentTimeMillis(), true))

        var sent = false
        val r = ReplyRegistry.replies[conv]
        if (r != null) {
            try {
                val intent = Intent()
                val bundle = Bundle()
                for (ri in r.inputs) bundle.putCharSequence(ri.resultKey, enc)
                RemoteInput.addResultsToIntent(r.inputs, intent, bundle)
                r.intent.send(app, 0, intent)
                sent = true
            } catch (e: Exception) {
                sent = false
            }
        }
        if (!sent) {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("encrypted", enc))
            toast(app, "Could not send directly. Encrypted text copied, paste it in WhatsApp")
        }
        NotificationPoster.post(app, conv)
        return sent
    }
}
