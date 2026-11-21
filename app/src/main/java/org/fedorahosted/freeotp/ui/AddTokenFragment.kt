package org.fedorahosted.freeotp.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.util.TokenImageBackupUtil
import org.fedorahosted.freeotp.databinding.AddBinding
import org.fedorahosted.freeotp.util.ImageUtil
import org.fedorahosted.freeotp.util.setTokenImagePreview
import javax.inject.Inject

@AndroidEntryPoint
class AddTokenFragment : Fragment() {

    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var tokenCryptoManager: TokenCryptoManager
    @Inject lateinit var imageUtil: ImageUtil

    private var _binding: AddBinding? = null
    private val binding get() = _binding!!

    private lateinit var categoryChipHelper: CategoryChipHelper
    private var selectedImageUri: Uri? = null
    private var isFormattingSecret = false
    private var imagePickerDialog: AlertDialog? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { sourceUri ->
        if (sourceUri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val savedUri = imageUtil.saveImageUriToFile(sourceUri)
                applySavedImageUri(savedUri)
            } catch (_: Exception) {
                context?.let { Toast.makeText(it, R.string.fail_to_add_image, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun applySavedImageUri(savedUri: Uri?) {
        val safeFile = TokenImageBackupUtil.resolveSafeLocalImageFile(
            savedUri?.toString(),
            requireContext().filesDir
        )
        selectedImageUri = safeFile?.let { Uri.fromFile(it) }
        updateTokenImagePreview()
    }

    private fun updateTokenImagePreview() {
        val b = _binding ?: return
        b.image.setTokenImagePreview(
            imagePath = selectedImageUri?.toString(),
            issuer = b.issuer.text?.toString(),
            label = b.label.text?.toString()
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = AddBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.buttonLayout) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                resources.getDimensionPixelSize(R.dimen.space_1) + insets.bottom
            )
            windowInsets
        }

        val algorithms = resources.getStringArray(R.array.algorithms)
        val algoAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, algorithms)
        binding.algorithm.setAdapter(algoAdapter)
        val initialAlgo = savedInstanceState?.getString(STATE_ALGORITHM)
            ?: algorithms.getOrElse(SHA1_OFFSET) { "SHA1" }
        binding.algorithm.setText(initialAlgo, false)

        if (savedInstanceState != null) {
            @Suppress("DEPRECATION")
            val restoredUri: Uri? = savedInstanceState.getParcelable(STATE_IMAGE_URI)
            applySavedImageUri(restoredUri)
        } else {
            updateTokenImagePreview()
        }

        categoryChipHelper = CategoryChipHelper(
            requireActivity() as AppCompatActivity,
            binding.categoryChipGroup
        )

        binding.typeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                binding.counterRow.visibility = if (checkedId == R.id.hotp) View.VISIBLE else View.GONE
                validateForm()
            }
        }
        binding.counterRow.visibility =
            if (binding.typeToggleGroup.checkedButtonId == R.id.hotp) View.VISIBLE else View.GONE

        binding.issuer.doAfterTextChanged {
            if (selectedImageUri == null) {
                updateTokenImagePreview()
            }
            validateForm()
        }
        binding.label.doAfterTextChanged {
            if (selectedImageUri == null) {
                updateTokenImagePreview()
            }
            validateForm()
        }
        binding.interval.doAfterTextChanged { validateForm() }
        binding.counter.doAfterTextChanged { validateForm() }

        binding.secret.doAfterTextChanged { s ->
            if (isFormattingSecret) return@doAfterTextChanged
            if (!s.isNullOrEmpty()) {
                isFormattingSecret = true
                try {
                    var haveData = false
                    for (i in s.length - 1 downTo 0) {
                        if (s[i] != '=') {
                            haveData = true
                        } else if (haveData) {
                            s.delete(i, i + 1)
                        }
                    }
                } finally {
                    isFormattingSecret = false
                }
            }
            validateForm()
        }

