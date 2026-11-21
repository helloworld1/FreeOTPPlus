package org.fedorahosted.freeotp.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.data.util.TokenImageBackupUtil
import org.fedorahosted.freeotp.databinding.EditBinding
import org.fedorahosted.freeotp.token.TokenLayout
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.fedorahosted.freeotp.util.ClipboardUtil
import org.fedorahosted.freeotp.util.ImageUtil
import org.fedorahosted.freeotp.util.Settings
import org.fedorahosted.freeotp.util.setTokenImagePreview
import java.security.GeneralSecurityException
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class EditTokenFragment : Fragment() {

    private data class InitialTokenState(
        val issuer: String,
        val label: String,
        val categories: List<String>,
        val imageUri: Uri?
    )

    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var tokenCryptoManager: TokenCryptoManager
    @Inject lateinit var settings: Settings
    @Inject lateinit var imageUtil: ImageUtil
    @Inject lateinit var clipboardUtil: ClipboardUtil

    private var _binding: EditBinding? = null
    private val binding get() = _binding!!

    private lateinit var categoryChipHelper: CategoryChipHelper
    private var initialState: InitialTokenState? = null
    private var displayedImageUri: Uri? = null
    private var tokenId: Long = 0L
    private var imagePickerDialog: AlertDialog? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { sourceUri ->
        if (sourceUri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val path = imageUtil.saveImageUriToFile(sourceUri)
                showImage(path)
            } catch (_: Exception) {
                context?.let { Toast.makeText(it, R.string.fail_to_add_image, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = EditBinding.inflate(inflater, container, false)
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

        binding.cancel.setOnClickListener {
            findNavController().navigateUp()
        }

        tokenId = arguments?.getLong(ARG_TOKEN_ID, 0L) ?: 0L
        if (tokenId == 0L) {
            findNavController().popBackStack()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val token = otpTokenDatabase.otpTokenDao().get(tokenId).first()
            val b = _binding ?: return@launch
            if (token == null) {
                findNavController().popBackStack()
                return@launch
            }

            val initial = InitialTokenState(
                issuer = token.issuer ?: "",
                label = token.label,
                categories = CategoryUtil.normalizeCategories(token.categories),
                imageUri = TokenImageBackupUtil.resolveSafeLocalImageFile(
                    token.imagePath,
                    requireContext().filesDir
                )?.toUri()
            )

            categoryChipHelper = CategoryChipHelper(
                requireActivity() as AppCompatActivity,
                b.categoryChipGroup
            ) {
                updateButtonStates()
            }

            val availableCategories = otpTokenDatabase.otpTokenDao().getAllCategories().first()
            if (_binding == null) return@launch
            categoryChipHelper.initialize(
                availableCategories = availableCategories,
                initialSelected = initial.categories,
                savedInstanceState = savedInstanceState
            )

            b.issuer.doAfterTextChanged {
                if (displayedImageUri == null) {
                    updateTokenImagePreview()
                }
                updateButtonStates()
            }
            b.label.doAfterTextChanged {
                if (displayedImageUri == null) {
                    updateTokenImagePreview()
                }
                updateButtonStates()
            }

            b.image.setOnClickListener {
                imagePickerDialog?.dismiss()
                TokenImagePickerHelper.showSourceChooserDialog(
                    context = requireContext(),
                    titleRes = R.string.change_token_image,
                    onBuiltInSelected = { tokenImage ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val path = imageUtil.saveDrawableResToFile(tokenImage.resource)
                                showImage(path)
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
            b.removeUserTokenImage.setOnClickListener {
                showImage(null)
            }
            b.restore.setOnClickListener {
                b.label.setText(initial.label)
                b.issuer.setText(initial.issuer)
                categoryChipHelper.setSelectedCategories(initial.categories)
                b.issuer.setSelection(b.issuer.text?.length ?: 0)
                showImage(initial.imageUri)
            }
            b.save.setOnClickListener {
                saveTokenChanges()
            }

            val restoredHasCustomImageState = savedInstanceState?.containsKey(STATE_IMAGE_DISPLAY) == true
            val initialImage = if (restoredHasCustomImageState) {
                @Suppress("DEPRECATION")
                savedInstanceState?.getParcelable(STATE_IMAGE_DISPLAY)
            } else {
                initial.imageUri
            }

            if (savedInstanceState == null) {
                b.label.setText(initial.label)
                b.issuer.setText(initial.issuer)
                b.issuer.setSelection(b.issuer.text?.length ?: 0)
            }

            b.algorithm.text = token.algorithm
            b.digits.text = token.digits.toString()

            if (token.tokenType == OtpTokenType.HOTP) {
                b.intervalLabel.setText(R.string.counter)
                b.interval.text = TokenLayout.formatHotpCounter(token.counter)
            } else {
                b.intervalLabel.setText(R.string.interval)
                b.interval.text = token.period.toString()
            }

            b.secret.setTextIsSelectable(false)
            b.secret.isFocusableInTouchMode = false
            b.secret.freezesText = false
            b.secret.text = getString(R.string.click_to_display)
            b.secret.setOnClickListener {
                val host = activity as? AppCompatActivity ?: return@setOnClickListener
                AuthenticationUtil.authenticateForSensitiveAction(
                    host,
                    settings,
                    tokenCryptoManager
                ) {
                    val activeBinding = _binding ?: return@authenticateForSensitiveAction
                    if (!isAdded) {
                        return@authenticateForSensitiveAction
                    }
                    if (!tokenCryptoManager.isUnlocked()) {
                        (host as? MainActivity)?.isSessionAuthenticated()
                        return@authenticateForSensitiveAction
                    }
                    val plaintextSecret = try {
                        tokenCryptoManager.decryptSecret(token)
                    } catch (e: GeneralSecurityException) {
                        Log.e(TAG, "Failed decrypting token secret", e)
                        (host as? MainActivity)?.showSnackbar(R.string.invalid_token)
                            ?: Snackbar.make(activeBinding.root, R.string.invalid_token, Snackbar.LENGTH_SHORT).show()
                        return@authenticateForSensitiveAction
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Token vault locked before secret could be revealed", e)
                        if (!tokenCryptoManager.isUnlocked()) {
                            (host as? MainActivity)?.isSessionAuthenticated()
                        } else {
                            (host as? MainActivity)?.showSnackbar(R.string.invalid_token)
                                ?: Snackbar.make(activeBinding.root, R.string.invalid_token, Snackbar.LENGTH_SHORT).show()
                        }
                        return@authenticateForSensitiveAction
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Invalid token secret state", e)
                        (host as? MainActivity)?.showSnackbar(R.string.invalid_token)
                            ?: Snackbar.make(activeBinding.root, R.string.invalid_token, Snackbar.LENGTH_SHORT).show()
                        return@authenticateForSensitiveAction
                    }
                    activeBinding.secret.setOnClickListener(null)
                    activeBinding.secret.setTextIsSelectable(true)
                    activeBinding.secret.customSelectionActionModeCallback = object : ActionMode.Callback {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true
                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            if (item.itemId == android.R.id.copy) {
                                val text = activeBinding.secret.text ?: return false
                                val min = minOf(activeBinding.secret.selectionStart, activeBinding.secret.selectionEnd)
                                    .coerceAtLeast(0)
                                val max = maxOf(activeBinding.secret.selectionStart, activeBinding.secret.selectionEnd)
                                    .coerceAtLeast(0)
                                val selected = if (min < max && max <= text.length) {
                                    text.subSequence(min, max)
                                } else {
                                    text
                                }
                                clipboardUtil.copySensitiveText(getString(R.string.secret), selected)
                                mode.finish()
                                return true
                            }
                            return false
                        }
                        override fun onDestroyActionMode(mode: ActionMode) {}
                    }
                    // Never serialize revealed OTP secrets into Fragment savedInstanceState Bundles
                    activeBinding.secret.freezesText = false
                    activeBinding.secret.text = plaintextSecret
                }
            }

            initialState = initial
            showImage(initialImage)
            updateButtonStates()
        }
    }

    private fun showImage(imageUri: Uri?) {
        val b = _binding ?: return
        val safeFile = TokenImageBackupUtil.resolveSafeLocalImageFile(
            imageUri?.toString(),
            requireContext().filesDir
        )
        displayedImageUri = safeFile?.toUri()
        updateTokenImagePreview()
        b.removeUserTokenImage.isEnabled = displayedImageUri != null
        updateButtonStates()
    }

    private fun updateTokenImagePreview() {
        val b = _binding ?: return
        b.image.setTokenImagePreview(
            imagePath = displayedImageUri?.toString(),
            issuer = b.issuer.text?.toString(),
            label = b.label.text?.toString()
        )
    }

    private fun categoriesDiffer(first: List<String>, second: List<String>): Boolean {
        val setA = CategoryUtil.normalizeCategories(first).map { it.lowercase(Locale.ROOT) }.toSet()
        val setB = CategoryUtil.normalizeCategories(second).map { it.lowercase(Locale.ROOT) }.toSet()
        return setA != setB
    }

    private fun updateButtonStates() {
        val b = _binding ?: return
        val initial = initialState ?: return
        if (!::categoryChipHelper.isInitialized) return

        val label = b.label.text?.toString().orEmpty()
        val issuer = b.issuer.text?.toString().orEmpty()
        val selectedCategories = categoryChipHelper.getSelectedCategories()

        val hasChanges = label != initial.label ||
            issuer != initial.issuer ||
            categoriesDiffer(selectedCategories, initial.categories) ||
            displayedImageUri != initial.imageUri

        b.save.isEnabled = label.isNotEmpty() && hasChanges
        b.restore.isEnabled = hasChanges
    }

    private fun saveTokenChanges() {
        val b = _binding ?: return
        b.save.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val token = otpTokenDatabase.otpTokenDao().get(tokenId).first() ?: run {
                if (isAdded) findNavController().popBackStack()
                return@launch
            }
            val newToken = token.copy(
                issuer = b.issuer.text?.toString()?.takeIf { it.isNotBlank() },
                label = b.label.text?.toString().orEmpty(),
                categories = categoryChipHelper.getSelectedCategories(),
                imagePath = displayedImageUri?.toString()
            )
            otpTokenDatabase.otpTokenDao().update(newToken)
            if (isAdded) {
                findNavController().popBackStack()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::categoryChipHelper.isInitialized) {
            categoryChipHelper.saveInstanceState(outState)
        }
        if (initialState != null) {
            outState.putParcelable(STATE_IMAGE_DISPLAY, displayedImageUri)
        }
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
        private val TAG = EditTokenFragment::class.java.simpleName
        const val ARG_TOKEN_ID = "token_id"
        private const val STATE_IMAGE_DISPLAY = "state_edit_image_display"

        fun args(tokenId: Long): Bundle = Bundle().apply {
            putLong(ARG_TOKEN_ID, tokenId)
        }
    }
}
