package com.controid.ui.dock

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.controid.dock.DockAppItem
import com.controid.dock.DockMediaState
import com.controid.service.EcosystemStateHub
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Authentically recreates SekaiPop's halftone + diagonal pastel slant stripes + corner stars/notes backdrop.
 */
@Composable
fun SekaiBackdrop(
    theme: SekaiResolvedTheme,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = theme.backdropWash)

        // Three diagonal pastel slant bands on upper-left and lower-right corners
        val slantWidth = size.minDimension * 0.20f
        val slantBands = listOf(
            Triple(theme.accentA.copy(alpha = if (theme.isDark) 0.14f else 0.24f), -0.04f, 18f),
            Triple(theme.pinkAccent.copy(alpha = if (theme.isDark) 0.12f else 0.20f), 0.12f, 18f),
            Triple(theme.accentB.copy(alpha = if (theme.isDark) 0.13f else 0.22f), 0.28f, 18f)
        )
        slantBands.forEach { (color, xRatio, deg) ->
            rotate(degrees = deg, pivot = Offset(size.width * 0.35f, size.height * 0.35f)) {
                drawRect(
                    color = color,
                    topLeft = Offset(size.width * xRatio, -size.height * 0.45f),
                    size = Size(slantWidth, size.height * 1.9f)
                )
            }
        }

        // Subtle SekaiPop halftone dots along outer corners
        val dotColor = theme.line.copy(alpha = if (theme.isDark) 0.16f else 0.085f)
        val step = 18.dp.toPx()
        val maxCols = (size.width / step).toInt()
        val maxRows = (size.height / step).toInt()
        for (c in 0..maxCols) {
            for (r in 0..maxRows) {
                val x = c * step
                val y = r * step
                val edgeFactor = maxOf(
                    1f - (x / (size.width * 0.36f)).coerceIn(0f, 1f),
                    1f - ((size.width - x) / (size.width * 0.36f)).coerceIn(0f, 1f)
                ) * maxOf(
                    1f - (y / (size.height * 0.35f)).coerceIn(0f, 1f),
                    1f - ((size.height - y) / (size.height * 0.35f)).coerceIn(0f, 1f)
                )
                if (edgeFactor > 0.18f) {
                    drawCircle(
                        color = dotColor,
                        radius = (1.3f + edgeFactor * 2.1f).dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }
        }

        // Playful 4-point Sekai stars on corners
        val starColors = listOf(
            theme.accentA.copy(alpha = if (theme.isDark) 0.36f else 0.52f),
            theme.pinkAccent.copy(alpha = if (theme.isDark) 0.32f else 0.48f),
            theme.accentB.copy(alpha = if (theme.isDark) 0.34f else 0.50f)
        )
        drawSparkStar(Offset(size.width * 0.08f, size.height * 0.12f), 11.dp.toPx(), starColors[0])
        drawSparkStar(Offset(size.width * 0.92f, size.height * 0.15f), 13.dp.toPx(), starColors[1])
        drawSparkStar(Offset(size.width * 0.12f, size.height * 0.88f), 14.dp.toPx(), starColors[2])
        drawSparkStar(Offset(size.width * 0.89f, size.height * 0.85f), 10.dp.toPx(), starColors[0])
    }
}

private fun DrawScope.drawSparkStar(center: Offset, radius: Float, color: Color) {
    val path = Path()
    val points = 8
    for (i in 0 until points) {
        val r = if (i % 2 == 0) radius else radius * 0.36f
        val angle = (i * PI / 4.0) - PI / 2.0
        val px = center.x + (r * cos(angle)).toFloat()
        val py = center.y + (r * sin(angle)).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color = color)
}

/**
 * SekaiPlate / SekaiKey container: thick rounded border + solid crisp offset shadow (no blur).
 * Animates down into the shadow on press for tactile physical button feel.
 */
