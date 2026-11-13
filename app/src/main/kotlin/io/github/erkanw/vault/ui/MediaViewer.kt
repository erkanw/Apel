package io.github.erkanw.vault.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.erkanw.vault.chat.MediaLoader
import io.github.erkanw.vault.chat.MessageAttachment

/** A picture or video sent with a message, shown inside its bubble; a tap opens the viewer. */
@Composable
fun MediaPreview(file: MessageAttachment, base: String?, content: Color, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    if (file.video) {
        Box(
            modifier
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.55f))
                .pressable(onClick = onOpen, shape = shape, pressedScale = 0.98f, onClickLabel = "Open ${file.name}")
                .size(width = 200.dp, height = 120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Icon(VaultIcons.Play, "Play ${file.name}", tint = Color.Black, modifier = Modifier.size(22.dp))
            }
            Text(
                file.name,
                color = Color.White,
                style = VaultType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        return
    }
    // null while loading, false when it can't be had.
    val bitmap by produceState<Any?>(null, file.localPath, file.url, base) {
        value = MediaLoader.bitmap(base, file, 640) ?: false
    }
    val loaded = bitmap as? android.graphics.Bitmap
    Box(modifier.clip(shape).pressable(onClick = onOpen, shape = shape, pressedScale = 0.98f, onClickLabel = "Open ${file.name}"), contentAlignment = Alignment.Center) {
        when {
            loaded != null -> Image(
                loaded.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 240.dp).height(maxOf(80, minOf(260, (240f * loaded.height / loaded.width).toInt())).dp),
            )
            bitmap == null -> Box(Modifier.size(width = 160.dp, height = 110.dp).background(content.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
            else -> Box(Modifier.size(width = 160.dp, height = 70.dp).background(content.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(VaultIcons.FileText, null, tint = content.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(file.name, color = content, style = VaultType.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Full-screen viewer for the pictures and videos of a message: swipe between them, pinch or double-tap to zoom a picture. */
@Composable
fun MediaViewer(
    files: List<MessageAttachment>,
    start: Int,
    base: String?,
    onClose: () -> Unit,
    /** Saves the file shown in Downloads/the agent; null hides the download button (the Files page has its own). */
    save: (suspend (MessageAttachment) -> io.github.erkanw.vault.SavedFile)? = null,
) {
    if (files.isEmpty()) {
        onClose()
        return
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, files.lastIndex)) { files.size }
    // A zoomed picture is moved with the finger, so the pager must not swipe at the same time.
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    val scope = rememberCoroutineScope()
    var saveState by remember { mutableStateOf(SaveState.Idle) }
    var saved by remember { mutableStateOf<io.github.erkanw.vault.SavedFile?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(saved, problem) {
        if (saved != null || problem != null) {
            kotlinx.coroutines.delay(6_000)
            saved = null
            problem = null
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                val file = files[page]
                if (file.video) VideoPage(file, base, active = pager.currentPage == page)
                else ImagePage(file, base, onZoomed = { if (pager.currentPage == page) zoomed = it })
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).pressable(onClick = onClose, shape = CircleShape, pressedScale = 0.9f, onClickLabel = "Close"),
                    contentAlignment = Alignment.Center,
                ) { Icon(VaultIcons.Close, "Close", tint = Color.White, modifier = Modifier.size(22.dp)) }
                Text(
                    files[pager.currentPage].name,
                    color = Color.White,
                    style = VaultType.Body,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                if (files.size > 1) Text("${pager.currentPage + 1} / ${files.size}", color = Color.White.copy(alpha = 0.8f), style = VaultType.Label.copy(fontWeight = null))
                if (save != null) {
                    Spacer(Modifier.width(8.dp))
                    // The download sits on the same dark disc as Close, opposite it: the picture stays the whole screen.
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        DownloadButton(saveState, "Save ${files[pager.currentPage].name} in Downloads", {
                            if (saveState != SaveState.Saving) {
                                val file = files[pager.currentPage]
                                saveState = SaveState.Saving
                                problem = null
                                scope.launch {
                                    try {
                                        saved = save(file)
                                        saveState = SaveState.Saved
                                        kotlinx.coroutines.delay(2_500)
                                        if (saveState == SaveState.Saved) saveState = SaveState.Idle
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        saveState = SaveState.Idle
                                        problem = "Couldn’t save ${file.name}: ${e.message ?: "the download failed"}."
                                    }
                                }
                            }
                        }, tint = Color.White)
                    }
                }
            }
            SavedNote(saved, problem, onProblem = { problem = it }, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
        }
    }
}

@Composable
private fun ImagePage(file: MessageAttachment, base: String?, onZoomed: (Boolean) -> Unit) {
    val state by produceState<Any?>(null, file.localPath, file.url, base) {
        value = MediaLoader.bitmap(base, file, 2048) ?: false
    }
    val bitmap = state as? android.graphics.Bitmap
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1f) / 2f
        val maxY = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    LaunchedEffect(scale) { onZoomed(scale > 1.01f) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .pointerInput(Unit) {
                // Only two fingers, or a finger on a zoomed picture, are taken here: one finger on an unzoomed one swipes the pager.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val next = (scale * zoom).coerceIn(1f, 6f)
                            scale = next
                            offset = if (next <= 1.01f) Offset.Zero else clamp(offset + pan, next)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            state == null -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            else -> Text(
                "Couldn’t load this picture.\nIt may have been removed from this phone.",
                color = Color.White.copy(alpha = 0.8f),
                style = VaultType.Body,
                modifier = Modifier.padding(32.dp),
            )
        }
    }
}

@Composable
private fun VideoPage(file: MessageAttachment, base: String?, active: Boolean) {
    val context = LocalContext.current
    val address = remember(file.localPath, file.url, base) { MediaLoader.playableUri(base, file) }
    var failed by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(Unit) { onDispose { video?.stopPlayback() } }
    LaunchedEffect(active, video) {
        val v = video ?: return@LaunchedEffect
        if (active) v.start() else v.pause()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (address == null || failed) {
            Text(
                "Couldn’t play this video.\nIt may have been removed from this phone.",
                color = Color.White.copy(alpha = 0.8f),
                style = VaultType.Body,
                modifier = Modifier.padding(32.dp),
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    val frame = FrameLayout(context)
                    val view = VideoView(context)
                    view.setOnErrorListener { _, _, _ ->
                        failed = true
                        true
                    }
                    view.setOnPreparedListener { if (active) it.start() }
                    view.setMediaController(MediaController(context).also { it.setAnchorView(frame) })
                    view.setVideoURI(Uri.parse(address))
                    frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                    video = view
                    frame
                },
            )
        }
    }
}
