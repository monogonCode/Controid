package com.controid.ui.dock

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

@Immutable
data class SekaiUnitPalette(
    val id: String,
    val name: String,
    val border: Color,
    val accentA: Color,
    val accentB: Color,
    val deep: Color,
    val divider: Color,
    val hoverTint: Color,
    val pinkAccent: Color,
    val mono: Boolean = false
)

@Immutable
data class SekaiResolvedTheme(
    val palette: SekaiUnitPalette,
    val isDark: Boolean,
    val surface: Color,
    val surfaceHover: Color,
    val ringWhite: Color,
    val line: Color,
    val lineShadow: Color,
    val ink: Color,
    val inkSub: Color,
    val chipFill: Color,
    val chipInk: Color,
    val backdropWash: Color,
    val accentA: Color,
    val accentB: Color,
    val pinkAccent: Color,
    val attributeColors: List<Color>,
    val borderWidthDp: Float = 2.6f,
    val shadowOffsetDp: Float = 4f
)

object SekaiPalettes {
    val all: List<SekaiUnitPalette> = listOf(
        SekaiUnitPalette(
            id = "warm_terracotta",
            name = "Terracotta (SekaiPop)",
            border = Color(0xFF561E18),
            accentA = Color(0xFFF2A396),
            accentB = Color(0xFFDFC38C),
            deep = Color(0xFF1A1110),
            divider = Color(0xFFF5DDDA),
            hoverTint = Color(0xFFFFEFEA),
            pinkAccent = Color(0xFFE8889E)
        ),
        SekaiUnitPalette(
            id = "vs",
            name = "Virtual Singer",
            border = Color(0xFF555473),
            accentA = Color(0xFF04FEEE),
            accentB = Color(0xFF00FFCB),
            deep = Color(0xFF232235),
            divider = Color(0xFFE4E4EC),
            hoverTint = Color(0xFFD9FFF6),
            pinkAccent = Color(0xFFFF8FD6)
        ),
        SekaiUnitPalette(
            id = "leoneed",
            name = "Leo/need",
            border = Color(0xFF2D3775),
            accentA = Color(0xFF8EA3FF),
            accentB = Color(0xFF9FD8FF),
            deep = Color(0xFF181E3F),
            divider = Color(0xFFDEE1F2),
            hoverTint = Color(0xFFF1F9FF),
            pinkAccent = Color(0xFFFF8FD6)
        ),
        SekaiUnitPalette(
            id = "mmj",
            name = "MORE MORE JUMP!",
            border = Color(0xFF39662D),
            accentA = Color(0xFFA4EE55),
            accentB = Color(0xFF7FE9A8),
            deep = Color(0xFF223C1B),
            divider = Color(0xFFE3F1DF),
            hoverTint = Color(0xFFECFCF2),
            pinkAccent = Color(0xFFFF8FD6)
        ),
        SekaiUnitPalette(
            id = "vbs",
            name = "Vivid BAD SQUAD",
            border = Color(0xFF561938),
            accentA = Color(0xFFFF5C9A),
            accentB = Color(0xFFFF96C0),
            deep = Color(0xFF43142C),
            divider = Color(0xFFF5DBE8),
            hoverTint = Color(0xFFFFEFF6),
            pinkAccent = Color(0xFFFF8FD6)
        ),
        SekaiUnitPalette(
            id = "wxs",
            name = "Wonderlands×Showtime",
            border = Color(0xFF6A3C11),
            accentA = Color(0xFFFFA51F),
            accentB = Color(0xFFFFD54A),
            deep = Color(0xFF4B2A0C),
            divider = Color(0xFFF9E7D7),
            hoverTint = Color(0xFFFFF9E4),
            pinkAccent = Color(0xFFFF8FD6)
        ),
        SekaiUnitPalette(
            id = "nightcord",
            name = "Nightcord at 25:00",
            border = Color(0xFF43285F),
            accentA = Color(0xFFC08CF2),
            accentB = Color(0xFFE2B5FF),
            deep = Color(0xFF2B1A3D),
            divider = Color(0xFFE8DFF1),
            hoverTint = Color(0xFFFBF4FF),
            pinkAccent = Color(0xFFFF8FD6)
        ),
        SekaiUnitPalette(
            id = "mono",
            name = "Monochrome",
            border = Color(0xFF444444),
            accentA = Color(0xFFF4F4F4),
            accentB = Color(0xFFBDBDBD),
            deep = Color(0xFF1A1A1A),
            divider = Color(0xFFECECEC),
            hoverTint = Color(0xFFE9E9E9),
            pinkAccent = Color(0xFF9E9E9E),
            mono = true
        )
    )

