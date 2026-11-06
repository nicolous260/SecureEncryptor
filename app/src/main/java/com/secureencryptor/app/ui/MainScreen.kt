package com.secureencryptor.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.secureencryptor.app.crypto.PasswordStrength
import com.secureencryptor.app.ui.theme.*

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val password by viewModel.password.collectAsState()
    val confirmPassword by viewModel.confirmPassword.collectAsState()
    val isPasswordVisible by viewModel.isPasswordVisible.collectAsState()
    val passwordStrength by viewModel.passwordStrength.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()

    val inputText by viewModel.inputText.collectAsState()
    val textResult by viewModel.textResult.collectAsState()

    val selectedFileName by viewModel.selectedFileName.collectAsState()
    val selectedFileSize by viewModel.selectedFileSize.collectAsState()
    val fileProgress by viewModel.fileProgress.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val alertMessage by viewModel.alertMessage.collectAsState()
    val outputFileResult by viewModel.outputFileResult.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var alertIsError by remember { mutableStateOf(false) }

    // File pickers
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val (name, size) = getFileInfo(context, it)
            viewModel.onFileSelected(it, name, size)
        }
    }

    var pendingSaveBytes by remember { mutableStateOf<ByteArray?>(null) }
    val fileSaverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { saveUri: Uri? ->
        saveUri?.let { uri ->
            pendingSaveBytes?.let { bytes ->
                try {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(bytes)
                    }
                    viewModel.clearOutputFileResult()
                    pendingSaveBytes = null
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    LaunchedEffect(outputFileResult) {
        outputFileResult?.let { (defaultFileName, bytes) ->
            pendingSaveBytes = bytes
            fileSaverLauncher.launch(defaultFileName)
        }
    }

    LaunchedEffect(alertMessage) {
        alertMessage?.let { (msg, isError) ->
            alertIsError = isError
            snackbarHostState.showSnackbar(msg)
            viewModel.clearAlert()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = BackgroundDeep,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    shape = RoundedCornerShape(10.dp),
                    containerColor = SurfaceRaised,
                    contentColor = TextPrimary
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (alertIsError) Negative else Positive)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(data.visuals.message, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                HeaderSection()

                // Password
                Panel {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Security",
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { viewModel.onPasswordChanged(it) },
                            label = { Text("Password") },
                            placeholder = { Text("At least 6 characters") },
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { viewModel.togglePasswordVisibility() }) {
                                    Icon(
                                        imageVector = if (isPasswordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                                        tint = TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = fieldColors()
                        )

                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { viewModel.onConfirmPasswordChanged(it) },
                            label = { Text("Confirm password") },
                            placeholder = { Text("Repeat password") },
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = fieldColors()
                        )

                        PasswordStrengthMeter(strength = passwordStrength)
                    }

                    SecondaryButton(
                        text = "Generate password",
                        icon = Icons.Outlined.Autorenew,
                        onClick = { viewModel.generatePassword() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SegmentedTabControl(
                    selectedTab = selectedTab,
                    onTabSelected = { viewModel.onTabSelected(it) }
                )

                if (selectedTab == 0) {
                    Panel {
                        AnimatedContent(
                            targetState = textResult.isNotEmpty(),
                            transitionSpec = {
                                (fadeIn(tween(300, easing = AppEase)) +
                                    slideInVertically(tween(300, easing = AppEase)) { 24 })
                                    .togetherWith(fadeOut(tween(150, easing = AppEase)))
                            },
                            label = "TextEncryptionModeTransition"
                        ) { showResult ->
                            if (showResult) {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    // Ciphertext readout
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        color = SurfaceDeep,
                                        border = BorderStroke(1.dp, CardBorder)
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text(
                                                "Result",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp,
                                                color = TextMuted
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            SelectionContainer {
                                                Text(
                                                    textResult,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 13.sp,
                                                    lineHeight = 20.sp,
                                                    color = TextPrimary,
                                                    modifier = Modifier
                                                        .heightIn(max = 240.dp)
                                                        .verticalScroll(rememberScrollState())
                                                )
                                            }
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        QuietButton(
                                            text = "Copy",
                                            icon = Icons.Outlined.ContentCopy,
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(textResult))
                                            }
                                        )
                                        QuietButton(
                                            text = "Share",
                                            icon = Icons.Outlined.Share,
                                            onClick = {
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, textResult)
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "Share result"))
                                            }
                                        )
                                    }

                                    PrimaryButton(
                                        text = "Back to input",
                                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                        onClick = { viewModel.clearResult() },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    OutlinedTextField(
                                        value = inputText,
                                        onValueChange = { viewModel.onInputTextChanged(it) },
                                        label = { Text("Input") },
                                        placeholder = { Text("Text to encrypt, or encrypted text to decrypt") },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 144.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = fieldColors(),
                                        maxLines = 6
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PrimaryButton(
                                            text = "Encrypt",
                                            icon = Icons.Outlined.Lock,
                                            onClick = { viewModel.encryptText() },
                                            modifier = Modifier.weight(1f),
                                            enabled = !isProcessing
                                        )
                                        SecondaryButton(
                                            text = "Decrypt",
                                            icon = Icons.Outlined.LockOpen,
                                            onClick = { viewModel.decryptText() },
                                            modifier = Modifier.weight(1f),
                                            enabled = !isProcessing
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Panel {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { filePickerLauncher.launch("*/*") },
                            shape = RoundedCornerShape(8.dp),
                            color = SurfaceDeep,
                            border = BorderStroke(1.dp, BorderStrong)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Outlined.FileUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = TextMuted
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = selectedFileName ?: "Select a file",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    color = TextPrimary
                                )
                                if (selectedFileSize > 0) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = formatFileSize(selectedFileSize),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        }

                        if (isProcessing || fileProgress > 0) {
                            Column {
                                LinearProgressIndicator(
                                    progress = { fileProgress / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = TextPrimary,
                                    trackColor = SurfaceRaised,
                                    drawStopIndicator = {}
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "$fileProgress%",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = TextMuted,
                                    modifier = Modifier.align(Alignment.End)
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton(
                                text = "Encrypt file",
                                icon = Icons.Outlined.Lock,
                                onClick = { viewModel.encryptFile(context) },
                                modifier = Modifier.weight(1f),
                                enabled = !isProcessing && selectedFileName != null
                            )
                            SecondaryButton(
                                text = "Decrypt file",
                                icon = Icons.Outlined.LockOpen,
                                onClick = { viewModel.decryptFile(context) },
                                modifier = Modifier.weight(1f),
                                enabled = !isProcessing && selectedFileName != null
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Brass,
    unfocusedBorderColor = BorderStrong,
    focusedContainerColor = SurfaceDeep,
    unfocusedContainerColor = SurfaceDeep,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = TextSecondary,
    unfocusedLabelColor = TextSecondary,
    focusedPlaceholderColor = TextMuted,
    unfocusedPlaceholderColor = TextMuted,
    cursorColor = Brass
)

@Composable
private fun PrimaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(8.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = TextPrimary,
            contentColor = BackgroundDeep,
            disabledContainerColor = SurfaceRaised,
            disabledContentColor = TextMuted
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(8.dp),
        enabled = enabled,
        border = BorderStroke(1.dp, if (enabled) BorderStrong else CardBorder),
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceRaised,
            contentColor = TextPrimary,
            disabledContainerColor = SurfaceDeep,
            disabledContentColor = TextMuted
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
private fun QuietButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp)
    }
}

@Composable
fun HeaderSection() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = Brass,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "SecureEncryptor",
            fontWeight = FontWeight.Medium,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.6).sp,
            color = TextPrimary
        )
    }
}

@Composable
fun SegmentedTabControl(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = SurfaceDeep,
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("Text", "Files").forEachIndexed { index, label ->
                val selected = selectedTab == index
                val bg by animateColorAsState(
                    if (selected) SurfaceRaised else Color.Transparent,
                    animationSpec = tween(150, easing = AppEase),
                    label = "tabBg$index"
                )
                val fg by animateColorAsState(
                    if (selected) TextPrimary else TextSecondary,
                    animationSpec = tween(150, easing = AppEase),
                    label = "tabFg$index"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(bg)
                        .clickable { onTabSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = fg
                    )
                }
            }
        }
    }
}

/** Five pins that set like a lock. Brass once the password is strong. */
@Composable
fun PasswordStrengthMeter(strength: PasswordStrength) {
    val filled = (strength.percent / 20f).toInt().coerceIn(0, 5)
    val pinColor by animateColorAsState(
        if (filled >= 4) Brass else TextSecondary,
        animationSpec = tween(180, easing = AppEase),
        label = "pinColor"
    )
    val label = strength.label.lowercase().replaceFirstChar { it.uppercase() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(5) { index ->
                val pin by animateColorAsState(
                    if (index < filled) pinColor else SurfaceRaised,
                    animationSpec = tween(180, easing = AppEase),
                    label = "pin$index"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(pin)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (filled == 0) "Strength: None" else "Strength: $label",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = TextMuted
        )
    }
}

fun getFileInfo(context: Context, uri: Uri): Pair<String, Long> {
    var name = "unknown_file"
    var size = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
            if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
        }
    }
    return Pair(name, size)
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val k = 1024
    val units = arrayOf("B", "KB", "MB", "GB")
    val i = (Math.log(bytes.toDouble()) / Math.log(k.toDouble())).toInt()
    val value = bytes / Math.pow(k.toDouble(), i.toDouble())
    return "%.2f %s".format(value, units[i])
}
