package com.secureencryptor.app.notif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.secureencryptor.app.crypto.CryptoEngine
import java.util.concurrent.Executors

/**
 * Reads WhatsApp notifications. When one contains an encrypted message it is decrypted with the saved
 * password, stored in the in-app history and replaced by a clean notification with an encrypted-reply action.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        /** Remove WhatsApp's own (unreadable) notification once ours is posted. */
        const val HIDE_ORIGINAL = true
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val waPackages = setOf("com.whatsapp", "com.whatsapp.w4b")

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in waPackages) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val key = sbn.key
        worker.execute { try { handle(key, n) } catch (e: Exception) { /* ignore */ } }
    }

    private class Incoming(val sender: String?, val text: String, val time: Long)

    private fun handle(key: String, n: Notification) {
        val pw = SecretStore.getPassword(this) ?: return

        val extras = n.extras
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)

        val incoming = ArrayList<Incoming>()
        var title = (style?.conversationTitle?.toString() ?: rawTitle) ?: "Chat"
        title = title.replace(Regex("""\s*\(\d+ (new )?messages?\)\s*$"""), "").trim().take(80).ifEmpty { "Chat" }

        if (style != null) {
            for (m in style.messages) {
                val t = m.text?.toString() ?: continue
                incoming.add(Incoming(m.person?.name?.toString(), t, m.timestamp))
            }
        } else {
            val t = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (t != null) incoming.add(Incoming(rawTitle, t, n.`when`))
        }
        if (incoming.none { it.text.startsWith("RU5DMg") }) return

        var decryptedAny = false
        for (m in incoming) {
            val body = if (m.text.startsWith("RU5DMg")) {
                try { displayText(CryptoEngine.decryptText(pw, m.text.trim())) } catch (e: Exception) { null }
            } else m.text
            if (body == null) continue
            if (m.text.startsWith("RU5DMg")) decryptedAny = true
            ChatStore.add(this, title, ChatMsg(m.sender ?: title, body, m.time, false))
        }
        if (!decryptedAny) return

        val action = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        if (action != null) ReplyRegistry.replies[title] = ReplyRegistry.Reply(action.actionIntent, action.remoteInputs)
        n.contentIntent?.let { ReplyRegistry.opens[title] = it }

        NotificationPoster.post(this, title)
        if (HIDE_ORIGINAL) cancelNotification(key)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
