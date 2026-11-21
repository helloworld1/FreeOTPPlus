package org.fedorahosted.freeotp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.databinding.FragmentScanTokenBinding
import org.fedorahosted.freeotp.util.TokenQRCodeDecoder
import java.util.concurrent.ExecutorService
import javax.inject.Inject

@AndroidEntryPoint
class ScanTokenFragment : Fragment() {

    @Inject lateinit var tokenQRCodeDecoder: TokenQRCodeDecoder
    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var tokenCryptoManager: TokenCryptoManager
    @Inject lateinit var executorService: ExecutorService

    private var _binding: FragmentScanTokenBinding? = null
    private val binding get() = _binding!!

    @Volatile
    private var foundToken = false

    @Volatile
    private var lastInvalidToastElapsedMs = 0L

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!isAdded) return@registerForActivityResult
        if (granted) {
            _binding?.viewFinder?.post { startCamera() }
        } else {
            Toast.makeText(requireContext(), R.string.error_camera_open, Toast.LENGTH_SHORT).show()
            popScanDestination()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScanTokenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.window) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val marginParam = v.layoutParams as FrameLayout.LayoutParams
            val baseMargin = (64 * resources.displayMetrics.density).toInt()
            marginParam.setMargins(
                baseMargin + insets.left,
                baseMargin + insets.top,
                baseMargin + insets.right,
                baseMargin + insets.bottom
            )
            v.layoutParams = marginParam
            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.backButtonContainer) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val marginParam = v.layoutParams as FrameLayout.LayoutParams
            val baseMargin = (16 * resources.displayMetrics.density).toInt()
            marginParam.setMargins(
                baseMargin + insets.left,
                baseMargin + insets.top,
                baseMargin + insets.right,
                baseMargin
            )
            v.layoutParams = marginParam
            windowInsets
        }

        if (hasCameraPermission()) {
            binding.viewFinder.post { startCamera() }
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val ctx = context ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        cameraProviderFuture.addListener({
            val activeBinding = _binding ?: return@addListener
            if (!isAdded) return@addListener

            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val cameraSelector = selectAvailableCamera(cameraProvider)
                if (cameraSelector == null) {
                    Toast.makeText(requireContext(), R.string.error_camera_open, Toast.LENGTH_SHORT).show()
                    popScanDestination()
                    return@addListener
                }

                val preview = Preview.Builder().apply {
                    setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    setTargetRotation(Surface.ROTATION_0)
                }.build()

                preview.setSurfaceProvider(activeBinding.viewFinder.surfaceProvider)

                val imageAnalysis = ImageAnalysis.Builder().apply {
                    setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    setTargetRotation(Surface.ROTATION_0)
                }.build()

                imageAnalysis.setAnalyzer(executorService) { imageProxy: ImageProxy ->
                    analyzeImage(imageProxy)
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start and bind camera", e)
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.error_camera_open, Toast.LENGTH_SHORT).show()
                    popScanDestination()
                }
            }
        }, ContextCompat.getMainExecutor(ctx))
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

        val tokenString = try {
            tokenQRCodeDecoder.parseQRCode(imageProxy)
        } finally {
            imageProxy.close()
        } ?: return

        foundToken = true
        // Hop from analyzer background thread onto Fragment's main-thread lifecycleScope
        lifecycleScope.launch {
            if (!isAdded || _binding == null) {
                foundToken = false
                return@launch
            }
            try {
                val token = OtpTokenFactory.createFromUri(tokenString.toUri())
                // Persist immediately so navigating back during any image preview never loses the token
                otpTokenDatabase.otpTokenDao().insert(
                    tokenCryptoManager.encryptTokenForStorage(token)
                )

                val activeBinding = _binding
                if (token.imagePath != null && activeBinding != null && isAdded) {
                    Glide.with(this@ScanTokenFragment)
                        .load(token.imagePath)
                        .placeholder(R.drawable.scan)
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(
                                e: GlideException?,
                                model: Any?,
                                target: Target<Drawable>,
                                isFirstResource: Boolean
                            ): Boolean {
                                Log.w(TAG, "Failed to load scanned token image, finishing scan", e)
                                activeBinding.image.post { popScanDestination() }
                                return false
                            }

                            override fun onResourceReady(
                                resource: Drawable,
                                model: Any,
                                target: Target<Drawable>?,
                                dataSource: DataSource,
                                isFirstResource: Boolean
                            ): Boolean {
                                val b = _binding ?: return false
                                b.progress.visibility = View.INVISIBLE
                                b.image.alpha = 0.9f
                                b.image.postDelayed({ popScanDestination() }, 1500)
                                return false
                            }
                        })
                        .into(activeBinding.image)
                } else {
                    popScanDestination()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Token vault locked while saving scanned token", e)
                val mainActivity = activity as? MainActivity
                if (!tokenCryptoManager.isUnlocked() && mainActivity != null) {
                    mainActivity.isSessionAuthenticated()
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (isAdded && now - lastInvalidToastElapsedMs > 2_500L) {
                        lastInvalidToastElapsedMs = now
                        context?.let {
                            Toast.makeText(it, R.string.invalid_token, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                foundToken = false
            } catch (e: Exception) {
                Log.w(TAG, "Invalid token URI scanned", e)
                val now = SystemClock.elapsedRealtime()
                if (isAdded && now - lastInvalidToastElapsedMs > 2_500L) {
                    lastInvalidToastElapsedMs = now
                    context?.let {
                        Toast.makeText(it, R.string.invalid_token_uri_received, Toast.LENGTH_SHORT).show()
                    }
                }
                foundToken = false
            }
        }
    }

    private fun popScanDestination() {
        if (!isAdded) return
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.scanTokenFragment) {
            navController.popBackStack()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    internal enum class CameraChoice {
        BACK,
        FRONT,
        ANY,
        NONE
    }

    companion object {
        private val TAG = ScanTokenFragment::class.java.simpleName
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
