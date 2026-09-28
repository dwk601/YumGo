package com.dwk.yumgo.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Warm paper and deep herb. Fixed roles stay the same in light and dark.
private val Herb = Color(0xFF1B5E45)
private val HerbInk = Color(0xFF002114)
private val HerbDeep = Color(0xFF003826)
private val HerbContainer = Color(0xFF005137)
private val HerbSoft = Color(0xFFA8E8C8)
private val HerbMist = Color(0xFF8DD4B0)

private val Stone = Color(0xFF4E5F56)
private val StoneInk = Color(0xFF0E1F18)
private val StoneDeep = Color(0xFF21362D)
private val StoneContainer = Color(0xFF364B42)
private val StoneSoft = Color(0xFFD5E6DC)
private val StoneMist = Color(0xFFB9CAC0)

private val Spice = Color(0xFF8A4B12)
private val SpiceInk = Color(0xFF2D1600)
private val SpiceDeep = Color(0xFF4A2800)
private val SpiceContainer = Color(0xFF6A3800)
private val SpiceSoft = Color(0xFFFFDCBE)
private val SpiceMist = Color(0xFFF5C08A)

private val Paper = Color(0xFFF7F6F2)
private val PaperLowest = Color(0xFFFFFFFF)
private val PaperLow = Color(0xFFF1F0EA)
private val PaperContainer = Color(0xFFEBEAE4)
private val PaperHigh = Color(0xFFE6E5DF)
private val PaperHighest = Color(0xFFE0DFD9)
private val PaperDim = Color(0xFFD8D7D1)
private val Ink = Color(0xFF1C1C19)
private val InkMuted = Color(0xFF444842)
private val Line = Color(0xFF747873)
private val LineSoft = Color(0xFFC4C7C2)

private val Night = Color(0xFF131411)
private val NightLowest = Color(0xFF0E0F0C)
private val NightLow = Color(0xFF1C1C19)
private val NightContainer = Color(0xFF201F1C)
private val NightHigh = Color(0xFF2A2A26)
private val NightHighest = Color(0xFF353530)
private val NightBright = Color(0xFF393A36)
private val Foam = Color(0xFFE6E5DF)
private val FoamMuted = Color(0xFFC4C7C2)
private val NightLine = Color(0xFF8E928C)

private val Error = Color(0xFFBA1A1A)
private val OnError = Color(0xFFFFFFFF)
private val ErrorContainer = Color(0xFFFFDAD6)
private val OnErrorContainer = Color(0xFF410002)
private val ErrorDark = Color(0xFFFFB4AB)
private val OnErrorDark = Color(0xFF690005)
private val ErrorContainerDark = Color(0xFF93000A)
private val OnErrorContainerDark = Color(0xFFFFDAD6)

internal val LightColorScheme =
  lightColorScheme(
    primary = Herb,
    onPrimary = Color.White,
    primaryContainer = HerbSoft,
    onPrimaryContainer = HerbInk,
    inversePrimary = HerbMist,
    secondary = Stone,
    onSecondary = Color.White,
    secondaryContainer = StoneSoft,
    onSecondaryContainer = StoneInk,
    tertiary = Spice,
    onTertiary = Color.White,
    tertiaryContainer = SpiceSoft,
    onTertiaryContainer = SpiceInk,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperHighest,
    onSurfaceVariant = InkMuted,
    surfaceTint = Herb,
    inverseSurface = Color(0xFF31312E),
    inverseOnSurface = Color(0xFFF4F3EE),
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    outline = Line,
    outlineVariant = LineSoft,
    scrim = Color.Black,
    surfaceBright = Paper,
    surfaceDim = PaperDim,
    surfaceContainerLowest = PaperLowest,
    surfaceContainerLow = PaperLow,
    surfaceContainer = PaperContainer,
    surfaceContainerHigh = PaperHigh,
    surfaceContainerHighest = PaperHighest,
    primaryFixed = HerbSoft,
    primaryFixedDim = HerbMist,
    onPrimaryFixed = HerbInk,
    onPrimaryFixedVariant = HerbDeep,
    secondaryFixed = StoneSoft,
    secondaryFixedDim = StoneMist,
    onSecondaryFixed = StoneInk,
    onSecondaryFixedVariant = StoneContainer,
    tertiaryFixed = SpiceSoft,
    tertiaryFixedDim = SpiceMist,
    onTertiaryFixed = SpiceInk,
    onTertiaryFixedVariant = SpiceContainer,
  )

internal val DarkColorScheme =
  darkColorScheme(
    primary = HerbMist,
    onPrimary = HerbDeep,
    primaryContainer = HerbContainer,
    onPrimaryContainer = HerbSoft,
    inversePrimary = Herb,
    secondary = StoneMist,
    onSecondary = StoneDeep,
    secondaryContainer = StoneContainer,
    onSecondaryContainer = StoneSoft,
    tertiary = SpiceMist,
    onTertiary = SpiceDeep,
    tertiaryContainer = SpiceContainer,
    onTertiaryContainer = SpiceSoft,
    background = Night,
    onBackground = Foam,
    surface = Night,
    onSurface = Foam,
    surfaceVariant = InkMuted,
    onSurfaceVariant = FoamMuted,
    surfaceTint = HerbMist,
    inverseSurface = Foam,
    inverseOnSurface = Color(0xFF31312E),
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    outline = NightLine,
    outlineVariant = InkMuted,
    scrim = Color.Black,
    surfaceBright = NightBright,
    surfaceDim = Night,
    surfaceContainerLowest = NightLowest,
    surfaceContainerLow = NightLow,
    surfaceContainer = NightContainer,
    surfaceContainerHigh = NightHigh,
    surfaceContainerHighest = NightHighest,
    primaryFixed = HerbSoft,
    primaryFixedDim = HerbMist,
    onPrimaryFixed = HerbInk,
    onPrimaryFixedVariant = HerbDeep,
    secondaryFixed = StoneSoft,
    secondaryFixedDim = StoneMist,
    onSecondaryFixed = StoneInk,
    onSecondaryFixedVariant = StoneContainer,
    tertiaryFixed = SpiceSoft,
    tertiaryFixedDim = SpiceMist,
    onTertiaryFixed = SpiceInk,
    onTertiaryFixedVariant = SpiceContainer,
  )
