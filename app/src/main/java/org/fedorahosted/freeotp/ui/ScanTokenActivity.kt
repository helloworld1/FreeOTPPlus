package org.fedorahosted.freeotp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.media.Image
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraX
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysisConfig
import androidx.camera.core.Preview
import androidx.camera.core.PreviewConfig
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import dagger.android.AndroidInjection
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.databinding.ActivityScanTokenBinding
import org.fedorahosted.freeotp.token.TokenPersistence
import org.fedorahosted.freeotp.util.ImageUtil
import org.fedorahosted.freeotp.util.TokenQRCodeDecoder
import org.fedorahosted.freeotp.util.uiLifecycleScope
import javax.inject.Inject

private const val REQUEST_CODE_PERMISSIONS = 10

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)


class ScanTokenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanTokenBinding

    @Inject lateinit var tokenQRCodeDecoder: TokenQRCodeDecoder

    @Inject lateinit var tokenPersistence: TokenPersistence

    @Inject lateinit var imageUtil: ImageUtil

    private var foundToken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidInjection.inject(this)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_scan_token)

        if (allPermissionsGranted()) {
            binding.viewFinder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }
    }

    private fun startCamera() {
        val previewConfig = PreviewConfig.Builder().apply {
            setTargetResolution(Size(1920, 1080))
        }.build()

        // Build the viewfinder use case
        val preview = Preview(previewConfig)

        // Every time the viewfinder is updated, recompute layout
        preview.setOnPreviewOutputUpdateListener {
            // To update the SurfaceTexture, we have to remove it and re-add it
            val parent = binding.viewFinder.parent as ViewGroup
            parent.removeView(binding.viewFinder)
            parent.addView(binding.viewFinder, 0)

            binding.viewFinder.surfaceTexture = it.surfaceTexture
            updateTransform()
        }


        val imageAnalysisConfig = ImageAnalysisConfig.Builder().apply {
            setTargetResolution(Size(1920, 1080))
        }.build()

        val imageAnalysis = ImageAnalysis(imageAnalysisConfig)
        imageAnalysis.setAnalyzer { image, _ ->
            image?.image?.let { analyzeImage(it) }
        }

        CameraX.bindToLifecycle(this, preview, imageAnalysis)
    }

    private fun updateTransform() {
        val matrix = Matrix()

        // Compute the center of the view finder
        val centerX = binding.viewFinder.width / 2f
        val centerY = binding.viewFinder.height / 2f

        // Correct preview output to account for display rotation
        val rotationDegrees = when(binding.viewFinder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        // Finally, apply transformations to our TextureView
        binding.viewFinder.setTransform(matrix)
    }

    /**
     * Process result from permission request dialog box, has the request
     * been granted? If yes, start Camera. Otherwise display a toast
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                binding.viewFinder.post {
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

    private fun analyzeImage(image: Image) {
        if (foundToken) {
            return
        }

        val tokenString = tokenQRCodeDecoder.parseQRCode(image) ?: return
        foundToken = true

        uiLifecycleScope {
            val token = try {
                tokenPersistence.addFromUriString(tokenString)
            } catch (e: Exception) {
                Toast.makeText(this@ScanTokenActivity, R.string.invalid_token_uri_received, Toast.LENGTH_SHORT).show()
                finish()
                return@uiLifecycleScope
            }

            Toast.makeText(this@ScanTokenActivity, R.string.add_token_success, Toast.LENGTH_SHORT).show()

            if (token.image == null) {
                finish()
                return@uiLifecycleScope
            }

            Picasso.get()
                    .load(token.image)
                    .placeholder(R.drawable.scan)
                    .into(binding.image, object : Callback {
                        override fun onSuccess() {
                            binding.progress.visibility = View.INVISIBLE
                            binding.image.alpha = 0.9f
                            binding.image.postDelayed({
                                finish()
                            }, 2000)
                        }

                        override fun onError(e: java.lang.Exception) {
                            e.printStackTrace()
                            finish()
                        }
                    })
        }
    }

}
