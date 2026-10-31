package com.secureencryptor.app.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secureencryptor.app.notif.ChatMsg
import com.secureencryptor.app.notif.ChatStore
import com.secureencryptor.app.notif.Replier
import com.secureencryptor.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Decrypted conversations received through WhatsApp notifications. */
class ChatsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra("conv")
        setContent {
            SecureEncryptorTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ChatsRoot(start) { finish() }
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFmt = SimpleDateFormat("d MMM", Locale.getDefault())

private fun stamp(t: Long): String {
    val now = System.currentTimeMillis()
    return if (now - t < 20 * 3600 * 1000L) timeFmt.format(Date(t)) else dayFmt.format(Date(t))
}

@Composable
fun ChatsRoot(start: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf(start) }
    val version = ChatStore.version.value
    BackHandler(enabled = selected != null) { selected = null }
    Column(
        Modifier
            .fillMaxSize()
            .background(BackgroundDeep)
            .safeDrawingPadding()
    ) {
        val sel = selected
        if (sel == null) ConversationList(ctx, version, onOpen = { selected = it }, onClose = onClose)
        else Conversation(ctx, sel, version, onBack = { selected = null })
    }
}

@Composable
private fun TopBar(title: String, left: String, onLeft: () -> Unit, right: String? = null, onRight: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CardSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onLeft) { Text(left, color = SecondaryTeal, fontWeight = FontWeight.Bold) }
        Text(
            title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        if (right != null) TextButton(onClick = onRight) { Text(right, color = SecondaryTeal, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ConversationList(ctx: Context, version: Int, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val convs = remember(version) { ChatStore.conversations(ctx) }
    TopBar("Secure messages", "BACK", onClose, "CLEAR") { ChatStore.clear(ctx) }
    if (convs.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "No messages yet.\nEncrypted WhatsApp messages that arrive as notifications appear here, decrypted.",
                color = TextSecondary, fontSize = 14.sp
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(convs, key = { it.first }) { (title, last) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(title) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                    Text(
                        (if (last.mine) "You: " else "") + last.text.replace('\n', ' '),
                        color = TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(stamp(last.time), color = TextMuted, fontSize = 12.sp)
            }
            HorizontalDivider(color = CardBorder)
        }
    }
}

@Composable
private fun Conversation(ctx: Context, conv: String, version: Int, onBack: () -> Unit) {
    val msgs = remember(version, conv) { ChatStore.messages(ctx, conv) }
    TopBar(conv, "BACK", onBack)
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.scrollToItem(msgs.size - 1) }

    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(msgs) { m -> Bubble(m) }
    }

    var text by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .background(CardSurface)
            .imePadding()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Reply (encrypted)") },
            maxLines = 4
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                val t = text.trim()
                if (t.isNotEmpty()) {
                    text = ""
                    val app = ctx.applicationContext
                    Thread { Replier.send(app, conv, t) }.start()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = SecondaryTeal)
        ) { Text("Send") }
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = if (m.mine) Color(0xFF005C4B) else Color(0xFF202C33),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(m.text, color = Color(0xFFE9EDEF), fontSize = 16.sp)
                Text(
                    stamp(m.time), color = Color(0xFF8696A0), fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}
