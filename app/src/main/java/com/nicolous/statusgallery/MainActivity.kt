@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.nicolous.statusgallery

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val BG = Color(0xFF0B141A)
private val SURFACE = Color(0xFF111B21)
private val CARD = Color(0xFF1F2C34)
private val ACCENT = Color(0xFF00A884)
private val MUTED = Color(0xFF8696A0)

private enum class Tab(val label: String, val kind: Kind?) {
    PICTURES("Pictures", Kind.IMAGE),
    VIDEOS("Videos", Kind.VIDEO),
    AUDIO("Audio", Kind.AUDIO),
    SAVED("Saved", null)
}

class MainActivity : ComponentActivity() {
    private var pendingPkg: String? = null
    private var tick by mutableIntStateOf(0)

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        val pkg = pendingPkg
        if (r.resultCode == RESULT_OK && uri != null && pkg != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            Store.setTree(this, pkg, uri.toString())
            tick++
        }
    }

    private fun pick(pkg: String) {
        pendingPkg = pkg
        val app = if (pkg == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp"
        val rel = if (Build.VERSION.SDK_INT >= 30) "Android/media/$pkg/$app/Media/.Statuses" else "$app/Media/.Statuses"
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
            )
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        picker.launch(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.parseColor("#0B141A")
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.parseColor("#0B141A")
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = ACCENT, background = BG, surface = SURFACE, onSurface = Color.White
                )
            ) {
                Root(tick) { pick(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }
}

private fun dayLabel(t: Long): String {
    if (t <= 0L) return "Earlier"
    val day = 86_400_000L
    fun d(ms: Long) = (ms + TimeZone.getDefault().getOffset(ms)) / day
    val diff = d(System.currentTimeMillis()) - d(t)
    return when {
        diff <= 0L -> "Today"
        diff == 1L -> "Yesterday"
        else -> SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(t))
    }
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Root(tick: Int, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<Data?>(null) }
    var tab by remember { mutableStateOf(Tab.PICTURES) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<Media>?>(null) }

    LaunchedEffect(tick, reload) {
        data = withContext(Dispatchers.IO) { Scanner.loadAll(ctx) }
        refreshing = false
    }

    val d = data
    fun listFor(t: Tab): List<Media> =
        if (d == null) emptyList() else if (t.kind == null) d.saved else d.status.filter { it.kind == t.kind }

    val items = remember(d, tab) { listFor(tab) }

    fun download(list: List<Media>) {
        if (busy) return
        val todo = list.filter { !it.saved }
        if (todo.isEmpty()) {
            Toast.makeText(ctx, "Already downloaded", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { todo.count { Downloader.save(ctx, it) != null } }
            busy = false
            selected = emptySet()
            reload++
            Toast.makeText(
                ctx,
                if (ok == todo.size) "Downloaded $ok" else "Downloaded $ok of ${todo.size}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun delete(list: List<Media>) {
        scope.launch {
            val n = withContext(Dispatchers.IO) { list.count { Downloader.delete(ctx, it) } }
            selected = emptySet()
            viewer = null
            reload++
            Toast.makeText(ctx, "Deleted $n", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(m: Media) {
        scope.launch {
            val uri = withContext(Dispatchers.IO) { Downloader.save(ctx, m) }
            if (uri == null) {
                Toast.makeText(ctx, "Could not share", Toast.LENGTH_SHORT).show()
                return@launch
            }
            reload++
            val i = Intent(Intent.ACTION_SEND).apply {
                type = Downloader.mime(m.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(i, "Share"))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BG)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ACCENT)
            }
        } else if (!d.hasFolder) {
            Setup(onPick)
        } else {
            // header
            if (selected.isEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Status Gallery", fontSize = 22.sp, color = Color.White, modifier = Modifier.weight(1f))
                    if (tab != Tab.SAVED && items.any { !it.saved }) {
                        IconButton(onClick = { download(items) }) {
                            Icon(Icons.Default.Download, contentDescription = "Download all", tint = ACCENT)
                        }
                    }
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                    IconButton(onClick = { onPick("com.whatsapp") }) {
                        Icon(Icons.Default.Folder, contentDescription = "Change folder", tint = MUTED)
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(CARD)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selected = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                    }
                    Text("${selected.size} selected", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { selected = items.map { it.key }.toSet() }) {
                        Icon(Icons.Default.SelectAll, contentDescription = "Select all", tint = Color.White)
                    }
                    if (tab != Tab.SAVED) {
                        IconButton(onClick = { download(items.filter { it.key in selected }) }) {
                            Icon(Icons.Default.Download, contentDescription = "Download", tint = ACCENT)
                        }
                    } else {
                        IconButton(onClick = { confirmDelete = items.filter { it.key in selected } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF5350))
                        }
                    }
                }
            }

            TabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = BG,
                contentColor = ACCENT
            ) {
                Tab.values().forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t; selected = emptySet() },
                        text = {
                            Text(
                                "${t.label} ${listFor(t).size}",
                                fontSize = 13.sp,
                                maxLines = 1,
                                color = if (tab == t) ACCENT else MUTED
                            )
                        }
                    )
                }
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ACCENT, trackColor = CARD)

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (tab == Tab.SAVED) "Nothing downloaded yet."
                        else "No ${tab.label.lowercase()} statuses.\nView statuses in WhatsApp first, then refresh.",
                        color = MUTED, textAlign = TextAlign.Center
                    )
                }
            } else {
                val groups = remember(items) { items.groupBy { dayLabel(it.modified) } }
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { refreshing = true; reload++ },
                    modifier = Modifier.fillMaxSize()
                ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    groups.forEach { (label, list) ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h_$label") {
                            Text(
                                label, color = MUTED, fontSize = 13.sp,
                                modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp)
                            )
                        }
                        items(list, key = { it.key }) { m ->
                            Cell(
                                m = m,
                                done = m.saved || m.name in d.savedNames,
                                selected = m.key in selected,
                                onClick = {
                                    if (selected.isNotEmpty()) {
                                        selected = if (m.key in selected) selected - m.key else selected + m.key
                                    } else viewer = items.indexOf(m)
                                },
                                onLong = { selected = selected + m.key }
                            )
                        }
                    }
                }
                }
            }
        }
    }

    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${list.size} item${if (list.size > 1) "s" else ""}?") },
            text = { Text("They will be removed from your gallery. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; delete(list) }) {
                    Text("Delete", color = Color(0xFFEF5350))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    val v = viewer
    if (v != null && v in items.indices && d != null) {
        Viewer(
            items = items,
            start = v,
            savedNames = d.savedNames,
            onDismiss = { viewer = null },
            onDownload = { download(listOf(it)) },
            onShare = { share(it) },
            onDelete = { confirmDelete = listOf(it) }
        )
    }
}

@Composable
private fun Setup(onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = ACCENT, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Status Gallery", fontSize = 26.sp, color = Color.White)
        Spacer(Modifier.height(10.dp))
        Text(
            "One-time setup. Pick the WhatsApp status folder and tap \"Use this folder\". " +
                "Your statuses are then sorted into Pictures, Videos and Audio.",
            color = MUTED, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onPick("com.whatsapp") },
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Choose WhatsApp folder") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { onPick("com.whatsapp.w4b") }, modifier = Modifier.fillMaxWidth()) {
            Text("Choose WhatsApp Business folder", color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "The folder is hidden. On Android 11 or newer it opens at Android > media > com.whatsapp > WhatsApp > Media > .Statuses.",
            color = MUTED, fontSize = 12.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Cell(
    m: Media,
    done: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit
) {
    val ctx = LocalContext.current
    val info by produceState<Thumbs.Info?>(null, m.key) {
        value = withContext(Dispatchers.IO) { Thumbs.get(ctx, m) }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(CARD)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    ) {
        val bmp = info?.bmp
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
        if (m.kind == Kind.AUDIO) {
            Column(
                Modifier.fillMaxSize().padding(6.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = ACCENT, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    m.name, color = MUTED, fontSize = 10.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                )
            }
        }
        if (m.kind != Kind.IMAGE) {
            val dur = info?.durationMs ?: 0L
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (m.kind == Kind.VIDEO) Icons.Default.PlayArrow else Icons.Default.MusicNote,
                    contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)
                )
                if (dur > 0) Text(duration(dur), color = Color.White, fontSize = 11.sp)
            }
        }
        if (done && !m.saved) {
            Icon(
                Icons.Default.Check, contentDescription = "Downloaded", tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(ACCENT)
                    .padding(2.dp)
            )
        }
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color(0x6600A884)))
            Icon(
                Icons.Default.CheckCircle, contentDescription = "Selected", tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
            )
        }
    }
}

