package org.fedorahosted.freeotp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import dagger.android.AndroidInjection
import kotlinx.android.synthetic.main.activity_scan_token.*
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.token.TokenPersistence
import org.fedorahosted.freeotp.util.ImageUtil
import org.fedorahosted.freeotp.util.TokenQRCodeDecoder
import org.fedorahosted.freeotp.util.uiLifecycleScope
import java.util.concurrent.ExecutorService
import javax.inject.Inject

private const val REQUEST_CODE_PERMISSIONS = 10

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)


class ScanTokenActivity : AppCompatActivity() {

    @Inject lateinit var tokenQRCodeDecoder: TokenQRCodeDecoder

    @Inject lateinit var tokenPersistence: TokenPersistence

    @Inject lateinit var imageUtil: ImageUtil

    @Inject lateinit var executorService: ExecutorService

    private var foundToken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidInjection.inject(this)
        setContentView(R.layout.activity_scan_token)

        if (allPermissionsGranted()) {
            view_finder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {

            val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

            val preview = Preview.Builder().apply {
                setTargetAspectRatio(AspectRatio.RATIO_16_9)
                setTargetRotation(Surface.ROTATION_0)
            }.build()

            preview.setSurfaceProvider(view_finder.surfaceProvider)

            val imageAnalysis = ImageAnalysis.Builder().apply {
                setBackgroundExecutor(executorService)
                setTargetAspectRatio(AspectRatio.RATIO_16_9)
                setTargetRotation(Surface.ROTATION_0)
            }.build()

            imageAnalysis.setAnalyzer(executorService, ImageAnalysis.Analyzer { imageProxy: ImageProxy ->
                analyzeImage(imageProxy)
            })

            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Process result from permission request dialog box, has the request
     * been granted? If yes, start Camera. Otherwise display a toast
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                view_finder.post {
                    startCamera()
                }
            } else {
                Toast.makeText(this, R.string.camera_permission_denied_text, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /**
     * Check if all permission specified in the manifest have been granted
     */
    private fun allPermissionsGranted(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        if (foundToken) {
            return
        }

        val tokenString = imageProxy.use { image ->
            tokenQRCodeDecoder.parseQRCode(image) ?: return
        }

        foundToken = true

        uiLifecycleScope {
            val token = try {
                tokenPersistence.addFromUriString(tokenString)
            } catch (e: Throwable) {
                Toast.makeText(this@ScanTokenActivity, R.string.invalid_token_uri_received, Toast.LENGTH_SHORT).show()

                finish()
                return@uiLifecycleScope
            }

            Toast.makeText(this@ScanTokenActivity, R.string.add_token_success, Toast.LENGTH_SHORT).show()

            setResult(RESULT_OK)
            if (token.image == null) {
                finish()
                return@uiLifecycleScope
            }

            Glide.with(this@ScanTokenActivity)
                    .load(token.image)
                    .placeholder(R.drawable.scan)
                    .listener(object: RequestListener<Drawable> {
                        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                            e?.printStackTrace()
                            finish()
                            return false
                        }

                        override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                            progress.visibility = View.INVISIBLE
                            image.alpha = 0.9f
                            image.postDelayed({
                                finish()
                            }, 2000)
                            return false 
                        }

                    })
                    .into(image)
        }
    }

}
