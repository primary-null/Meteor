package com.meteor.app.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meteor.app.ConsoleController
import com.meteor.app.ConsolePhase
import com.meteor.app.ConsoleUiState
import com.meteor.app.MemberTrustReview
import com.meteor.app.MeteorViewModel
import com.meteor.app.data.ConsoleFailureKind
import com.meteor.app.data.ConsoleModifier
import com.meteor.app.data.Framebuffer
import com.meteor.app.data.KeySyms
import com.meteor.app.data.ViewTransform
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** The immersive console for a [Destination.Console]. The session itself lives in [MeteorViewModel.console]. */
@Composable
fun ConsoleHost(destination: Destination.Console, model: MeteorViewModel, leave: () -> Unit) {
    ConsoleScreen(model.console, destination.vmName, leave)
}

private enum class ConsoleDialog { LEAVE, CTRL_ALT_DEL, PASTE }

/** Copies changed guest regions into one bitmap on the UI thread. A new view starts with a full copy. */
private class FrameUploader {
    var bitmap by mutableStateOf<Bitmap?>(null)
    var version by mutableIntStateOf(0)
    private var source: Framebuffer? = null

    fun sync(framebuffer: Framebuffer?) {
        if (framebuffer == null) { bitmap = null; source = null; version++; return }
        val whole = bitmap == null || source !== framebuffer
        val delta = (if (whole) { framebuffer.takeDelta(); framebuffer.fullDelta() } else framebuffer.takeDelta()) ?: return
        var target = bitmap
        if (target == null || target.width != delta.width || target.height != delta.height) {
            target = createBitmap(delta.width, delta.height)
            bitmap = target
        }
        target.setPixels(delta.pixels, delta.y * delta.width + delta.x, delta.width, delta.x, delta.y, delta.w, delta.h)
        source = framebuffer
        version++
    }
}