@Composable
private fun Viewer(
    items: List<Media>,
    start: Int,
    savedNames: Set<String>,
    onDismiss: () -> Unit,
    onDownload: (Media) -> Unit,
    onShare: (Media) -> Unit,
    onDelete: (Media) -> Unit
) {
    val pager = rememberPagerState(initialPage = start) { items.size }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, modifier = Modifier.fillMaxSize()) { page ->
                val m = items[page]
                val active = pager.currentPage == page
                when (m.kind) {
                    Kind.IMAGE -> ImagePage(m)
                    Kind.VIDEO -> VideoPage(m, active)
                    Kind.AUDIO -> AudioPage(m, active)
                }
            }
            val cur = items[pager.currentPage.coerceIn(0, items.lastIndex)]
            val done = cur.saved || cur.name in savedNames
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
                Text(
                    "${pager.currentPage + 1} / ${items.size}", color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onShare(cur) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                }
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                if (cur.saved) {
                    OutlinedButton(onClick = { onDelete(cur) }) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete", color = Color(0xFFEF5350))
                    }
                } else {
                    Button(
                        onClick = { onDownload(cur) },
                        enabled = !done,
                        colors = ButtonDefaults.buttonColors(containerColor = ACCENT, disabledContainerColor = CARD)
                    ) {
                        Icon(
                            if (done) Icons.Default.Check else Icons.Default.Download,
                            contentDescription = null, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (done) "Downloaded" else "Download")
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePage(m: Media) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, m.key) {
        value = withContext(Dispatchers.IO) { Thumbs.full(ctx, m) }
    }
    var scale by remember(m.key) { mutableFloatStateOf(1f) }
    var offset by remember(m.key) { mutableStateOf(Offset.Zero) }
    val tstate = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale <= 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(m.key) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        })
                    }
                    .transformable(tstate, canPan = { scale > 1f })
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            )
        } else {
            CircularProgressIndicator(color = ACCENT)
        }
    }
}