    fun byId(id: String): SekaiUnitPalette =
        all.firstOrNull { it.id == id } ?: all.first()

    fun resolve(palette: SekaiUnitPalette, isDark: Boolean): SekaiResolvedTheme {
        val attributes = if (palette.mono) {
            listOf(
                Color(0xFFE4E4E4),
                Color(0xFFB0B0B0),
                Color(0xFFF6F6F6),
                Color(0xFF8C8C8C),
                Color(0xFFCECECE)
            )
        } else {
            listOf(
                Color(0xFF8EE3EF),
                Color(0xFFFF9ECB),
                Color(0xFFFFE066),
                Color(0xFFB39DDB),
                Color(0xFFA5E6A5)
            )
        }

        return if (!isDark) {
            val baseSurface = Color(0xFFFFFDF9)
            val wash = lerp(Color(0xFFFAF5F0), palette.accentB, 0.10f)
            SekaiResolvedTheme(
                palette = palette,
                isDark = false,
                surface = baseSurface,
                surfaceHover = palette.hoverTint,
                ringWhite = Color.White,
                line = palette.border,
                lineShadow = palette.border,
                ink = palette.border,
                inkSub = palette.border.copy(alpha = 0.67f),
                chipFill = lerp(Color.White, palette.border, 0.11f),
                chipInk = palette.border,
                backdropWash = wash,
                accentA = palette.accentA,
                accentB = palette.accentB,
                pinkAccent = palette.pinkAccent,
                attributeColors = attributes
            )
        } else {
            val darkSurface = lerp(palette.deep, Color.White, 0.08f)
            val darkHover = lerp(darkSurface, Color.White, 0.12f)
            val darkShadow = lerp(palette.deep, Color.Black, 0.45f)
            val darkLine = lerp(palette.border, Color.White, 0.22f)
            val darkWash = lerp(palette.deep, palette.accentB, 0.08f)
            SekaiResolvedTheme(
                palette = palette,
                isDark = true,
                surface = darkSurface,
                surfaceHover = darkHover,
                ringWhite = lerp(Color.White, palette.border, 0.12f),
                line = darkLine,
                lineShadow = darkShadow,
                ink = palette.divider,
                inkSub = palette.divider.copy(alpha = 0.70f),
                chipFill = lerp(palette.deep, Color.White, 0.16f),
                chipInk = Color.White,
                backdropWash = darkWash,
                accentA = palette.accentA,
                accentB = palette.accentB,
                pinkAccent = palette.pinkAccent,
                attributeColors = attributes
            )
        }
    }
}

object SekaiDockPrefs {
    private const val PREFS_NAME = "controid_sekai_dock_prefs"
    private const val KEY_PALETTE = "palette_id"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_TAP_NEW_WINDOW = "tap_always_new"
    private const val KEY_DECK_MODE = "deck_mode"

    fun loadPaletteId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PALETTE, "warm_terracotta") ?: "warm_terracotta"

    fun savePaletteId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PALETTE, id).apply()
    }

    fun loadDarkMode(context: Context, systemDark: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_DARK)) prefs.getBoolean(KEY_DARK, systemDark) else false
    }

    fun saveDarkMode(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, dark).apply()
    }

    fun loadTapAlwaysNew(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TAP_NEW_WINDOW, false)

    fun saveTapAlwaysNew(context: Context, alwaysNew: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TAP_NEW_WINDOW, alwaysNew).apply()
    }

    fun loadDeckMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DECK_MODE, false)

    fun saveDeckMode(context: Context, deckMode: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DECK_MODE, deckMode).apply()
    }
}
