package org.fedorahosted.freeotp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.databinding.ActivityScanTokenBinding
import org.fedorahosted.freeotp.util.AppSessionManager
import org.fedorahosted.freeotp.util.ImageUtil
import org.fedorahosted.freeotp.util.TokenQRCodeDecoder
import java.util.concurrent.ExecutorService
import javax.inject.Inject

private const val REQUEST_CODE_PERMISSIONS = 10

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

@AndroidEntryPoint
class ScanTokenActivity : AppCompatActivity() {

    @Inject lateinit var tokenQRCodeDecoder: TokenQRCodeDecoder

    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase

    @Inject lateinit var tokenCryptoManager: TokenCryptoManager

    @Inject lateinit var appSessionManager: AppSessionManager

    @Inject lateinit var imageUtil: ImageUtil

    @Inject lateinit var executorService: ExecutorService

    private lateinit var binding: ActivityScanTokenBinding

    private var foundToken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!appSessionManager.ensureAuthenticatedOrFinish(this)) {
            return
        }
        enableEdgeToEdge()

        binding = ActivityScanTokenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.window) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val marginParam = view.layoutParams as android.widget.FrameLayout.LayoutParams
            marginParam.setMargins(
                (64 * resources.displayMetrics.density).toInt() + insets.left,
                (64 * resources.displayMetrics.density).toInt() + insets.top,
                (64 * resources.displayMetrics.density).toInt() + insets.right,
                (64 * resources.displayMetrics.density).toInt() + insets.bottom
            )
            view.layoutParams = marginParam
            windowInsets
        }

        if (allPermissionsGranted()) {
            binding.viewFinder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (isFinishing || isDestroyed) {
                return@addListener
            }

            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val cameraSelector = selectAvailableCamera(cameraProvider)
                if (cameraSelector == null) {
                    Toast.makeText(this, R.string.error_camera_open, Toast.LENGTH_SHORT).show()
                    finish()
                    return@addListener
                }

                val preview = Preview.Builder().apply {
                    setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    setTargetRotation(Surface.ROTATION_0)
                }.build()

                preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)

                val imageAnalysis = ImageAnalysis.Builder().apply {
                    setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    setTargetRotation(Surface.ROTATION_0)
                }.build()

                imageAnalysis.setAnalyzer(executorService) { imageProxy: ImageProxy ->
                    analyzeImage(imageProxy)
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start and bind camera", e)
                Toast.makeText(this, R.string.error_camera_open, Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun selectAvailableCamera(cameraProvider: ProcessCameraProvider): CameraSelector? {
        val choice = chooseCameraChoice(
            hasBackCamera = runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false),
            hasFrontCamera = runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false),
            hasAnyCamera = cameraProvider.availableCameraInfos.isNotEmpty()
        )
        return when (choice) {
            CameraChoice.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraChoice.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            CameraChoice.ANY -> ANY_CAMERA_SELECTOR
            CameraChoice.NONE -> null
        }
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        if (foundToken) {
            imageProxy.close()
            return
        }

        val tokenString = tokenQRCodeDecoder.parseQRCode(imageProxy)

        if (tokenString != null) {
            foundToken = true

            lifecycleScope.launch {
                try {
                    val token = OtpTokenFactory.createFromUri(Uri.parse(tokenString))
                    if (token.imagePath != null) {
                        Glide.with(this@ScanTokenActivity)
                                .load(token.imagePath)
                                .placeholder(R.drawable.scan)
                                .listener(object : RequestListener<Drawable> {
                                    override fun onLoadFailed(e: GlideException?,
                                                              model: Any?,
                                                              target: Target<Drawable>,
                                                              isFirstResource: Boolean): Boolean {
                                        e?.printStackTrace()
                                        return false
                                    }

                                    override fun onResourceReady(resource: Drawable,
                                                                 model: Any,
                                                                 target: Target<Drawable>?,
                                                                 dataSource: DataSource,
                                                                 isFirstResource: Boolean): Boolean {
                                        binding.progress.visibility = View.INVISIBLE
                                        binding.image.alpha = 0.9f
                                        binding.image.postDelayed({
                                            lifecycleScope.launch {
                                                otpTokenDatabase.otpTokenDao().insert(
                                                    tokenCryptoManager.encryptTokenForStorage(token)
                                                )
                                                setResult(RESULT_OK)
                                                finish()
                                            }
                                        }, 2000)
                                        return false
                                    }

                                })
                                .into(binding.image)
                    } else {
                        otpTokenDatabase.otpTokenDao().insert(
                            tokenCryptoManager.encryptTokenForStorage(token)
                        )
                        setResult(RESULT_OK)
                        finish()
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@ScanTokenActivity, R.string.invalid_token_uri_received, Toast.LENGTH_SHORT).show()
                    foundToken = false
                }
            }
        }
        imageProxy.close()
    }


    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                binding.viewFinder.post { startCamera() }
            } else {
                Toast.makeText(this,
                        R.string.error_camera_open,
                        Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
                baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    internal enum class CameraChoice {
        BACK,
        FRONT,
        ANY,
        NONE
    }

    companion object {
        private val TAG = ScanTokenActivity::class.java.simpleName
        private val ANY_CAMERA_SELECTOR: CameraSelector = CameraSelector.Builder().build()

        internal fun chooseCameraChoice(
            hasBackCamera: Boolean,
            hasFrontCamera: Boolean,
            hasAnyCamera: Boolean
        ): CameraChoice {
            return when {
                hasBackCamera -> CameraChoice.BACK
                hasFrontCamera -> CameraChoice.FRONT
                hasAnyCamera -> CameraChoice.ANY
                else -> CameraChoice.NONE
            }
        }
    }
}
