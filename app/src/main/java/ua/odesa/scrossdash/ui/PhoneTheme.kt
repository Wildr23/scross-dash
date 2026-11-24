package ua.odesa.scrossdash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class SCrossExtendedColors(
    val isDark: Boolean,
    val background: Color,
    val card: Color,
    val cardBorder: Color,
    val innerTile: Color,
    val innerTileSelected: Color,
    val chartWell: Color,
    val chartGrid: Color,
    val progressTrack: Color,
    val navContainer: Color,
    val navIndicator: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentBlue: Color,
    val accentCyan: Color,
    val accentGreen: Color,
    val accentAmber: Color,
    val accentYellow: Color,
    val accentPurple: Color,
    val accentRed: Color,
    val pillNeutralBg: Color,
    val pillNeutralFg: Color,
    val statusOnlineBg: Color,
    val statusOnlineFg: Color,
    val statusDemoBg: Color,
    val statusDemoFg: Color,
    val statusStandbyBg: Color,
    val statusStandbyFg: Color,
    val statusCritBg: Color,
    val statusCritFg: Color,
    val selectedBlueBg: Color,
    val selectedBlueBorder: Color,
    val selectedBlueFg: Color,
    val selectedGreenBg: Color,
    val selectedGreenBorder: Color,
    val selectedGreenFg: Color,
    val refuelBoxBg: Color,
    val sensorBoxBg: Color,
    val ecoPanelBg: Color,
    val ecoPanelInnerBg: Color,
    val ecoPanelFg: Color,
    val ecoPanelDivider: Color,
    val carePanelBg: Color,
    val carePanelInnerBg: Color,
    val carePanelFg: Color,
    val carePanelDivider: Color,
    val infoBannerBg: Color,
    val warningBoxBg: Color,
    val warningBoxFg: Color,
    val alertBannerBg: Color,
    val alertBannerTitle: Color,
    val alertBannerBody: Color,
    val archiveItemBg: Color
)

val DarkExtendedColors = SCrossExtendedColors(
    isDark = true,
    background = Color(0xFF090D16),
    card = Color(0xFF131B2E),
    cardBorder = Color(0xFF24324D),
    innerTile = Color(0xFF1A243B),
    innerTileSelected = Color(0xFF1E2D4A),
    chartWell = Color(0xFF0E1525),
    chartGrid = Color(0xFF1E293B),
    progressTrack = Color(0xFF1E293B),
    navContainer = Color(0xFF101829),
    navIndicator = Color(0xFF1E293B),
    divider = Color(0xFF24324D),
    textPrimary = Color.White,
    textSecondary = Color(0xFFCBD5E1),
    textMuted = Color(0xFF94A3B8),
    accentBlue = Color(0xFF38BDF8),
    accentCyan = Color(0xFF7DD3FC),
    accentGreen = Color(0xFF34D399),
    accentAmber = Color(0xFFFBBF24),
    accentYellow = Color(0xFFEAB308),
    accentPurple = Color(0xFFA78BFA),
    accentRed = Color(0xFFF87171),
    pillNeutralBg = Color(0xFF1E293B),
    pillNeutralFg = Color(0xFFCBD5E1),
    statusOnlineBg = Color(0xFF064E3B),
    statusOnlineFg = Color(0xFF6EE7B7),
    statusDemoBg = Color(0xFF0C4A6E),
    statusDemoFg = Color(0xFF7DD3FC),
    statusStandbyBg = Color(0xFF451A03),
    statusStandbyFg = Color(0xFFFCD34D),
    statusCritBg = Color(0xFF450A0A),
    statusCritFg = Color(0xFFF87171),
    selectedBlueBg = Color(0xFF0C3252),
    selectedBlueBorder = Color(0xFF38BDF8),
    selectedBlueFg = Color(0xFF7DD3FC),
    selectedGreenBg = Color(0xFF063A2A),
    selectedGreenBorder = Color(0xFF10B981),
    selectedGreenFg = Color(0xFF6EE7B7),
    refuelBoxBg = Color(0xFF0F2942),
    sensorBoxBg = Color(0xFF162035),
    ecoPanelBg = Color(0xFF0B1A12),
    ecoPanelInnerBg = Color(0xFF0F2B1A),
    ecoPanelFg = Color(0xFF86EFAC),
    ecoPanelDivider = Color(0xFF1A3424),
    carePanelBg = Color(0xFF110B1E),
    carePanelInnerBg = Color(0xFF1A1030),
    carePanelFg = Color(0xFFC4B5FD),
    carePanelDivider = Color(0xFF2A1E42),
    infoBannerBg = Color(0xFF0F1B2F),
    warningBoxBg = Color(0xFF271C0C),
    warningBoxFg = Color(0xFFFBBF24),
    alertBannerBg = Color(0xFF78350F),
    alertBannerTitle = Color(0xFFFDE68A),
    alertBannerBody = Color(0xFFFEF3C7),
    archiveItemBg = Color(0xFF18243C)
)

