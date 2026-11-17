package com.encore.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// DESIGN.md §4. Poppins (display) and Montserrat (body) TTFs are not bundled yet;
// both families fall back to the system sans-serif until they land in res/font.
val DisplayFamily: FontFamily = FontFamily.SansSerif
val BodyFamily: FontFamily = FontFamily.SansSerif

private val Base = Typography()

val EncoreTypography = Base.copy(
    displayLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = Base.titleLarge.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = Base.bodyMedium.copy(fontFamily = BodyFamily),
    labelLarge = Base.labelLarge.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp),
)
