package com.secureencryptor.app.notif

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the "Reply" typed in the notification: encrypts it and sends it through WhatsApp. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conv = intent.getStringExtra("conv") ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply_text")?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try { Replier.send(app, conv, text) } finally { pending.finish() }
        }.start()
    }
}
