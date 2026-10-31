package com.secureencryptor.app.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.secureencryptor.app.ui.theme.*

private fun isServiceOn(ctx: Context): Boolean =
    Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.contains("${ctx.packageName}/") == true

@Composable
fun WhatsAppModeCard() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(isServiceOn(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_RESUME) enabled = isServiceOn(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("WhatsApp # mode", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            Text(
                "1. Type #password#  (once; same password for both people)\n" +
                "2. Type message#  -> encrypted and sent automatically\n" +
                "3. The chat appears decrypted in a WhatsApp-style reader\n" +
                "4. Swipe a message right to reply, long-press to copy\n" +
                "Type ## to lock",
                fontSize = 13.sp, color = TextSecondary
            )
            Text(
                if (enabled) "Status: ON" else "Status: OFF — enable \"Secure Encryptor\" in Accessibility settings",
                fontSize = 13.sp, color = if (enabled) SecondaryTeal else TextMuted
            )
            Button(
                onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (enabled) "Open Accessibility settings" else "Enable WhatsApp # mode") }
        }
    }
}
