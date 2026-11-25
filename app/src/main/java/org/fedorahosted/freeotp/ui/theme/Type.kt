package org.fedorahosted.freeotp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val FreeOtpTypography = Typography()

val OtpCodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 30.sp,
    lineHeight = 36.sp,
    fontFeatureSettings = "tnum",
    letterSpacing = 0.04.em,
)

val OtpCodeCompactTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 25.sp,
    lineHeight = 32.sp,
    fontFeatureSettings = "tnum",
    letterSpacing = 0.03.em,
)