@Composable
fun SekaiPlate(
    theme: SekaiResolvedTheme,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    borderWidth: Dp = theme.borderWidthDp.dp,
    shadowOffset: Dp = theme.shadowOffsetDp.dp,
    fillColor: Color = theme.surface,
    shadowColor: Color = theme.lineShadow,
    borderColor: Color = theme.line,
    pressed: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val pressProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sekai_press"
    )
    val dx = (shadowOffset.value * pressProgress).dp
    val dy = (shadowOffset.value * pressProgress).dp

    Box(
        modifier = modifier
            .padding(end = shadowOffset, bottom = shadowOffset),
        propagateMinConstraints = true
    ) {
        // Hard offset shadow plate
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset { IntOffset(shadowOffset.roundToPx(), shadowOffset.roundToPx()) }
                .clip(shape)
                .background(shadowColor)
        )
        // Main crisp card body that shifts into the shadow when pressed
        Box(
            modifier = Modifier
                .offset { IntOffset(dx.roundToPx(), dy.roundToPx()) }
                .clip(shape)
                .background(fillColor)
                .border(borderWidth, borderColor, shape),
            contentAlignment = contentAlignment,
            content = content
        )
    }
}

/**
 * Rotated rounded diamond emblem badge matching SekaiPop Settings 01..06 & Banner icon.
 */
@Composable
fun SekaiDiamondBadge(
    theme: SekaiResolvedTheme,
    accentColor: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconTint: Color = theme.ink
) {
    val shape = RoundedCornerShape((size.value * 0.28f).dp)
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.78f)
                .offset(x = 2.dp, y = 2.dp)
                .rotate(45f)
                .clip(shape)
                .background(theme.lineShadow)
        )
        Box(
            modifier = Modifier
                .size(size * 0.78f)
                .rotate(45f)
                .clip(shape)
                .background(accentColor)
                .border(2.2.dp, theme.line, shape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .size(size * 0.44f)
                    .rotate(-45f)
            )
        }
    }
}

/**
 * Top SekaiBanner pill matching Screenshot 2's Settings header capsule.
 */
