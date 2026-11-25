package org.fedorahosted.freeotp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.util.TokenImageBackupUtil
import org.fedorahosted.freeotp.ui.theme.FreeOtpTheme
import org.fedorahosted.freeotp.ui.theme.LocalIsDarkTheme
import org.fedorahosted.freeotp.util.ThemeMode
import org.fedorahosted.freeotp.util.TokenCustomImageTransformation
import org.fedorahosted.freeotp.util.extractInitialGlyph
import org.fedorahosted.freeotp.util.matchIssuerWithTokenThumbnail
import org.liberty.android.freeotp.token_images.TokenImage
import java.io.File
import java.util.Locale

internal const val TOKEN_BADGE_CORNER_PERCENT = 17

/**
 * Deterministic 17-color WCAG AA Material badge palette (replacing legacy :text-drawable).
 */
object TokenBadgePalette {
    val MATERIAL_COLORS: IntArray = intArrayOf(
        0xffd32f2f.toInt(),
        0xffc2185b.toInt(),
        0xff9c27b0.toInt(),
        0xff6e44c7.toInt(),
        0xff4c5ec4.toInt(),
        0xff1976d2.toInt(),
        0xff0277bd.toInt(),
        0xff00838f.toInt(),
        0xff00796b.toInt(),
        0xff2e7d32.toInt(),
        0xff4a7f24.toInt(),
        0xffa65e00.toInt(),
        0xffcf3e12.toInt(),
        0xffb8336a.toInt(),
        0xff835d4f.toInt(),
        0xff546e7a.toInt(),
        0xff1b8270.toInt(),
    )

    fun getColor(key: Any): Int {
        return MATERIAL_COLORS[(key.hashCode() and 0x7fffffff) % MATERIAL_COLORS.size]
    }
}

sealed interface TokenAvatarModel {
    data class BuiltInVector(@DrawableRes val resId: Int) : TokenAvatarModel
    data class CustomLocalFile(val file: File) : TokenAvatarModel
    data class MatchedServiceVector(@DrawableRes val resId: Int) : TokenAvatarModel
    data class InitialGlyph(val glyph: String, val backgroundArgb: Int) : TokenAvatarModel
    data object DefaultLogo : TokenAvatarModel
}

fun resolveTokenAvatarModel(
    imagePath: String?,
    issuer: String?,
    label: String?,
    filesDir: File,
): TokenAvatarModel {
    TokenImageBackupUtil.parseBuiltInImageName(imagePath)?.let { builtInName ->
        TokenImage.fromName(builtInName)?.let { builtInIcon ->
            return TokenAvatarModel.BuiltInVector(builtInIcon.resource)
        }
    }

    val safeImageFile = TokenImageBackupUtil.resolveSafeLocalImageFile(imagePath, filesDir)
    if (safeImageFile != null) {
        return TokenAvatarModel.CustomLocalFile(safeImageFile)
    }

    matchIssuerWithTokenThumbnail(issuer, label)?.let { matchedRes ->
        return TokenAvatarModel.MatchedServiceVector(matchedRes)
    }

    val textSource = issuer?.trim()?.takeIf { it.isNotEmpty() }
        ?: label?.trim()?.takeIf { it.isNotEmpty() }
    if (textSource != null) {
        val glyph = extractInitialGlyph(textSource)
        if (glyph.isNotEmpty()) {
            val color = TokenBadgePalette.getColor(textSource.lowercase(Locale.ROOT))
            return TokenAvatarModel.InitialGlyph(glyph = glyph, backgroundArgb = color)
        }
    }

    return TokenAvatarModel.DefaultLogo
}

@Composable
fun TokenAvatar(
    token: OtpToken,
    modifier: Modifier = Modifier,
    size: Dp = 68.dp,
    alpha: Float = 1f,
    imageRefreshVersion: Long = 0L,
    contentDescription: String? = null,
) {
    TokenAvatar(
        imagePath = token.imagePath,
        issuer = token.issuer,
        label = token.label,
        modifier = modifier,
        size = size,
        alpha = alpha,
        imageRefreshVersion = imageRefreshVersion,
        contentDescription = contentDescription,
    )
}

@Composable
fun TokenAvatar(
    imagePath: String?,
    issuer: String?,
    label: String?,
    modifier: Modifier = Modifier,
    size: Dp = 68.dp,
    alpha: Float = 1f,
    imageRefreshVersion: Long = 0L,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val isDarkTheme = LocalIsDarkTheme.current
    val model = remember(imagePath, issuer, label, context.filesDir, imageRefreshVersion) {
        resolveTokenAvatarModel(
            imagePath = imagePath,
            issuer = issuer,
            label = label,
            filesDir = context.filesDir,
        )
    }

    val badgeShape = RoundedCornerShape(percent = TOKEN_BADGE_CORNER_PERCENT)

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                this.alpha = alpha
                // CompositingStrategy.Offscreen when semi-transparent ensures badge background + glyph fade uniformly
                if (alpha < 1f) {
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (model) {
            is TokenAvatarModel.BuiltInVector -> {
                Image(
                    painter = painterResource(model.resId),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(badgeShape),
                )
            }

            is TokenAvatarModel.MatchedServiceVector -> {
                Image(
                    painter = painterResource(model.resId),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(badgeShape),
                )
            }

            is TokenAvatarModel.CustomLocalFile -> {
                val file = model.file
                val loadedBitmap by produceState<ImageBitmap?>(
                    initialValue = null,
                    file.absolutePath,
                    file.length(),
                    file.lastModified(),
                    isDarkTheme,
                    imageRefreshVersion,
                ) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            Glide.with(context.applicationContext)
                                .asBitmap()
                                .load(file)
                                .transform(TokenCustomImageTransformation(isDarkTheme))
                                .signature(
                                    ObjectKey("${file.length()}_${file.lastModified()}_night_${isDarkTheme}_$imageRefreshVersion")
                                )
                                .submit(256, 256)
                                .get()
                                .asImageBitmap()
                        }.getOrNull()
                    }
                }

                val bmp = loadedBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = contentDescription,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(badgeShape),
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.logo),
                        contentDescription = contentDescription,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            is TokenAvatarModel.InitialGlyph -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(badgeShape)
                        .background(Color(model.backgroundArgb)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = model.glyph,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = (size.value * 0.46f).sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }

            TokenAvatarModel.DefaultLogo -> {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TokenAvatarLightPreview() {
    FreeOtpTheme(themeMode = ThemeMode.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(12.dp)) {
            TokenAvatar(
                imagePath = null,
                issuer = "Acme Cloud",
                label = "alice@acme.example",
                size = 68.dp,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TokenAvatarDarkPreview() {
    FreeOtpTheme(themeMode = ThemeMode.DARK) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(12.dp)) {
            TokenAvatar(
                imagePath = "builtin:GitHub",
                issuer = "GitHub",
                label = "octocat@github.com",
                size = 68.dp,
            )
        }
    }
}
