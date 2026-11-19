package org.fedorahosted.freeotp.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.databinding.EditBinding
import org.fedorahosted.freeotp.token.TokenLayout
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.fedorahosted.freeotp.util.ImageUtil
import org.fedorahosted.freeotp.util.Settings
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

    private var _binding: EditBinding? = null
    private val binding get() = _binding!!

    private lateinit var categoryChipHelper: CategoryChipHelper
    private var initialState: InitialTokenState? = null
    private var displayedImageUri: Uri? = null
    private var tokenId: Long = 0L

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
                imageUri = token.imagePath?.toUri()
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

            b.issuer.doAfterTextChanged { updateButtonStates() }
            b.label.doAfterTextChanged { updateButtonStates() }

            b.image.setOnClickListener {
                pickImageLauncher.launch("image/*")
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
                    if (!isAdded || !tokenCryptoManager.isUnlocked()) {
                        return@authenticateForSensitiveAction
                    }
                    val plaintextSecret = tokenCryptoManager.decryptSecret(token)
                    activeBinding.secret.setOnClickListener(null)
                    activeBinding.secret.setTextIsSelectable(true)
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
        displayedImageUri = imageUri
        Glide.with(this)
            .load(imageUri)
            .placeholder(R.drawable.logo)
            .into(b.image)
        b.removeUserTokenImage.isEnabled = displayedImageUri != null
        updateButtonStates()
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
        if (::categoryChipHelper.isInitialized) {
            categoryChipHelper.dismissDialog()
        }
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_TOKEN_ID = "token_id"
        private const val STATE_IMAGE_DISPLAY = "state_edit_image_display"

        fun args(tokenId: Long): Bundle = Bundle().apply {
            putLong(ARG_TOKEN_ID, tokenId)
        }
    }
}
