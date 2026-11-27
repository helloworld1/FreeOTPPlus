package org.fedorahosted.freeotp.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.theme.ScanScrimColor
import org.fedorahosted.freeotp.util.TokenQRCodeDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

private const val TAG = "ScanTokenScreen"
private val ANY_CAMERA_SELECTOR: CameraSelector = CameraSelector.Builder().build()

object ScanTokenConfig {
    @VisibleForTesting
    @Volatile
    var simulatedQrTextForTest: String? = null
}

internal enum class CameraChoice {
    BACK,
    FRONT,
    ANY,
    NONE,
}

internal fun chooseCameraChoice(
    hasBackCamera: Boolean,
    hasFrontCamera: Boolean,
    hasAnyCamera: Boolean,
): CameraChoice {
    return when {
        hasBackCamera -> CameraChoice.BACK
        hasFrontCamera -> CameraChoice.FRONT
        hasAnyCamera -> CameraChoice.ANY
        else -> CameraChoice.NONE
    }
}

@Composable
fun ScanTokenScreen(
    tokenQRCodeDecoder: TokenQRCodeDecoder,
    executorService: ExecutorService,
    onNavigateBack: () -> Unit,
    onQrScanned: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val currentOnNavigateBack by rememberUpdatedState(onNavigateBack)
    val currentOnQrScanned by rememberUpdatedState(onQrScanned)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val foundTokenFlag = remember { AtomicBoolean(false) }
    val lastInvalidToastMs = remember { AtomicLong(0L) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            hasCameraPermission = true
        } else {
            Toast.makeText(context, R.string.error_camera_open, Toast.LENGTH_SHORT).show()
            currentOnNavigateBack()
        }
    }

    fun processScannedQrText(qrText: String) {
        try {
            val uri = qrText.trim().toUri()
            OtpTokenFactory.createFromUri(uri)
            currentOnNavigateBack()
            currentOnQrScanned(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid token URI scanned", e)
            val now = SystemClock.elapsedRealtime()
            if (now - lastInvalidToastMs.get() > 2_500L) {
                lastInvalidToastMs.set(now)
                Toast.makeText(
                    context,
                    R.string.invalid_token_uri_received,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            foundTokenFlag.set(false)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        val simulated = ScanTokenConfig.simulatedQrTextForTest
        if (simulated != null && foundTokenFlag.compareAndSet(false, true)) {
            ScanTokenConfig.simulatedQrTextForTest = null
            processScannedQrText(simulated)
        }
    }

    DisposableEffect(hasCameraPermission, previewViewRef, lifecycleOwner) {
        val activePreviewView = previewViewRef
        var boundProvider: ProcessCameraProvider? = null

        if (
            hasCameraPermission &&
            activePreviewView != null &&
            ScanTokenConfig.simulatedQrTextForTest == null &&
            !foundTokenFlag.get()
        ) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener(
                {
                    if (foundTokenFlag.get() || ScanTokenConfig.simulatedQrTextForTest != null) {
                        return@addListener
                    }
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        boundProvider = cameraProvider
                        val choice = chooseCameraChoice(
                            hasBackCamera = runCatching {
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                            }.getOrDefault(false),
                            hasFrontCamera = runCatching {
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                            }.getOrDefault(false),
                            hasAnyCamera = cameraProvider.availableCameraInfos.isNotEmpty(),
                        )
                        val selector = when (choice) {
                            CameraChoice.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                            CameraChoice.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                            CameraChoice.ANY -> ANY_CAMERA_SELECTOR
                            CameraChoice.NONE -> null
                        }
                        if (selector == null) {
                            if (!foundTokenFlag.get()) {
                                Toast.makeText(context, R.string.error_camera_open, Toast.LENGTH_SHORT).show()
                                currentOnNavigateBack()
                            }
                            return@addListener
                        }

                        val resolutionSelector = ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .build()

                        val preview = Preview.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setTargetRotation(Surface.ROTATION_0)
                            .build()
                        preview.setSurfaceProvider(activePreviewView.surfaceProvider)

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setTargetRotation(Surface.ROTATION_0)
                            .build()

                        imageAnalysis.setAnalyzer(executorService) { imageProxy: ImageProxy ->
                            if (foundTokenFlag.get()) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            val qrText = try {
                                tokenQRCodeDecoder.parseQRCode(imageProxy)
                            } finally {
                                imageProxy.close()
                            } ?: return@setAnalyzer

                            if (!foundTokenFlag.compareAndSet(false, true)) {
                                return@setAnalyzer
                            }

                            coroutineScope.launch(Dispatchers.Main.immediate) {
                                processScannedQrText(qrText)
                            }
                        }

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start and bind camera", e)
                        if (!foundTokenFlag.get()) {
                            Toast.makeText(context, R.string.error_camera_open, Toast.LENGTH_SHORT).show()
                            currentOnNavigateBack()
                        }
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

        onDispose {
            runCatching { boundProvider?.unbindAll() }
        }
    }

    val accentColor = MaterialTheme.colorScheme.primaryContainer

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewViewRef = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(UiTestTags.SCAN_PREVIEW),
            )
        }

        // Translucent scrim + centered square rounded scan reticle
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .testTag(UiTestTags.SCAN_RETICLE),
        ) {
            drawRect(color = ScanScrimColor)

            val minDim = min(size.width, size.height)
            val windowSide = (minDim - 112.dp.toPx()).coerceAtLeast(180.dp.toPx())
            val left = (size.width - windowSide) / 2f
            val top = (size.height - windowSide) / 2f
            val cornerRadiusPx = 24.dp.toPx()

            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(windowSide, windowSide),
                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                blendMode = BlendMode.Clear,
            )

            drawRoundRect(
                color = Color.White.copy(alpha = 0.72f),
                topLeft = Offset(left, top),
                size = Size(windowSide, windowSide),
                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                style = Stroke(width = 2.dp.toPx()),
            )

            // Crisp corner bracket highlights
            val bracketLength = 28.dp.toPx()
            val bracketStroke = 4.dp.toPx()
            val right = left + windowSide
            val bottom = top + windowSide

            // Top-left
            drawLine(accentColor, Offset(left, top + cornerRadiusPx), Offset(left, top + cornerRadiusPx + bracketLength), bracketStroke, StrokeCap.Round)
            drawLine(accentColor, Offset(left + cornerRadiusPx, top), Offset(left + cornerRadiusPx + bracketLength, top), bracketStroke, StrokeCap.Round)
            // Top-right
            drawLine(accentColor, Offset(right, top + cornerRadiusPx), Offset(right, top + cornerRadiusPx + bracketLength), bracketStroke, StrokeCap.Round)
            drawLine(accentColor, Offset(right - cornerRadiusPx, top), Offset(right - cornerRadiusPx - bracketLength, top), bracketStroke, StrokeCap.Round)
            // Bottom-left
            drawLine(accentColor, Offset(left, bottom - cornerRadiusPx), Offset(left, bottom - cornerRadiusPx - bracketLength), bracketStroke, StrokeCap.Round)
            drawLine(accentColor, Offset(left + cornerRadiusPx, bottom), Offset(left + cornerRadiusPx + bracketLength, bottom), bracketStroke, StrokeCap.Round)
            // Bottom-right
            drawLine(accentColor, Offset(right, bottom - cornerRadiusPx), Offset(right, bottom - cornerRadiusPx - bracketLength), bracketStroke, StrokeCap.Round)
            drawLine(accentColor, Offset(right - cornerRadiusPx, bottom), Offset(right - cornerRadiusPx - bracketLength, bottom), bracketStroke, StrokeCap.Round)
        }

        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(16.dp)
                .align(Alignment.TopStart),
        ) {
            FilledTonalIconButton(
                onClick = onNavigateBack,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .size(44.dp)
                    .testTag(UiTestTags.SCAN_BACK_BUTTON),
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_24px),
                    contentDescription = stringResource(R.string.cancel_text),
                )
            }
        }
    }
}