private const val FRAME_INTERVAL_MILLIS = 33L

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConsoleScreen(controller: ConsoleController, title: String, leave: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val modifiers by controller.input.modifiers.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val connected = state.phase == ConsolePhase.CONNECTED

    var chrome by rememberSaveable { mutableStateOf(true) }
    var keyBar by rememberSaveable { mutableStateOf(false) }
    var touchMode by rememberSaveable { mutableStateOf(TouchMode.MOUSE) }
    var doubleTapZoom by rememberSaveable { mutableStateOf(true) }
    var transform by remember { mutableStateOf(ViewTransform()) }
    var dialog by remember { mutableStateOf<ConsoleDialog?>(null) }
    val uploader = remember { FrameUploader() }
    val rootFocus = remember { FocusRequester() }
    val softFocus = remember { FocusRequester() }

    val requestLeave = { if (controller.input.anyHeld) dialog = ConsoleDialog.LEAVE else leave() }
    BackHandler(onBack = requestLeave)

    // Background grace period: rotation and fold/unfold stop and restart the activity within it, so the session stays.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.appBackgrounded()
                Lifecycle.Event.ON_START -> controller.appForegrounded()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Immersive: system bars are hidden while the console is on screen and a swipe reveals them briefly.
    DisposableEffect(view) {
        val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { insets?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(view, connected) {
        view.keepScreenOn = connected
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(controller) {
        controller.frames.collect {
            uploader.sync(controller.framebuffer)
            delay(FRAME_INTERVAL_MILLIS) // Redraws are throttled; the newest frame is always shown after the pause.
        }
    }
    LaunchedEffect(state.phase) { uploader.sync(controller.framebuffer) }
    LaunchedEffect(state.frameWidth, state.frameHeight) { transform = transform.withFrame(state.frameWidth, state.frameHeight) }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    val gestures = remember(controller) {
        ConsoleGestureHost(transform = { transform }, setTransform = { transform = it }, input = controller.input,
            mode = { touchMode }, doubleTapZoom = { doubleTapZoom }, haptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) })
    }

    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).focusable()
        .onPreviewKeyEvent { event -> connected && forwardKeyEvent(event, controller.input) }) {
        // The toolbar insets itself (cutout, system gestures, status-bar height); the framebuffer keeps the safe-drawing insets
        // and takes the top one itself only while the toolbar is hidden, so the picture stays as immersive as before.
        Column(Modifier.fillMaxSize()) {
            if (chrome) {
                ConsoleToolbar(title, state, modifiers.any, touchMode, keyBar, imeVisible, transform, doubleTapZoom,
                    onClose = requestLeave,
                    onKeys = { keyBar = !keyBar },
                    onKeyboard = {
                        if (imeVisible) { keyboard?.hide(); rootFocus.requestFocus() } else { softFocus.requestFocus(); keyboard?.show() }
                    },
                    onMode = { touchMode = touchMode.next() },
                    onFit = { transform = if (transform.fit) transform.toggleActualSize(transform.viewWidth / 2f, transform.viewHeight / 2f) else transform.fitToScreen() },
                    onViewOnly = { controller.setViewOnly(!state.viewOnly) },
                    onPaste = { dialog = ConsoleDialog.PASTE },
                    onCtrlAltDel = { dialog = ConsoleDialog.CTRL_ALT_DEL },
                    onDoubleTap = { doubleTapZoom = !doubleTapZoom },
                    onReconnect = controller::reconnect,
                    onHide = { chrome = false })
                if (keyBar) ConsoleKeyBar(connected && !state.viewOnly, modifiers, controller.input::toggle, controller.input::press,
                    { dialog = ConsoleDialog.CTRL_ALT_DEL },
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(vertical = 2.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(
                if (chrome) WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom else WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical))) {
                val image = remember(uploader.bitmap) { uploader.bitmap?.asImageBitmap() }
                Canvas(Modifier.fillMaxSize().alpha(if (state.phase == ConsolePhase.FAILED) .35f else 1f)
                    .semantics { contentDescription = "Console of $title" }
                    .onSizeChanged { transform = transform.withViewport(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(gestures) { consoleGestures(gestures) }) {
                    uploader.version // Read so a new frame redraws the canvas.
                    val bitmap = image ?: return@Canvas
                    val region = transform.visibleRegion() ?: return@Canvas
                    drawImage(bitmap, IntOffset(region.srcX, region.srcY), IntSize(region.srcWidth, region.srcHeight),
                        IntOffset(region.dstX.roundToInt(), region.dstY.roundToInt()),
                        IntSize(region.dstWidth.roundToInt().coerceAtLeast(1), region.dstHeight.roundToInt().coerceAtLeast(1)),
                        filterQuality = if (transform.scale >= 1f) FilterQuality.None else FilterQuality.Medium)
                }
                ConsoleStatus(state, onReconnect = controller::reconnect, onClose = requestLeave)
            }
        }
        if (!chrome) TextButton(onClick = { chrome = true }, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().alpha(.7f)) { Text("Controls") }
        SoftKeyboardField(softFocus, { backspaces, text -> applySoftKeyboardEdit(controller.input, backspaces, text) }, Modifier.align(Alignment.TopStart))
    }

    state.review?.let { review ->
        MemberTrustDialog(review, state.trustBusy, onTrust = controller::approveMemberTrust, onCancel = controller::declineMemberTrust)
    }
    when (dialog) {
        ConsoleDialog.LEAVE -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Leave the console?") },
            text = { Text("A key or mouse button is still held down in the VM. Leaving releases it first.") },
            confirmButton = { TextButton(onClick = { dialog = null; leave() }) { Text("Release and leave") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Stay") } })
        ConsoleDialog.CTRL_ALT_DEL -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Send Ctrl+Alt+Del?") },
            text = { Text("This sends the three keys to ${title} as a key sequence. What happens depends on the guest: Windows shows its security screen, some Linux guests reboot. It is not an XCP-ng reboot command and nothing is recorded as a power action.") },
            confirmButton = { TextButton(onClick = { dialog = null; controller.input.ctrlAltDel() }, enabled = connected && !state.viewOnly) { Text("Send") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        ConsoleDialog.PASTE -> PasteDialog(context, enabled = connected && !state.viewOnly, pasting = state.pasting,
            onSend = { text -> dialog = null; controller.paste(text) }, onStop = controller::cancelPaste, onDismiss = { dialog = null })
        null -> Unit
    }
}

/**
 * Where the toolbar's content may sit: inside the display cutout, clear of the system-gesture edges (a Close button under the
 * back-gesture zone is hard to hit) and below the status-bar height even while the bars are hidden in immersive mode, so the
 * title is never drawn against the very top edge or the rounded corners of a foldable's inner display.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun toolbarInsets(): WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.displayCutout).union(WindowInsets.systemGestures)
    .union(WindowInsets.statusBarsIgnoringVisibility).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

@Composable
private fun ConsoleToolbar(title: String, state: ConsoleUiState, modifierActive: Boolean, mode: TouchMode, keysOpen: Boolean, imeVisible: Boolean,
    transform: ViewTransform, doubleTapZoom: Boolean, onClose: () -> Unit, onKeys: () -> Unit, onKeyboard: () -> Unit, onMode: () -> Unit,
    onFit: () -> Unit, onViewOnly: () -> Unit, onPaste: () -> Unit, onCtrlAltDel: () -> Unit, onDoubleTap: () -> Unit,
    onReconnect: () -> Unit, onHide: () -> Unit) {
    val connected = state.phase == ConsolePhase.CONNECTED
    var menu by remember { mutableStateOf(false) }
    // The padding is inside the Surface so its background still reaches the screen edge while the content stays clear of it.
    // A translucent colour matches no scheme colour, so contentColorFor() would fall back to black; set it explicitly.
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.windowInsetsPadding(toolbarInsets()).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onClose) { Text("Close") }
            Column(Modifier.widthIn(max = 170.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(when {
                    state.phase == ConsolePhase.CONNECTING -> "Connecting…"
                    state.phase == ConsolePhase.FAILED -> "Disconnected"
                    state.phase == ConsolePhase.IDLE -> "Closed"
                    state.viewOnly -> "View only"
                    modifierActive -> "Modifier held · typing goes to the VM"
                    else -> "Typing goes to the VM"
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            FilterChip(selected = keysOpen, onClick = onKeys, label = { Text("Keys") })
            FilterChip(selected = imeVisible, onClick = onKeyboard, enabled = connected && !state.viewOnly, label = { Text("Keyboard") })
            AssistChip(onClick = onMode, label = { Text("Drag: ${mode.label}") })
            AssistChip(onClick = onFit, enabled = transform.ready,
                label = { Text(if (transform.fit) "Fit" else "${(transform.scale * 100).roundToInt()}%") })
            FilterChip(selected = state.viewOnly, onClick = onViewOnly, label = { Text("View only") })
            Box {
                TextButton(onClick = { menu = true }) { Text("More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Paste text…") }, enabled = connected && !state.viewOnly, onClick = { menu = false; onPaste() })
                    DropdownMenuItem(text = { Text("Ctrl+Alt+Del…") }, enabled = connected && !state.viewOnly, onClick = { menu = false; onCtrlAltDel() })
                    DropdownMenuItem(text = { Text(if (doubleTapZoom) "Double tap: zoom (switch to double-click)" else "Double tap: double-click (switch to zoom)") },
                        onClick = { menu = false; onDoubleTap() })
                    DropdownMenuItem(text = { Text("Reconnect") }, enabled = state.phase != ConsolePhase.CONNECTING && state.target != null,
                        onClick = { menu = false; onReconnect() })
                    DropdownMenuItem(text = { Text("Hide controls") }, onClick = { menu = false; onHide() })
                }
            }
        }
    }
}

/** Connecting, failed and closed states, drawn over the (dimmed) last frame. */
@Composable
private fun BoxScope.ConsoleStatus(state: ConsoleUiState, onReconnect: () -> Unit, onClose: () -> Unit) {
    if (state.phase == ConsolePhase.CONNECTED) return
    Surface(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 420.dp), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f), tonalElevation = 3.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (state.phase) {
                ConsolePhase.CONNECTING -> {
                    CircularProgressIndicator()
                    Text(state.message ?: "Connecting to the console…")
                }
                ConsolePhase.FAILED -> {
                    Text(failureTitle(state.failure), style = MaterialTheme.typography.titleMedium)
                    Text(state.message ?: "The console is not connected.", style = MaterialTheme.typography.bodyMedium)
                    failureHint(state.failure)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onReconnect) { Text(if (state.failure == ConsoleFailureKind.MEMBER_TRUST) "Review certificate" else "Reconnect") }
                        TextButton(onClick = onClose) { Text("Close console") }
                    }
                }
                else -> {
                    Text("The console is closed.")
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

private fun failureTitle(kind: ConsoleFailureKind?) = when (kind) {
    ConsoleFailureKind.NOT_RUNNING -> "VM is not running"
    ConsoleFailureKind.NO_CONSOLE -> "No console available"
    ConsoleFailureKind.MEMBER_TRUST -> "Pool member not trusted yet"
    ConsoleFailureKind.CERTIFICATE -> "Certificate mismatch"
    ConsoleFailureKind.AUTH -> "Access refused"
    ConsoleFailureKind.NETWORK -> "Host unreachable"
    ConsoleFailureKind.TIMEOUT -> "Timed out"
    ConsoleFailureKind.LOST -> "Connection lost"
    ConsoleFailureKind.BACKGROUND -> "Console closed in the background"
    ConsoleFailureKind.PROTOCOL -> "Console data could not be read"
    ConsoleFailureKind.UNSUPPORTED -> "Console not supported"
    ConsoleFailureKind.FAILED, null -> "Console unavailable"
}

private fun failureHint(kind: ConsoleFailureKind?): String? = when (kind) {
    ConsoleFailureKind.CERTIFICATE -> "If the server certificate was renewed, open Connect and review its fingerprint before trusting it again."
    ConsoleFailureKind.NETWORK, ConsoleFailureKind.TIMEOUT, ConsoleFailureKind.LOST ->
        "The console needs a direct network path from this device to the host that runs the VM, which can be a pool member."
    ConsoleFailureKind.PROTOCOL -> "Reconnect retries in compatibility mode, which uses uncompressed encodings only."
    ConsoleFailureKind.MEMBER_TRUST -> "The VM runs on a pool member. Nothing was sent to that host."
    else -> null
}

@Composable
private fun MemberTrustDialog(review: MemberTrustReview, busy: Boolean, onTrust: () -> Unit, onCancel: () -> Unit) {
    var verified by remember(review) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Trust this pool member?") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This VM's console is served by ${review.authority}, a pool member. Its certificate differs from the pool coordinator's saved pin, so Meteor needs your approval before it sends the console session to that host.")
            if (review.previousPin != null) Text("This host was trusted before but now presents a different certificate. Verify the change independently.")
            Text(review.certificate.subject, style = MaterialTheme.typography.bodySmall)
            val dates = DateFormat.getDateInstance()
            Text("Valid from ${dates.format(Date(review.certificate.validFrom))}\nExpires ${dates.format(Date(review.certificate.validUntil))}", style = MaterialTheme.typography.bodySmall)
            Fingerprint("Observed SHA-256 of ${review.authority}", review.certificate.sha256)
            review.previousPin?.let { Fingerprint("Previously trusted SHA-256", it) }
            Text("Approving saves this fingerprint in this connection's encrypted profile for this host only. The pool coordinator's saved pin does not change.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(verified, { verified = it })
                Text("I compared the fingerprint with a trusted source.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = onTrust, enabled = verified && !busy) { Text("Trust and connect") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}

@Composable
private fun PasteDialog(context: Context, enabled: Boolean, pasting: Boolean, onSend: (String) -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Paste text") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("The text is typed into the VM as key presses (US layout), not sent as a clipboard.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it.take(ConsoleController.MAX_PASTE_CHARS) }, label = { Text("Text to type") },
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp), minLines = 3)
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val pasted = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!pasted.isNullOrEmpty()) text = pasted.take(ConsoleController.MAX_PASTE_CHARS)
            }) { Text("Use device clipboard") }
            if (pasting) Text("Typing in progress…", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        if (pasting) TextButton(onClick = onStop) { Text("Stop typing") }
        else TextButton(onClick = { onSend(text) }, enabled = enabled && text.isNotEmpty()) { Text("Type into VM") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
