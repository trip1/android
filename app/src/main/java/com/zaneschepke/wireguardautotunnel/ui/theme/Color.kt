package com.zaneschepke.wireguardautotunnel.ui.theme

import androidx.compose.ui.graphics.Color

val OffWhite = Color(0xFFF3ECE1)
val CoolGray = Color(0xFF99CCFF)
val LightGrey = Color(0xFFECEDEF)
val Aqua = Color(0xFFFF9C00)
val Plantation = Color(0xFF1A2238)
val Shark = Color(0xFF0E1322)
val BalticSea = Color(0xFF060810)

// LCARS Tactical Starfleet Accents
val LcarsAmber = Color(0xFFFF9C00)
val LcarsGold = Color(0xFFFFCC66)
val LcarsBlue = Color(0xFF99CCFF)
val LcarsPeriwinkle = Color(0xFFCC99CC)
val LcarsCrimson = Color(0xFFFF5555)
val LcarsWarpTeal = Color(0xFF33DDFF)

// amoled
val ElectricTeal = Color(0xFFFF9C00)

// Status colors
val SilverTree = Color(0xFF33DDFF)
val AlertRed = Color(0xFFFF5555)
val Straw = Color(0xFFFFCC66)

val Disabled = CoolGray.copy(alpha = 0.4f)

// Other colors
val ConfigHeaderColor = Color(0xFFCC99CC)
val ConfigKeyColor = Color(0xFF99CCFF)
val Heart = Color(0xFFFF6666)

sealed class ThemeColors(
    val background: Color,
    val surface: Color,
    val primary: Color,
    val secondary: Color,
    val onSurface: Color,
    val onBackground: Color,
    val outline: Color,
) {

    data object Light :
        ThemeColors(
            background = LightGrey.copy(alpha = 0.95f),
            surface = OffWhite,
            primary = Aqua,
            secondary = LightGrey,
            onSurface = BalticSea,
            outline = Plantation.copy(alpha = .75f),
            onBackground = BalticSea,
        )

    data object Dark :
        ThemeColors(
            background = BalticSea,
            surface = Shark,
            primary = Aqua,
            secondary = Plantation,
            onSurface = OffWhite,
            outline = CoolGray,
            onBackground = OffWhite,
        )
}
