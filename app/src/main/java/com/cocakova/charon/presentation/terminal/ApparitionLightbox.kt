package com.cocakova.charon.presentation.terminal

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.cocakova.charon.terminal.Apparition
import com.cocakova.charon.terminal.ImageFormat
import com.cocakova.charon.theme.CharonMono
import com.cocakova.charon.theme.Styx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The lightbox — a shade held up to the light.
 *
 * A terminal image on a desktop is a rectangle of dead pixels. On a phone it is a
 * photo: full screen, pinch to look closer, and gold **carry ashore** to put it in
 * the gallery or send it onward. This is the mobile surpass, and it costs one tap.
 */
@Composable
fun ApparitionLightbox(
    image: Apparition,
    cache: ApparitionCache,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(image) { cache.bitmapFor(image) }
    var note by remember { mutableStateOf<String?>(null) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val carry = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(image.mimeType()),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            note = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(image.shareableBytes(bitmap))
                    } ?: error("no stream")
                    "carried ashore"
                }.getOrElse { "the shade slipped: ${it.message ?: "unknown"}" }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Tap the water to let the shade sink back; double-tap toggles a
                // closer look without a pinch.
                .pointerInput(Unit) {
                    detectTapGestures(
                        // Zoomed in, a tap is part of looking — only a shade at rest
                        // sinks back on a tap.
                        onTap = {
                            if (scale > 1.05f) {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                            } else {
                                onDismiss()
                            }
                        },
                        onDoubleTap = {
                            if (scale > 1.05f) {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        if (scale > 1.01f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap == null) {
                Text(
                    "the shade would not hold its shape",
                    fontFamily = CharonMono,
                    fontSize = 13.sp,
                    color = Styx.mist,
                )
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "an apparition from the terminal",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                )
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                note?.let {
                    Text(
                        it,
                        fontFamily = CharonMono,
                        fontSize = 12.sp,
                        color = Styx.mist,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LightboxAction("⇣ carry ashore", Styx.coin) {
                        note = null
                        carry.launch(image.suggestedName())
                    }
                    LightboxAction("send onward", Styx.water) {
                        note = null
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                runCatching { image.stage(context, bitmap) }.getOrNull()
                            }
                            if (uri == null) {
                                note = "the shade would not travel"
                            } else {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = image.mimeType()
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        "send the shade onward",
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // A note is a passing remark, not a state to live in.
    LaunchedEffect(note) {
        if (note != null) {
            kotlinx.coroutines.delay(2600)
            note = null
        }
    }
}

@Composable
private fun LightboxAction(label: String, tint: Color, onClick: () -> Unit) {
    Text(
        label,
        fontFamily = CharonMono,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(tint)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

// ---------------------------------------------------------------- carrying ashore

/**
 * What the image is, on the way out. Raw pixel formats have no container of their
 * own, so they leave as PNG; an encoded image leaves exactly as it arrived — nobody
 * wants their JPEG re-encoded on the way to the gallery.
 */
private fun Apparition.mimeType(): String = when (format) {
    ImageFormat.RGB, ImageFormat.RGBA -> "image/png"
    ImageFormat.ENCODED -> sniffMime(bytes)
}

private fun Apparition.extension(): String = when (mimeType()) {
    "image/jpeg" -> "jpg"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    else -> "png"
}

private fun Apparition.suggestedName(): String = "charon-shade-$id.${extension()}"

private fun Apparition.shareableBytes(bitmap: Bitmap?): ByteArray = when (format) {
    ImageFormat.ENCODED -> bytes
    else -> ByteArrayOutputStream().also { out ->
        bitmap?.compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()
}

/** Park the bytes where the share sheet can reach them, then hand over a grant. */
private fun Apparition.stage(context: Context, bitmap: Bitmap?): android.net.Uri {
    val dir = File(context.cacheDir, "shades").apply { mkdirs() }
    // One file per image: re-sharing the same shade must not litter the cache.
    dir.listFiles()?.forEach { if (it.name.startsWith("charon-shade-$id.")) it.delete() }
    val file = File(dir, suggestedName())
    file.writeBytes(shareableBytes(bitmap))
    return FileProvider.getUriForFile(context, "${context.packageName}.shades", file)
}

private fun sniffMime(b: ByteArray): String = when {
    b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
    b.size >= 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "image/gif"
    b.size >= 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "image/webp"
    else -> "image/png"
}
