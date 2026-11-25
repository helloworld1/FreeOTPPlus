package org.fedorahosted.freeotp.ui.dialogs

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.ui.components.SecureDialogWindowEffect
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.components.rememberSecureDialogProperties
import org.fedorahosted.freeotp.ui.theme.QrSurfaceColor
import java.security.GeneralSecurityException

private const val TAG = "ShareQrCodeDialog"
private const val QR_ENCODE_SIZE_PX = 512

private data class PreparedQrData(
    val title: String,
    val subtitle: String?,
    val uriString: String,
    val bitmap: ImageBitmap,
)

@Composable
fun ShareQrCodeDialog(
    storedToken: OtpToken,
    tokenCryptoManager: TokenCryptoManager,
    isSessionAuthenticated: () -> Boolean,
    onCopyUri: (String) -> Unit,
    onInvalidTokenError: () -> Unit,
    onDismiss: () -> Unit,
) {
    var qrData by remember(storedToken.id) { mutableStateOf<PreparedQrData?>(null) }

    LaunchedEffect(storedToken) {
        if (!tokenCryptoManager.isUnlocked() || !isSessionAuthenticated()) {
            onDismiss()
            return@LaunchedEffect
        }

        val plainToken = try {
            tokenCryptoManager.decryptToken(storedToken)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Failed decrypting token for QR code", e)
            onInvalidTokenError()
            onDismiss()
            return@LaunchedEffect
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Token vault locked before QR code could be generated", e)
            if (tokenCryptoManager.isUnlocked()) {
                onInvalidTokenError()
            } else {
                isSessionAuthenticated()
            }
            onDismiss()
            return@LaunchedEffect
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid token for QR code", e)
            onInvalidTokenError()
            onDismiss()
            return@LaunchedEffect
        }

        val generated = try {
            val uriString = OtpTokenFactory.toUri(plainToken).toString()
            val imageBitmap = withContext(Dispatchers.Default) {
                encodeQrImageBitmap(uriString, QR_ENCODE_SIZE_PX)
            }
            PreparedQrData(
                title = if (plainToken.issuer.isNullOrBlank()) plainToken.label else plainToken.issuer.orEmpty(),
                subtitle = plainToken.label.takeIf { !plainToken.issuer.isNullOrBlank() && it.isNotBlank() },
                uriString = uriString,
                bitmap = imageBitmap,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed generating QR code", e)
            null
        }

        if (generated != null) {
            qrData = generated
        } else {
            onInvalidTokenError()
            onDismiss()
        }
    }

    val currentQr = qrData
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = rememberSecureDialogProperties(),
        title = {
            SecureDialogWindowEffect()
            Text(text = currentQr?.title ?: stringResource(R.string.share_qr_code))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (currentQr?.subtitle != null) {
                    Text(
                        text = currentQr.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .testTag(UiTestTags.SHARE_QR_SUBTITLE),
                    )
                }

                // Always keep #FFFFFF QR surface even in Dark Mode so camera scanners decode reliably
                OutlinedCard(
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Surface(color = QrSurfaceColor) {
                        Box(
                            modifier = Modifier
                                .size(240.dp)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (currentQr != null) {
                                Image(
                                    bitmap = currentQr.bitmap,
                                    contentDescription = stringResource(R.string.share_qr_code),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(216.dp)
                                        .testTag(UiTestTags.SHARE_QR_IMAGE),
                                )
                            } else {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = {
                        val active = currentQr ?: return@FilledTonalButton
                        if (!tokenCryptoManager.isUnlocked() || !isSessionAuthenticated()) {
                            onDismiss()
                            return@FilledTonalButton
                        }
                        onCopyUri(active.uriString)
                        onDismiss()
                    },
                    enabled = currentQr != null,
                    modifier = Modifier.testTag(UiTestTags.COPY_URI_BUTTON),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.content_copy_24px),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.copy_token_uri))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTestTags.DIALOG_POSITIVE_BUTTON),
            ) {
                Text(text = stringResource(R.string.ok_text))
            }
        },
    )
}

internal fun encodeQrImageBitmap(contents: String, sizePx: Int): ImageBitmap {
    val encoded = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val rowOffset = y * sizePx
        for (x in 0 until sizePx) {
            pixels[rowOffset + x] = if (encoded.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565).asImageBitmap()
}
