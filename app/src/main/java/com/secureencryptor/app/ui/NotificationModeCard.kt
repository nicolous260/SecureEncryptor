package com.secureencryptor.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.secureencryptor.app.notif.SecretStore
import com.secureencryptor.app.ui.theme.*

private fun listenerOn(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
private fun postingOn(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

@Composable
fun NotificationModeCard() {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(listenerOn(ctx)) }
    var posting by remember { mutableStateOf(postingOn(ctx)) }
    var hasPw by remember { mutableStateOf(SecretStore.getPassword(ctx) != null) }
    var pw by remember { mutableStateOf("") }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) {
                access = listenerOn(ctx); posting = postingOn(ctx); hasPw = SecretStore.getPassword(ctx) != null
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { posting = postingOn(ctx) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Notification mode", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            Text(
                "Encrypted WhatsApp messages are decrypted in the notification. Reply from the notification " +
                    "(sent encrypted through WhatsApp). All decrypted chats are kept in the app.",
                fontSize = 13.sp, color = TextSecondary
            )

            OutlinedTextField(
                value = pw,
                onValueChange = { pw = it },
                label = { Text(if (hasPw) "New password (one is saved)" else "Shared password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (pw.isNotBlank() && !pw.contains('#') && !pw.contains(',')) {
                            SecretStore.setPassword(ctx, pw); hasPw = true; pw = ""
                            Toast.makeText(ctx, "Password saved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(ctx, "Password can't be empty or contain # or ,", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                    modifier = Modifier.weight(1f)
                ) { Text("Save password") }
                if (hasPw) OutlinedButton(
                    onClick = { SecretStore.setPassword(ctx, null); hasPw = false },
                    modifier = Modifier.weight(1f)
                ) { Text("Forget") }
            }

            Text(
                "Password: " + (if (hasPw) "saved" else "not set") +
                    "\nNotification access: " + (if (access) "ON" else "OFF") +
                    "\nShow notifications: " + (if (posting) "allowed" else "blocked"),
                fontSize = 13.sp, color = if (access && posting && hasPw) SecondaryTeal else TextMuted
            )

            if (!access) Button(
                onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Allow notification access") }

            if (!posting) Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Allow notifications") }

            OutlinedButton(
                onClick = { ctx.startActivity(Intent(ctx, ChatsActivity::class.java)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open secure messages") }
        }
    }
}
