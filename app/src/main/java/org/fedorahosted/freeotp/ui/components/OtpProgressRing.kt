package org.fedorahosted.freeotp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.fedorahosted.freeotp.ui.theme.FreeOtpTheme
import org.fedorahosted.freeotp.util.ThemeMode

/**
 * Dual concentric Material 3 OTP countdown progress rings (replacing legacy ProgressCircle View).
 *
 * - Inner filled pie sweep + crisp stroke edge tracks [currentProgress] out of [max] (default 1000).
 * - Outer hollow rounded-cap arc tracks [totalProgress] out of [max] (rendered for TOTP tokens).
 * - Transitions smoothly from [MaterialTheme.colorScheme.primary] to [MaterialTheme.colorScheme.error]
 *   when either arc drops to 25% or below.
 */
@Composable
fun OtpProgressRing(
    currentProgress: Int,
    totalProgress: Int,
    isHotp: Boolean,
    modifier: Modifier = Modifier,
    max: Int = 1000,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val safeMax = if (max > 0) max else 1000

    val innerPercent = currentProgress * 100 / safeMax
    val innerBaseColor = if (innerPercent > 25 || currentProgress <= 0) primaryColor else errorColor

    val outerPercent = totalProgress * 100 / safeMax
    val outerBaseColor = if (outerPercent > 25 || totalProgress <= 0) primaryColor else errorColor

    Canvas(modifier = modifier) {
        val outerStrokePx = 3.5.dp.toPx()
        val innerEdgeStrokePx = 2.dp.toPx()
        val outerPaddingPx = 5.dp.toPx()
        val innerPaddingPx = 15.dp.toPx()

        // 1. Inner filled pie sweep + edge stroke
        val innerInset = innerPaddingPx + innerEdgeStrokePx / 2f
        val innerDiameterWidth = (size.width - innerInset * 2f).coerceAtLeast(0f)
        val innerDiameterHeight = (size.height - innerInset * 2f).coerceAtLeast(0f)
        val innerSweep = (currentProgress.coerceIn(0, safeMax) * 360f) / safeMax

        if (innerDiameterWidth > 0f && innerDiameterHeight > 0f && innerSweep > 0f) {
            val innerTopLeft = Offset(innerInset, innerInset)
            val innerSize = Size(innerDiameterWidth, innerDiameterHeight)
            drawArc(
                color = innerBaseColor.copy(alpha = 0.36f),
                startAngle = -90f,
                sweepAngle = innerSweep,
                useCenter = true,
                topLeft = innerTopLeft,
                size = innerSize,
                style = Fill,
            )
            drawArc(
                color = innerBaseColor.copy(alpha = 0.80f),
                startAngle = -90f,
                sweepAngle = innerSweep,
                useCenter = false,
                topLeft = innerTopLeft,
                size = innerSize,
                style = Stroke(width = innerEdgeStrokePx, cap = StrokeCap.Round),
            )
        }

        // 2. Outer hollow stroke arc (TOTP only)
        if (!isHotp) {
            val outerInset = outerPaddingPx + outerStrokePx / 2f
            val outerDiameterWidth = (size.width - outerInset * 2f).coerceAtLeast(0f)
            val outerDiameterHeight = (size.height - outerInset * 2f).coerceAtLeast(0f)
            val outerSweep = (totalProgress.coerceIn(0, safeMax) * 360f) / safeMax

            if (outerDiameterWidth > 0f && outerDiameterHeight > 0f && outerSweep > 0f) {
                drawArc(
                    color = outerBaseColor.copy(alpha = 0.95f),
                    startAngle = -90f,
                    sweepAngle = outerSweep,
                    useCenter = false,
                    topLeft = Offset(outerInset, outerInset),
                    size = Size(outerDiameterWidth, outerDiameterHeight),
                    style = Stroke(width = outerStrokePx, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OtpProgressRingNormalPreview() {
    FreeOtpTheme(themeMode = ThemeMode.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(8.dp)) {
            OtpProgressRing(
                currentProgress = 680,
                totalProgress = 840,
                isHotp = false,
                modifier = Modifier.size(84.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OtpProgressRingExpiringDarkPreview() {
    FreeOtpTheme(themeMode = ThemeMode.DARK) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(8.dp)) {
            OtpProgressRing(
                currentProgress = 180,
                totalProgress = 180,
                isHotp = false,
                modifier = Modifier.size(84.dp),
            )
        }
    }
}

/**
 * Thin Material 3 OTP countdown progress bar displayed along the bottom edge of a TokenCard.
 *
 * - Tracks [currentProgress] out of [max] (default 1000) across the card width.
 * - Transitions from [MaterialTheme.colorScheme.primary] to [MaterialTheme.colorScheme.error]
 *   when remaining time drops to 25% or below, matching [OtpProgressRing].
 */
@Composable
fun OtpProgressBar(
    currentProgress: Int,
    modifier: Modifier = Modifier,
    max: Int = 1000,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val safeMax = if (max > 0) max else 1000

    val percent = currentProgress * 100 / safeMax
    val barColor = if (percent > 25 || currentProgress <= 0) primaryColor else errorColor
    val fraction = currentProgress.coerceIn(0, safeMax).toFloat() / safeMax.toFloat()

    Canvas(modifier = modifier) {
        drawRect(color = trackColor)

        val barWidth = size.width * fraction
        if (barWidth > 0f) {
            val startX = if (layoutDirection == LayoutDirection.Rtl) {
                size.width - barWidth
            } else {
                0f
            }
            drawRect(
                color = barColor,
                topLeft = Offset(startX, 0f),
                size = Size(width = barWidth, height = size.height),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OtpProgressBarNormalPreview() {
    FreeOtpTheme(themeMode = ThemeMode.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(300.dp).padding(8.dp)) {
            OtpProgressBar(
                currentProgress = 680,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OtpProgressBarExpiringDarkPreview() {
    FreeOtpTheme(themeMode = ThemeMode.DARK) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(300.dp).padding(8.dp)) {
            OtpProgressBar(
                currentProgress = 180,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }
    }
}
