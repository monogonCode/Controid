package com.controid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controid.clipboard.ClipboardContentType
import com.controid.clipboard.ClipboardDirection
import com.controid.clipboard.ClipboardEntry
import com.controid.ui.dock.SekaiBannerPill
import com.controid.ui.dock.SekaiIconButton
import com.controid.ui.dock.SekaiKeyPill
import com.controid.ui.dock.SekaiPlate
import com.controid.ui.dock.SekaiResolvedTheme
import com.controid.ui.dock.SekaiStatusBadge
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ClipboardScreen(
    state: EcosystemUiState,
    theme: SekaiResolvedTheme,
    onOpenTheme: () -> Unit,
    onSendClipboardNow: () -> Unit,
    onCopyTextToClipboard: (String) -> Unit,
    onCopyEntryToClipboard: (ClipboardEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var localClipboardPreview by remember { mutableStateOf<String?>(null) }

    val latestEntry = state.clipboardEntries.firstOrNull()
    val previewText = latestEntry?.textPreview ?: localClipboardPreview
    val previewMime = latestEntry?.mime ?: "text/plain"
    val previewUri = latestEntry?.fileUri?.toString()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            maxWidth > maxHeight

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (isLandscape) 12.dp else 14.dp,
                    vertical = if (isLandscape) 8.dp else 10.dp
                ),
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 8.dp else 10.dp)
        ) {
            // SekaiPop Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SekaiBannerPill(
                    theme = theme,
                    title = "Portapapeles",
                    subtitle = if (isLandscape) "Sincronización bidireccional PC ↔ Móvil" else "PC ↔ Móvil en vivo",
                    icon = Icons.Outlined.ContentPaste,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SekaiStatusBadge(
                        theme = theme,
                        text = if (state.clipboardBackgroundGranted) "Sync 2o plano" else "Primer plano / Tile",
                        dotColor = if (state.clipboardBackgroundGranted) theme.accentB else theme.accentA
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.ColorLens,
                        contentDescription = "Estética SekaiPop",
                        size = 36.dp,
                        onClick = onOpenTheme
                    )
                }
            }

            if (isLandscape) {
                // Landscape 2-Pane Split: Left Controls & Preview | Right History List
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.95f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ClipboardActionsCard(
                            theme = theme,
                            compact = true,
                            onSendClipboardNow = onSendClipboardNow,
                            onRefreshLocal = { localClipboardPreview = readLocalClipboardPreview(context) }
                        )

                        if (latestEntry != null || !previewText.isNullOrBlank() || !previewUri.isNullOrBlank()) {
                            LatestClipCard(
                                theme = theme,
                                compact = true,
                                latestEntry = latestEntry,
                                previewText = previewText,
                                previewMime = previewMime,
                                previewUri = previewUri,
                                onCopy = {
                                    if (latestEntry != null) {
                                        onCopyEntryToClipboard(latestEntry)
                                    } else if (!previewText.isNullOrBlank()) {
                                        onCopyTextToClipboard(previewText)
                                    }
                                }
                            )
                        } else {
                            SekaiPlate(
                                theme = theme,
                                cornerRadius = 18.dp,
                                shadowOffset = 3.dp,
                                fillColor = theme.surfaceHover,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "LISTO PARA SINCRONIZAR",
                                        color = theme.ink,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = "Copia en Linux o Android y aparecerá aquí y en el historial con copia en 1 toque.",
                                        color = theme.inkSub,
                                        fontSize = 11.5.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }
                    }

                    SekaiPlate(
                        theme = theme,
                        cornerRadius = 20.dp,
                        shadowOffset = 4.dp,
                        fillColor = theme.surface.copy(alpha = 0.94f),
                        modifier = Modifier
                            .weight(1.05f)
                            .fillMaxHeight()
                    ) {
                        if (state.clipboardEntries.isEmpty()) {
                            EmptyClipboardState(theme = theme)
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item {
                                    Text(
                                        text = "HISTORIAL RECIENTE (${state.clipboardEntries.size})",
                                        color = theme.inkSub,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.8.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                                items(state.clipboardEntries, key = { it.id }) { entry ->
                                    ClipboardHistoryRow(
                                        theme = theme,
                                        entry = entry,
                                        onCopy = { onCopyEntryToClipboard(entry) }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Portrait Single Scrollable Column
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        ClipboardActionsCard(
                            theme = theme,
                            compact = false,
                            onSendClipboardNow = onSendClipboardNow,
                            onRefreshLocal = { localClipboardPreview = readLocalClipboardPreview(context) }
                        )
                    }

                    if (latestEntry != null || !previewText.isNullOrBlank() || !previewUri.isNullOrBlank()) {
                        item {
                            LatestClipCard(
                                theme = theme,
                                compact = false,
                                latestEntry = latestEntry,
                                previewText = previewText,
                                previewMime = previewMime,
                                previewUri = previewUri,
                                onCopy = {
                                    if (latestEntry != null) {
                                        onCopyEntryToClipboard(latestEntry)
                                    } else if (!previewText.isNullOrBlank()) {
                                        onCopyTextToClipboard(previewText)
                                    }
                                }
                            )
                        }
                    }

                    if (state.clipboardEntries.isEmpty()) {
                        item {
                            SekaiPlate(
                                theme = theme,
                                cornerRadius = 20.dp,
                                shadowOffset = 3.5.dp,
                                fillColor = theme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                EmptyClipboardState(theme = theme)
                            }
                        }
                    } else {
                        item {
                            Text(
                                text = "HISTORIAL RECIENTE",
                                color = theme.inkSub,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                        items(state.clipboardEntries, key = { it.id }) { entry ->
                            ClipboardHistoryRow(
                                theme = theme,
                                entry = entry,
                                onCopy = { onCopyEntryToClipboard(entry) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardActionsCard(
    theme: SekaiResolvedTheme,
    compact: Boolean,
    onSendClipboardNow: () -> Unit,
    onRefreshLocal: () -> Unit
) {
    SekaiPlate(
        theme = theme,
        cornerRadius = 20.dp,
        shadowOffset = 3.5.dp,
        fillColor = theme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 10.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SekaiKeyPill(
                theme = theme,
                active = true,
                fillColor = theme.accentA,
                shadowOffset = 3.dp,
                cornerRadius = 14.dp,
                onClick = onSendClipboardNow,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Send,
                        contentDescription = null,
                        tint = Color(0xFF1A1110),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Enviar al PC",
                        color = Color(0xFF1A1110),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            SekaiKeyPill(
                theme = theme,
                fillColor = theme.surfaceHover,
                shadowOffset = 3.dp,
                cornerRadius = 14.dp,
                onClick = onRefreshLocal
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        tint = theme.ink,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Leer local",
                        color = theme.ink,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun LatestClipCard(
    theme: SekaiResolvedTheme,
    compact: Boolean,
    latestEntry: ClipboardEntry?,
    previewText: String?,
    previewMime: String,
    previewUri: String?,
    onCopy: () -> Unit
) {
    SekaiPlate(
        theme = theme,
        cornerRadius = 20.dp,
        shadowOffset = 3.5.dp,
        fillColor = theme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 11.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Último clip ($previewMime)",
                    color = theme.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (latestEntry != null) {
                    Text(
                        text = formatByteSize(latestEntry.sizeBytes),
                        color = theme.inkSub,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (latestEntry?.contentType == ClipboardContentType.IMAGE ||
                previewMime.startsWith("image/", ignoreCase = true)
            ) {
                ImagePreviewBox(
                    theme = theme,
                    localFile = latestEntry?.localFile,
                    uriString = previewUri,
                    maxHeightDp = if (compact) 150 else 230
                )
            } else if (!previewText.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(theme.surfaceHover)
                        .border(1.6.dp, theme.line.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = previewText.take(2000),
                            color = theme.ink,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = if (compact) 6 else 10,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Text(
                    text = previewUri ?: "Contenido binario listo",
                    color = theme.inkSub,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                SekaiKeyPill(
                    theme = theme,
                    active = true,
                    fillColor = theme.accentB,
                    shadowOffset = 2.5.dp,
                    onClick = onCopy
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            tint = Color(0xFF1A1110),
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Copiar en 1 toque",
                            color = Color(0xFF1A1110),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyClipboardState(theme: SekaiResolvedTheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.ContentPaste,
            contentDescription = null,
            tint = theme.inkSub,
            modifier = Modifier.size(34.dp)
        )
        Text(
            text = "Sin transferencias todavía",
            color = theme.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black
        )
        Text(
            text = "Copia texto o una imagen en Android o Linux, o pulsa 'Enviar al PC'.",
            color = theme.inkSub,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ImagePreviewBox(
    theme: SekaiResolvedTheme,
    localFile: File?,
    uriString: String?,
    maxHeightDp: Int
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
            contentDescription = "Vista previa de imagen sincronizada",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = maxHeightDp.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(theme.surfaceHover)
                .border(1.8.dp, theme.line, RoundedCornerShape(14.dp))
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(theme.surfaceHover)
                .border(1.6.dp, theme.line.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Outlined.Image, contentDescription = null, tint = theme.ink)
            Text(
                text = uriString ?: localFile?.name ?: "Imagen recibida",
                color = theme.ink,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ClipboardHistoryRow(
    theme: SekaiResolvedTheme,
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

    SekaiPlate(
        theme = theme,
        cornerRadius = 16.dp,
        borderWidth = 2.dp,
        shadowOffset = 2.5.dp,
        fillColor = theme.surfaceHover,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (entry.direction == ClipboardDirection.INBOUND) theme.accentB else theme.accentA
                    )
                    .border(1.5.dp, theme.line, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = dirIcon,
                    contentDescription = entry.direction.name,
                    tint = Color(0xFF1A1110),
                    modifier = Modifier.size(16.dp)
                )
            }

            Icon(
                imageVector = typeIcon,
                contentDescription = null,
                tint = theme.inkSub,
                modifier = Modifier.size(17.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.textPreview?.take(140) ?: "${entry.mime} (${formatByteSize(entry.sizeBytes)})",
                    color = theme.ink,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${if (entry.direction == ClipboardDirection.INBOUND) "Desde PC" else "Hacia PC"} • ${formatByteSize(entry.sizeBytes)} • $relTime",
                    color = theme.inkSub,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.ContentCopy,
                contentDescription = "Copiar elemento",
                size = 34.dp,
                onClick = onCopy
            )
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
