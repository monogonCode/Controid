package com.controid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.controid.clipboard.ClipboardContentType
import com.controid.clipboard.ClipboardDirection
import com.controid.clipboard.ClipboardEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ClipboardScreen(
    state: EcosystemUiState,
    onSendClipboardNow: () -> Unit,
    onCopyTextToClipboard: (String) -> Unit,
    onCopyEntryToClipboard: (ClipboardEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var localClipboardPreview by remember { mutableStateOf<String?>(null) }

    val latestEntry = state.clipboardEntries.firstOrNull()
    val previewText = latestEntry?.textPreview ?: localClipboardPreview
    val previewMime = latestEntry?.mime ?: "text/plain"
    val previewUri = latestEntry?.fileUri?.toString()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Bidirectional Clipboard",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(
                                    if (state.clipboardBackgroundGranted) {
                                        "BG Sync Active"
                                    } else {
                                        "Foreground / Tile"
                                    }
                                )
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSendClipboardNow,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Send to PC")
                        }

                        FilledTonalButton(
                            onClick = {
                                localClipboardPreview = readLocalClipboardPreview(context)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Refresh")
                        }
                    }
                }
            }
        }

        // Latest synced / active preview
        if (latestEntry != null || !previewText.isNullOrBlank() || !previewUri.isNullOrBlank()) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Latest Clip ($previewMime)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (latestEntry != null) {
                                Text(
                                    text = formatByteSize(latestEntry.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (latestEntry?.contentType == ClipboardContentType.IMAGE ||
                            previewMime.startsWith("image/", ignoreCase = true)
                        ) {
                            ImagePreviewBox(
                                localFile = latestEntry?.localFile,
                                uriString = previewUri
                            )
                        } else if (!previewText.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .padding(12.dp)
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = previewText.take(2000),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 10,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = previewUri ?: "Binary payload ready",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    if (latestEntry != null) {
                                        onCopyEntryToClipboard(latestEntry)
                                    } else if (!previewText.isNullOrBlank()) {
                                        onCopyTextToClipboard(previewText)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("1-Tap Copy")
                            }
                        }
                    }
                }
            }
        }

        if (state.clipboardEntries.isEmpty()) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "No Clipboard Transfers Yet",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Copy text or an image on Android or Linux Hyprland, or tap Send to PC / QuickSettings tile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    text = "Recent History",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(state.clipboardEntries, key = { it.id }) { entry ->
                ClipboardHistoryRow(
                    entry = entry,
                    onCopy = { onCopyEntryToClipboard(entry) }
                )
            }
        }
    }
}

@Composable
private fun ImagePreviewBox(
    localFile: File?,
    uriString: String?
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, localFile?.absolutePath, uriString) {
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(context, localFile, uriString, maxDim = 720)
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Synced image preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 240.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Outlined.Image, contentDescription = null)
            Text(
                text = uriString ?: localFile?.name ?: "Image payload",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ClipboardHistoryRow(
    entry: ClipboardEntry,
    onCopy: () -> Unit
) {
    val dirIcon = if (entry.direction == ClipboardDirection.INBOUND) {
        Icons.Outlined.ArrowDownward
    } else {
        Icons.Outlined.ArrowUpward
    }
    val typeIcon = when (entry.contentType) {
        ClipboardContentType.IMAGE -> Icons.Outlined.Image
        else -> Icons.Outlined.Description
    }
    val relTime = DateUtils.getRelativeTimeSpanString(
        entry.timestampMs,
        System.currentTimeMillis(),
        DateUtils.SECOND_IN_MILLIS
    ).toString()

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = dirIcon,
                contentDescription = entry.direction.name,
                tint = if (entry.direction == ClipboardDirection.INBOUND) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.size(18.dp)
            )
            Icon(
                imageVector = typeIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.textPreview?.take(140) ?: "${entry.mime} (${formatByteSize(entry.sizeBytes)})",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${if (entry.direction == ClipboardDirection.INBOUND) "From PC" else "To PC"} • ${formatByteSize(entry.sizeBytes)} • $relTime",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy item"
                )
            }
        }
    }
}

private fun readLocalClipboardPreview(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
    if (clip.itemCount <= 0) return null
    val item = clip.getItemAt(0)
    return (item.text ?: item.uri?.toString() ?: runCatching { item.coerceToText(context) }.getOrNull())
        ?.toString()
}

private fun decodeSampledBitmap(
    context: Context,
    file: File?,
    uriString: String?,
    maxDim: Int
): Bitmap? {
    return runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (file != null && file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } else if (!uriString.isNullOrBlank()) {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } else {
            return null
        }

        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        if (file != null && file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        } else {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        }
    }.getOrNull()
}

internal fun copyTextToSystemClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { cm.setPrimaryClip(ClipData.newPlainText("Controid", text)) }
}
