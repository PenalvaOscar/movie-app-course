package com.encore.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.encore.app.R

// DESIGN.md §4. Both fonts are bundled in res/font (SIL OFL 1.1, texts in assets/licenses/), so they work offline.
// Poppins ships as static files for the three weights we use.
val DisplayFamily = FontFamily(
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold),
)

// Montserrat is one variable file; each Font entry pins the 'wght' axis to its weight.
@OptIn(ExperimentalTextApi::class)
private fun montserrat(weight: FontWeight) = Font(
    R.font.montserrat_variable,
    weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val BodyFamily = FontFamily(
    montserrat(FontWeight.Normal),
    montserrat(FontWeight.Medium),
    montserrat(FontWeight.SemiBold),
)

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