@Composable
private fun VideoPage(m: Media, active: Boolean) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            VideoView(c).apply {
                val mc = MediaController(c)
                mc.setAnchorView(this)
                setMediaController(mc)
                setVideoURI(m.uri)
                setOnPreparedListener { it.isLooping = true }
            }
        },
        update = { v ->
            if (active) {
                if (!v.isPlaying) v.start()
            } else if (v.isPlaying) {
                v.pause()
            }
        },
        onRelease = { it.stopPlayback() }
    )
}

@Composable
private fun AudioPage(m: Media, active: Boolean) {
    val ctx = LocalContext.current
    var playing by remember(m.key) { mutableStateOf(false) }
    var pos by remember(m.key) { mutableFloatStateOf(0f) }
    val mp = remember(m.key) {
        try {
            MediaPlayer().apply {
                setDataSource(ctx, m.uri)
                prepare()
                setOnCompletionListener { playing = false; pos = 0f; seekTo(0) }
            }
        } catch (e: Exception) {
            null
        }
    }
    DisposableEffect(mp) { onDispose { mp?.release() } }
    LaunchedEffect(playing) {
        while (playing && mp != null) {
            pos = mp.currentPosition.toFloat()
            delay(250)
        }
    }
    LaunchedEffect(active) {
        if (!active) {
            try {
                mp?.pause()
            } catch (e: Exception) {
            }
            playing = false
        }
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.MusicNote, contentDescription = null, tint = ACCENT, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(12.dp))
        Text(m.name, color = MUTED, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        val total = (mp?.duration ?: 0).coerceAtLeast(1).toFloat()
        Slider(
            value = pos.coerceIn(0f, total),
            onValueChange = { pos = it; mp?.seekTo(it.toInt()) },
            valueRange = 0f..total,
            colors = SliderDefaults.colors(thumbColor = ACCENT, activeTrackColor = ACCENT)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(duration(pos.toLong()), color = MUTED, fontSize = 12.sp)
            Text(duration(total.toLong()), color = MUTED, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        FilledIconButton(
            onClick = {
                if (mp != null) {
                    if (playing) mp.pause() else mp.start()
                    playing = !playing
                }
            },
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ACCENT)
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = Color.White, modifier = Modifier.size(36.dp)
            )
        }
    }
}