        binding.image.setOnClickListener {
            imagePickerDialog?.dismiss()
            TokenImagePickerHelper.showSourceChooserDialog(
                context = requireContext(),
                titleRes = R.string.select_token_image,
                onBuiltInSelected = { tokenImage ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val savedUri = imageUtil.saveDrawableResToFile(tokenImage.resource)
                            applySavedImageUri(savedUri)
                        } catch (_: Exception) {
                            context?.let {
                                Toast.makeText(it, R.string.fail_to_add_image, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onDeviceImageRequested = {
                    pickImageLauncher.launch("image/*")
                },
                onDialogChanged = { dialog ->
                    imagePickerDialog = dialog
                }
            )
        }

        binding.cancel.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.add.setOnClickListener {
            submitNewToken()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val categories = otpTokenDatabase.otpTokenDao().getAllCategories().first()
            _binding?.let {
                categoryChipHelper.initialize(
                    availableCategories = categories,
                    initialSelected = emptyList(),
                    savedInstanceState = savedInstanceState
                )
            }
        }

        validateForm()
    }

    private fun validateForm() {
        val b = _binding ?: return
        val rawLabel = b.label.text?.toString().orEmpty()
        val secretWithoutPadding = b.secret.text?.toString().orEmpty().trimEnd('=')
        val interval = b.interval.text?.toString()?.toIntOrNull()
        val isHotp = b.typeToggleGroup.checkedButtonId == R.id.hotp
        val counterValid = !isHotp || b.counter.text?.toString()?.toLongOrNull() != null

        b.add.isEnabled = rawLabel.isNotEmpty() &&
            secretWithoutPadding.length >= 8 &&
            interval != null &&
            interval > 0 &&
            counterValid
    }

    private fun submitNewToken() {
        val b = _binding ?: return
        val rawIssuer = b.issuer.text?.toString().orEmpty()
        val rawLabel = b.label.text?.toString().orEmpty()
        val secret = b.secret.text?.toString().orEmpty()
        val algorithm = b.algorithm.text?.toString().orEmpty().ifBlank { "SHA1" }
        val interval = b.interval.text?.toString()?.toIntOrNull() ?: return
        if (interval <= 0) return

        val isHotp = b.typeToggleGroup.checkedButtonId == R.id.hotp
        val initialCounter = if (isHotp) {
            b.counter.text?.toString()?.toLongOrNull() ?: return
        } else {
            0L
        }
        val digits = when (b.digitsToggleGroup.checkedButtonId) {
            R.id.digits5 -> 5
            R.id.digits7 -> 7
            R.id.digits8 -> 8
            else -> 6
        }

        b.add.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = OtpTokenFactory.create(
                    type = if (isHotp) OtpTokenType.HOTP else OtpTokenType.TOTP,
                    issuer = rawIssuer,
                    label = rawLabel,
                    secret = secret,
                    algorithm = algorithm,
                    digits = digits,
                    period = interval,
                    initialCounter = initialCounter,
                    imagePath = selectedImageUri?.toString(),
                    categories = categoryChipHelper.getSelectedCategories()
                )
                otpTokenDatabase.otpTokenDao().insert(tokenCryptoManager.encryptTokenForStorage(token))
                if (isAdded) {
                    findNavController().popBackStack()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                _binding?.add?.isEnabled = true
                val mainActivity = activity as? MainActivity
                if (!tokenCryptoManager.isUnlocked() && mainActivity != null) {
                    mainActivity.isSessionAuthenticated()
                } else {
                    mainActivity?.showSnackbar(R.string.invalid_token)
                }
            } catch (_: Exception) {
                _binding?.add?.isEnabled = true
                (activity as? MainActivity)?.showSnackbar(R.string.invalid_token_uri_received)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::categoryChipHelper.isInitialized) {
            categoryChipHelper.saveInstanceState(outState)
        }
        _binding?.algorithm?.text?.toString()?.let {
            outState.putString(STATE_ALGORITHM, it)
        }
        outState.putParcelable(STATE_IMAGE_URI, selectedImageUri)
    }

    override fun onDestroyView() {
        imagePickerDialog?.dismiss()
        imagePickerDialog = null
        if (::categoryChipHelper.isInitialized) {
            categoryChipHelper.dismissDialog()
        }
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val SHA1_OFFSET = 1
        private const val STATE_ALGORITHM = "state_selected_algorithm"
        private const val STATE_IMAGE_URI = "state_selected_image_uri"
    }
}