@Composable
fun SekaiBannerPill(
    theme: SekaiResolvedTheme,
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    SekaiPlate(
        theme = theme,
        cornerRadius = 999.dp,
        shadowOffset = 3.5.dp,
        pressed = pressed && onClick != null,
        fillColor = theme.surface,
        modifier = modifier.then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                )
            } else {
                Modifier
            }
        )
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            theme.accentA.copy(alpha = if (theme.isDark) 0.24f else 0.45f),
                            theme.pinkAccent.copy(alpha = if (theme.isDark) 0.14f else 0.24f),
                            theme.surface
                        )
                    )
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SekaiDiamondBadge(
                    theme = theme,
                    accentColor = theme.accentB,
                    icon = icon,
                    size = 34.dp
                )
                Column(modifier = Modifier.padding(end = 6.dp)) {
                    Text(
                        text = title,
                        color = theme.ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        color = theme.inkSub,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Rounded tactile pill key for header actions, workspace tabs, and media controls.
 */
@Composable
fun SekaiKeyPill(
    theme: SekaiResolvedTheme,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    fillColor: Color = if (active) theme.accentA else theme.surface,
    contentColor: Color = theme.ink,
    shadowOffset: Dp = 3.dp,
    cornerRadius: Dp = 999.dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    SekaiPlate(
        theme = theme,
        cornerRadius = cornerRadius,
        borderWidth = 2.2.dp,
        shadowOffset = shadowOffset,
        fillColor = fillColor,
        pressed = pressed,
        contentAlignment = Alignment.Center,
        modifier = modifier.clickable(
            interactionSource = interaction,
            indication = null
        ) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

/**
 * SekaiPop search pill matching Screenshot 1 & 3's thick-bordered rounded white search bar.
 */
@Composable
fun SekaiSearchPill(
    theme: SekaiResolvedTheme,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    SekaiPlate(
        theme = theme,
        cornerRadius = 999.dp,
        borderWidth = 2.4.dp,
        shadowOffset = 3.5.dp,
        fillColor = theme.surface,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 40.dp else 46.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = theme.inkSub,
                modifier = Modifier.size(if (compact) 18.dp else 20.dp)
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = theme.inkSub,
                        fontSize = if (compact) 13.sp else 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = theme.ink,
                        fontSize = if (compact) 13.sp else 15.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    cursorBrush = SolidColor(theme.accentA),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(theme.chipFill)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Limpiar búsqueda",
                        tint = theme.chipInk,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * Three crisp tilted attribute stripes (`///`) rendered inside Deck cards like Screenshot 2.
 */
@Composable
fun SekaiSlantStripes(
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        colors.take(3).forEach { color ->
            Canvas(modifier = Modifier.size(width = 10.dp, height = 10.dp)) {
                val p = Path().apply {
                    moveTo(size.width * 0.38f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width * 0.62f, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(p, color = color)
            }
        }
    }
}

/**
 * Fallback crisp monogram badge when an app icon hasn't arrived yet or fails to decode.
 */
@Composable
fun SekaiAppIconBox(
    app: DockAppItem,
    icon: ImageBitmap?,
    theme: SekaiResolvedTheme,
    size: Dp,
    cornerRadius: Dp = (size.value * 0.28f).dp,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerRadius)
    val paletteColor = remember(app.id, theme.attributeColors) {
        val colors = theme.attributeColors
        val idx = abs(app.id.hashCode()) % colors.size
        colors[idx]
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(if (icon != null) theme.ringWhite else paletteColor)
            .border(2.dp, theme.line, shape),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = app.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(RoundedCornerShape((cornerRadius.value - 3f).coerceAtLeast(6f).dp))
            )
        } else if (app.terminal) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = app.name,
                tint = theme.ink,
                modifier = Modifier.size(size * 0.52f)
            )
        } else {
            val monogram = app.name
                .trim()
                .split(" ", "-", "_")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercaseChar().toString() }
                .ifEmpty { "?" }
            Text(
                text = monogram,
                color = theme.ink,
                fontSize = (size.value * 0.34f).sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            )
        }
    }
}

/**
 * Interactive touch App Tile for Portrait/Landscape Grids and Sticky Fast Dock.
 * Supports tap (launch/focus) and long-press (actions menu) with press-into-shadow animation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SekaiAppTile(
    app: DockAppItem,
    icon: ImageBitmap?,
    theme: SekaiResolvedTheme,
    isPinned: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showLabel: Boolean = true,
    iconSize: Dp = if (compact) 44.dp else 52.dp
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "tile_scale"
    )
    val tileFill = when {
        app.focused -> theme.surfaceHover
        app.runningCount > 0 -> theme.surface
        else -> theme.surface.copy(alpha = 0.96f)
    }

    Box(
        modifier = modifier
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTap()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                }
            )
    ) {
        SekaiPlate(
            theme = theme,
            cornerRadius = if (compact) 16.dp else 18.dp,
            borderWidth = if (app.focused) 2.8.dp else 2.2.dp,
            borderColor = if (app.focused) theme.accentA else theme.line,
            shadowOffset = if (compact) 2.5.dp else 3.5.dp,
            fillColor = tileFill,
            pressed = pressed,
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (compact) 6.dp else 8.dp,
                        vertical = if (compact) 7.dp else 10.dp
                    )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        SekaiAppIconBox(
                            app = app,
                            icon = icon,
                            theme = theme,
                            size = (iconSize.value * scale).dp
                        )
                        if (isPinned) {
                            Box(
                                modifier = Modifier
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(theme.pinkAccent)
                                    .border(1.5.dp, theme.line, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PushPin,
                                    contentDescription = "Fijada",
                                    tint = Color.White,
                                    modifier = Modifier.size(9.dp)
                                )
                            }
                        }
                    }

                    if (showLabel) {
                        Text(
                            text = app.name,
                            color = theme.ink,
                            fontSize = if (compact) 11.sp else 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 13.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.height(26.dp)
                        )
                    }

                    // Running / Focused SekaiPop diamond indicator bar
                    RunningIndicatorRow(
                        theme = theme,
                        runningCount = app.runningCount,
                        focused = app.focused
                    )
                }
            }
        }
    }
}

@Composable
private fun RunningIndicatorRow(
    theme: SekaiResolvedTheme,
    runningCount: Int,
    focused: Boolean
) {
    Box(
        modifier = Modifier.height(9.dp),
        contentAlignment = Alignment.Center
    ) {
        if (runningCount > 0) {
            val dots = runningCount.coerceIn(1, 3)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(dots) { idx ->
                    val diamondColor = when {
                        focused && idx == 0 -> theme.pinkAccent
                        else -> theme.accentA
                    }
                    Box(
                        modifier = Modifier
                            .size(if (focused) 8.dp else 6.5.dp)
                            .rotate(45f)
                            .background(diamondColor, RoundedCornerShape(1.5.dp))
                            .border(1.3.dp, theme.line, RoundedCornerShape(1.5.dp))
                    )
                }
            }
        }
    }
}

/**
 * SekaiPop Deck Card (01..99) inspired directly by Screenshot 2:
 * Giant muted index number top-right, rotated attribute diamond icon, 3 diagonal slant stripes,
 * app icon thumbnail, title, and category/status subtitle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SekaiDeckCard(
    indexNumber: Int,
    app: DockAppItem,
    icon: ImageBitmap?,
    theme: SekaiResolvedTheme,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = remember(indexNumber, theme.attributeColors) {
        theme.attributeColors[indexNumber % theme.attributeColors.size]
    }
    val stripeColors = remember(indexNumber, theme.attributeColors) {
        val c = theme.attributeColors
        listOf(
            c[indexNumber % c.size],
            c[(indexNumber + 1) % c.size],
            c[(indexNumber + 2) % c.size]
        )
    }

    Box(
        modifier = modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onTap()
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongPress()
            }
        )
    ) {
        SekaiPlate(
            theme = theme,
            cornerRadius = 22.dp,
            borderWidth = if (app.focused) 3.dp else 2.5.dp,
            borderColor = if (app.focused) theme.accentA else theme.line,
            shadowOffset = 4.dp,
            fillColor = if (app.focused) theme.surfaceHover else theme.surface,
            pressed = pressed,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // Big muted index badge top-right ("01", "02", ...)
                Text(
                    text = indexNumber.toString().padStart(2, '0'),
                    color = theme.line.copy(alpha = if (theme.isDark) 0.25f else 0.14f),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.TopEnd)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Tilted icon frame or app icon
                        Box(contentAlignment = Alignment.BottomEnd) {
                            SekaiAppIconBox(
                                app = app,
                                icon = icon,
                                theme = theme,
                                size = 46.dp,
                                cornerRadius = 13.dp
                            )
                            Box(
                                modifier = Modifier
                                    .offset(x = 4.dp, y = 4.dp)
                                    .size(14.dp)
                                    .rotate(45f)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(accent)
                                    .border(1.5.dp, theme.line, RoundedCornerShape(3.dp))
                            )
                        }
                        SekaiSlantStripes(
                            colors = stripeColors,
                            modifier = Modifier.padding(top = 18.dp)
                        )
                    }

                    Text(
                        text = app.name,
                        color = theme.ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val subtitle = when {
                        app.focused -> "Enfocada • toca para alternar"
                        app.runningCount > 1 -> "${app.runningCount} ventanas abiertas"
                        app.runningCount == 1 -> "Abierta • toca para enfocar"
                        !app.genericName.isNullOrBlank() -> app.genericName
                        app.categories.isNotEmpty() -> app.categories.first()
                        else -> "Toca para abrir"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (app.runningCount > 0) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .rotate(45f)
                                    .background(if (app.focused) theme.pinkAccent else theme.accentA)
                                    .border(1.2.dp, theme.line)
                            )
                        }
                        Text(
                            text = subtitle,
                            color = theme.inkSub,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Square/rounded icon key in SekaiPop style (presses into hard offset shadow).
 */
@Composable
fun SekaiIconButton(
    theme: SekaiResolvedTheme,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 38.dp,
    cornerRadius: Dp = 12.dp,
    fillColor: Color = if (active) theme.accentA else theme.surface,
    iconTint: Color = if (active) Color(0xFF1A1110) else theme.ink,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    SekaiPlate(
        theme = theme,
        cornerRadius = cornerRadius,
        borderWidth = 2.2.dp,
        shadowOffset = 2.5.dp,
        fillColor = if (enabled) fillColor else fillColor.copy(alpha = 0.55f),
        pressed = pressed && enabled,
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) iconTint else iconTint.copy(alpha = 0.45f),
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

/**
 * High-contrast status badge pill with rotated diamond dot indicator.
 */
@Composable
fun SekaiStatusBadge(
    theme: SekaiResolvedTheme,
    text: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    fillColor: Color = theme.surfaceHover
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(fillColor)
            .border(1.8.dp, theme.line, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .rotate(45f)
                .background(dotColor, RoundedCornerShape(1.5.dp))
                .border(1.2.dp, theme.line, RoundedCornerShape(1.5.dp))
        )
        Text(
            text = text,
            color = theme.ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Card section header with diamond emblem, title, and status pill.
 */
@Composable
fun SekaiSectionHeader(
    theme: SekaiResolvedTheme,
    icon: ImageVector,
    title: String,
    accentColor: Color,
    badgeText: String,
    badgeColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            SekaiDiamondBadge(
                theme = theme,
                accentColor = accentColor,
                icon = icon,
                size = 32.dp,
                iconTint = Color(0xFF1A1110)
            )
            Text(
                text = title,
                color = theme.ink,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(6.dp))
        SekaiStatusBadge(
            theme = theme,
            text = badgeText,
            dotColor = badgeColor
        )
    }
}

/**
 * Compact monospace metric block used inside Dashboard & Clipboard cards.
 */
@Composable
fun SekaiMetricBox(
    theme: SekaiResolvedTheme,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(theme.surfaceHover)
            .border(1.6.dp, theme.line.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            color = theme.inkSub,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = value,
            color = theme.ink,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Tactile SekaiPop text input pill (used on Trackpad keyboard input).
 */
@Composable
fun SekaiTextInputPill(
    theme: SekaiResolvedTheme,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    SekaiPlate(
        theme = theme,
        cornerRadius = 16.dp,
        borderWidth = 2.2.dp,
        shadowOffset = 2.5.dp,
        fillColor = theme.surfaceHover,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 34.dp else 40.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = theme.inkSub,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                textStyle = TextStyle(
                    color = theme.ink,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                ),
                cursorBrush = SolidColor(theme.accentA),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Shared SekaiPop Theme & Behavior modal accessible from Dock, Dashboard, Trackpad & Clipboard.
 */
@Composable
fun SekaiThemePickerDialog(
    theme: SekaiResolvedTheme,
    selectedPaletteId: String,
    darkMode: Boolean,
    tapAlwaysNew: Boolean,
    onSelectPalette: (String) -> Unit,
    onToggleDark: (Boolean) -> Unit,
    onToggleTapNew: (Boolean) -> Unit,
    onRefreshCatalog: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        SekaiPlate(
            theme = theme,
            cornerRadius = 24.dp,
            shadowOffset = 5.dp,
            fillColor = theme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SekaiDiamondBadge(
                            theme = theme,
                            accentColor = theme.accentA,
                            icon = Icons.Outlined.Palette,
                            size = 34.dp,
                            iconTint = Color(0xFF1A1110)
                        )
                        Text(
                            text = "Estética SekaiPop",
                            color = theme.ink,
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.Close,
                        contentDescription = "Cerrar",
                        onClick = onDismiss
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (darkMode) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                            contentDescription = null,
                            tint = theme.ink,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Modo oscuro",
                            color = theme.ink,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Switch(checked = darkMode, onCheckedChange = onToggleDark)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Abrir siempre nueva ventana",
                            color = theme.ink,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Si está desactivado, enfoca la ventana abierta al pulsar",
                            color = theme.inkSub,
                            fontSize = 11.sp
                        )
                    }
                    Switch(checked = tapAlwaysNew, onCheckedChange = onToggleTapNew)
                }

                Text(
                    text = "Paleta de unidad",
                    color = theme.inkSub,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SekaiPalettes.all.forEach { palette ->
                        val active = palette.id == selectedPaletteId
                        SekaiKeyPill(
                            theme = theme,
                            active = active,
                            fillColor = if (active) palette.accentA else theme.surfaceHover,
                            shadowOffset = 2.5.dp,
                            cornerRadius = 14.dp,
                            onClick = { onSelectPalette(palette.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = palette.name,
                                    color = if (active) Color(0xFF1A1110) else theme.ink,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(palette.border, palette.accentA, palette.accentB, palette.pinkAccent)
                                        .forEach { dotColor ->
                                            Box(
                                                modifier = Modifier
                                                    .size(11.dp)
                                                    .clip(CircleShape)
                                                    .background(dotColor)
                                                    .border(1.2.dp, theme.line, CircleShape)
                                            )
                                        }
                                }
                            }
                        }
                    }
                }

                if (onRefreshCatalog != null) {
                    SekaiKeyPill(
                        theme = theme,
                        fillColor = theme.accentB,
                        shadowOffset = 3.dp,
                        cornerRadius = 14.dp,
                        onClick = {
                            onRefreshCatalog()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                tint = Color(0xFF1A1110),
                                modifier = Modifier.size(17.dp)
                            )
                            Text(
                                text = "Reescanear aplicaciones del escritorio",
                                color = Color(0xFF1A1110),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * High-frequency isolated 16-bar SekaiPop Cava visualizer.
 * Collects [EcosystemStateHub.dockSpectrum] internally so 18+ fps updates never invalidate parent grids.
 * Falls back gracefully to an organic Cava-style rhythmic wave when [isPlaying] is true and Cava reports zeros.
 */
@Composable
fun SekaiCavaEqualizerCanvas(
    theme: SekaiResolvedTheme,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val spectrum by EcosystemStateHub.dockSpectrum.collectAsState()
    val hasLiveSpectrum = remember(spectrum) { spectrum.any { it > 2 } }
    val latestSpectrum by rememberUpdatedState(spectrum)
    val latestPlaying by rememberUpdatedState(isPlaying)
    val latestHasLive by rememberUpdatedState(hasLiveSpectrum)

    val levels = remember { FloatArray(16) { 0f } }
    var frameTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(isPlaying, hasLiveSpectrum) {
        var lastNs = 0L
        var phaseSec = 0f
        while (true) {
            val shouldContinue = withFrameNanos { nowNs ->
                val dt = if (lastNs == 0L) 0.016f else ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastNs = nowNs
                phaseSec += dt

                val live = latestSpectrum
                val liveActive = latestHasLive
                val playing = latestPlaying
                val attack = 1f - exp(-dt * 24f)
                val decay = 1f - exp(-dt * 10f)

                var maxLevel = 0f
                for (i in 0 until 16) {
                    val target = when {
                        liveActive -> ((live.getOrNull(i) ?: 0) / 100f).coerceIn(0.06f, 1f)
                        playing -> {
                            val w1 = 0.5f + 0.5f * sin(phaseSec * 4.7f + i * 0.57f)
                            val w2 = 0.5f + 0.5f * sin(phaseSec * 7.6f - i * 0.91f + 1.2f)
                            val w3 = 0.5f + 0.5f * cos(phaseSec * 3.1f + i * 0.39f)
                            val envelope = 0.64f + 0.36f * sin((i / 15f) * PI.toFloat())
                            (0.16f + (w1 * 0.42f + w2 * 0.30f + w3 * 0.28f) * 0.80f * envelope)
                                .coerceIn(0.14f, 0.96f)
                        }
                        else -> 0f
                    }
                    val cur = levels[i]
                    val alpha = if (target > cur) attack else decay
                    val next = cur + (target - cur) * alpha
                    levels[i] = if (!playing && !liveActive && next < 0.012f) 0f else next
                    if (levels[i] > maxLevel) maxLevel = levels[i]
                }
                frameTick++
                playing || liveActive || maxLevel > 0.012f
            }
            if (!shouldContinue) break
        }
    }

    Canvas(modifier = modifier) {
        // Read frameTick inside draw lambda to invalidate strictly on frame updates
        val tick = frameTick
        @Suppress("UNUSED_EXPRESSION")
        tick

        val barCount = 16
        val gap = (size.width * 0.018f).coerceIn(1.5.dp.toPx(), 4.dp.toPx())
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(2.dp.toPx())
        val corner = CornerRadius(barWidth * 0.48f, barWidth * 0.48f)
        val strokeWidth = 1.2.dp.toPx()
        val dotHeight = minOf(barWidth, 4.dp.toPx())

        for (i in 0 until barCount) {
            val t = i / (barCount - 1f)
            val baseColor = if (t < 0.5f) {
                lerp(theme.accentA, theme.pinkAccent, t * 2f)
            } else {
                lerp(theme.pinkAccent, theme.accentB, (t - 0.5f) * 2f)
            }
            val level = levels[i].coerceIn(0f, 1f)
            val x = i * (barWidth + gap)

            if (level < 0.06f) {
                val dotY = size.height - dotHeight
                drawRoundRect(
                    color = baseColor.copy(alpha = 0.45f),
                    topLeft = Offset(x, dotY),
                    size = Size(barWidth, dotHeight),
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = theme.line.copy(alpha = 0.55f),
                    topLeft = Offset(x, dotY),
                    size = Size(barWidth, dotHeight),
                    cornerRadius = corner,
                    style = Stroke(width = strokeWidth * 0.8f)
                )
            } else {
                val barHeight = maxOf(barWidth, level * (size.height - strokeWidth))
                val topY = (size.height - barHeight).coerceAtLeast(strokeWidth * 0.5f)
                val highlightTop = lerp(baseColor, Color.White, 0.28f)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(highlightTop, baseColor),
                        startY = topY,
                        endY = size.height
                    ),
                    topLeft = Offset(x, topY),
                    size = Size(barWidth, size.height - topY),
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = theme.line,
                    topLeft = Offset(x, topY),
                    size = Size(barWidth, size.height - topY),
                    cornerRadius = corner,
                    style = Stroke(width = strokeWidth)
                )
                if (barHeight > barWidth * 1.8f) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.55f),
                        radius = (barWidth * 0.18f).coerceAtLeast(1.dp.toPx()),
                        center = Offset(x + barWidth * 0.5f, topY + barWidth * 0.48f)
                    )
                }
            }
        }
    }
}

/**
 * SekaiPop Cava Dynamic Music Bar & Quick System Footer.
 * Ultra-slim (~48dp) when paused/idle; expands smoothly with live 16-bar Cava spectrum + marquee track pill when playing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SekaiCavaMediaBar(
    theme: SekaiResolvedTheme,
    media: DockMediaState?,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
    compactLandscape: Boolean = false
) {
    val isPlaying = media?.playing == true
    val isMuted = media?.muted == true
    val titleText = media?.title?.takeIf { it.isNotBlank() }
    val artistText = media?.artist?.takeIf { it.isNotBlank() }
    val volumeLabel = when {
        isMuted -> "MUTE"
        media?.volume != null -> "${media.volume}%"
        else -> null
    }

    SekaiPlate(
        theme = theme,
        cornerRadius = if (compactLandscape) 18.dp else 22.dp,
        borderWidth = 2.4.dp,
        shadowOffset = if (compactLandscape) 2.5.dp else 3.5.dp,
        fillColor = theme.surface,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
    ) {
        if (!isPlaying || compactLandscape) {
            // Ultra-slim single capsule (~48dp Portrait idle, ~40dp Landscape Deck footer)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (compactLandscape) 38.dp else 46.dp)
                    .padding(horizontal = 8.dp, vertical = if (compactLandscape) 4.dp else 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SekaiCavaEqualizerCanvas(
                    theme = theme,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .width(if (compactLandscape) 64.dp else 54.dp)
                        .height(if (compactLandscape) 20.dp else 22.dp)
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(theme.surfaceHover.copy(alpha = 0.75f))
                        .border(1.4.dp, theme.line.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
                        .clickable { onAction("media_play_pause") }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = if (isPlaying) theme.pinkAccent else theme.inkSub,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = titleText ?: if (isPlaying) "Reproduciendo" else "En pausa",
                        color = if (titleText != null || isPlaying) theme.ink else theme.inkSub,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (isPlaying && titleText != null) Modifier.basicMarquee() else Modifier)
                    )
                    if (volumeLabel != null) {
                        Text(
                            text = volumeLabel,
                            color = if (isMuted) theme.pinkAccent else theme.inkSub,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                val btnSize = if (compactLandscape) 28.dp else 30.dp
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.SkipPrevious,
                    contentDescription = "Anterior",
                    size = btnSize,
                    cornerRadius = 9.dp,
                    onClick = { onAction("media_prev") }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = "Play/Pausa",
                    active = isPlaying || titleText != null,
                    size = btnSize,
                    cornerRadius = 9.dp,
                    onClick = { onAction("media_play_pause") }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.SkipNext,
                    contentDescription = "Siguiente",
                    size = btnSize,
                    cornerRadius = 9.dp,
                    onClick = { onAction("media_next") }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.VolumeOff,
                    contentDescription = "Silenciar",
                    active = isMuted,
                    fillColor = if (isMuted) theme.pinkAccent else theme.surface,
                    size = btnSize,
                    cornerRadius = 9.dp,
                    onClick = { onAction("volume_mute") }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.Lock,
                    contentDescription = "Bloquear PC",
                    size = btnSize,
                    cornerRadius = 9.dp,
                    onClick = { onAction("lock") }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.PhotoCamera,
                    contentDescription = "Captura",
                    size = btnSize,
                    cornerRadius = 9.dp,
                    onClick = { onAction("screenshot") }
                )
            }
        } else {
            // Expanded dynamic Cava music capsule when playing in Portrait
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                theme.accentA.copy(alpha = if (theme.isDark) 0.14f else 0.20f),
                                theme.pinkAccent.copy(alpha = if (theme.isDark) 0.10f else 0.16f),
                                theme.surface
                            )
                        )
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SekaiCavaEqualizerCanvas(
                        theme = theme,
                        isPlaying = true,
                        modifier = Modifier
                            .width(98.dp)
                            .height(26.dp)
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(theme.surface.copy(alpha = 0.85f))
                            .border(1.5.dp, theme.line.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .clickable { onAction("media_play_pause") }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = titleText ?: "Audio en reproducción",
                            color = theme.ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee()
                        )
                        if (artistText != null) {
                            Text(
                                text = artistText,
                                color = theme.inkSub,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (volumeLabel != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (isMuted) theme.pinkAccent else theme.accentB)
                                .border(1.6.dp, theme.line, RoundedCornerShape(999.dp))
                                .clickable { onAction("volume_mute") }
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = volumeLabel,
                                color = Color(0xFF1A1110),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.SkipPrevious,
                        contentDescription = "Anterior",
                        size = 31.dp,
                        cornerRadius = 10.dp,
                        onClick = { onAction("media_prev") }
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.Pause,
                        contentDescription = "Pausar",
                        active = true,
                        size = 31.dp,
                        cornerRadius = 10.dp,
                        onClick = { onAction("media_play_pause") }
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.SkipNext,
                        contentDescription = "Siguiente",
                        size = 31.dp,
                        cornerRadius = 10.dp,
                        onClick = { onAction("media_next") }
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.AutoMirrored.Outlined.VolumeDown,
                        contentDescription = "Bajar volumen",
                        size = 31.dp,
                        cornerRadius = 10.dp,
                        onClick = { onAction("volume_down") }
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.AutoMirrored.Outlined.VolumeOff,
                        contentDescription = if (isMuted) "Activar sonido" else "Silenciar",
                        active = isMuted,
                        fillColor = if (isMuted) theme.pinkAccent else theme.surface,
                        size = 31.dp,
                        cornerRadius = 10.dp,
                        onClick = { onAction("volume_mute") }
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = "Subir volumen",
                        size = 31.dp,
                        cornerRadius = 10.dp,
                        onClick = { onAction("volume_up") }
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.Lock,
                        contentDescription = "Bloquear PC",
                        size = 31.dp,
                        cornerRadius = 10.dp,
                        onClick = { onAction("lock") }
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.PhotoCamera,
                        contentDescription = "Captura de pantalla",
                        size = 31.dp,
                        cornerRadius = 10.dp,
                        onClick = { onAction("screenshot") }
                    )
                }
            }
        }
    }
}