val LightExtendedColors = SCrossExtendedColors(
    isDark = false,
    background = Color(0xFFF1F5F9),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFCBD5E1),
    innerTile = Color(0xFFF8FAFC),
    innerTileSelected = Color(0xFFE0F2FE),
    chartWell = Color(0xFFF8FAFC),
    chartGrid = Color(0xFFE2E8F0),
    progressTrack = Color(0xFFE2E8F0),
    navContainer = Color(0xFFFFFFFF),
    navIndicator = Color(0xFFE0F2FE),
    divider = Color(0xFFE2E8F0),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF334155),
    textMuted = Color(0xFF475569),
    accentBlue = Color(0xFF0284C7),
    accentCyan = Color(0xFF0369A1),
    accentGreen = Color(0xFF059669),
    accentAmber = Color(0xFFD97706),
    accentYellow = Color(0xFFCA8A04),
    accentPurple = Color(0xFF7C3AED),
    accentRed = Color(0xFFDC2626),
    pillNeutralBg = Color(0xFFE2E8F0),
    pillNeutralFg = Color(0xFF334155),
    statusOnlineBg = Color(0xFFD1FAE5),
    statusOnlineFg = Color(0xFF065F46),
    statusDemoBg = Color(0xFFE0F2FE),
    statusDemoFg = Color(0xFF0369A1),
    statusStandbyBg = Color(0xFFFEF3C7),
    statusStandbyFg = Color(0xFF92400E),
    statusCritBg = Color(0xFFFEE2E2),
    statusCritFg = Color(0xFFB91C1C),
    selectedBlueBg = Color(0xFFE0F2FE),
    selectedBlueBorder = Color(0xFF0284C7),
    selectedBlueFg = Color(0xFF0369A1),
    selectedGreenBg = Color(0xFFD1FAE5),
    selectedGreenBorder = Color(0xFF059669),
    selectedGreenFg = Color(0xFF047857),
    refuelBoxBg = Color(0xFFE0F2FE),
    sensorBoxBg = Color(0xFFF1F5F9),
    ecoPanelBg = Color(0xFFECFDF5),
    ecoPanelInnerBg = Color(0xFFD1FAE5),
    ecoPanelFg = Color(0xFF065F46),
    ecoPanelDivider = Color(0xFFA7F3D0),
    carePanelBg = Color(0xFFF5F3FF),
    carePanelInnerBg = Color(0xFFEDE9FE),
    carePanelFg = Color(0xFF5B21B6),
    carePanelDivider = Color(0xFFDDD6FE),
    infoBannerBg = Color(0xFFF0F9FF),
    warningBoxBg = Color(0xFFFEF3C7),
    warningBoxFg = Color(0xFF92400E),
    alertBannerBg = Color(0xFFFEF3C7),
    alertBannerTitle = Color(0xFF92400E),
    alertBannerBody = Color(0xFF78350F),
    archiveItemBg = Color(0xFFF8FAFC)
)

private val SCrossDarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF062033),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF062016),
    background = Color(0xFF090D16),
    onBackground = Color.White,
    surface = Color(0xFF131B2E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A243B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF24324D),
    error = Color(0xFFEF4444),
    onError = Color.White
)

private val SCrossLightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color.White,
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    background = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color.White
)

val LocalSCrossColors = staticCompositionLocalOf { DarkExtendedColors }

@Composable
fun SCrossTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val ext = if (darkTheme) DarkExtendedColors else LightExtendedColors
    val colorScheme = if (darkTheme) SCrossDarkColorScheme else SCrossLightColorScheme

    CompositionLocalProvider(LocalSCrossColors provides ext) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
