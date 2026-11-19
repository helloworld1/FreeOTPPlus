package org.fedorahosted.freeotp.ui

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.databinding.AddBinding
import org.fedorahosted.freeotp.util.ImageUtil
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AddTokenFragment : Fragment() {

    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var tokenCryptoManager: TokenCryptoManager
    @Inject lateinit var imageUtil: ImageUtil

    private var _binding: AddBinding? = null
    private val binding get() = _binding!!

    private lateinit var categoryChipHelper: CategoryChipHelper
    private var mImageURL: Uri? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { sourceUri ->
        if (sourceUri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val savedUri = imageUtil.saveImageUriToFile(sourceUri)
                mImageURL = savedUri
                _binding?.let { b ->
                    Glide.with(this@AddTokenFragment)
                        .load(savedUri)
                        .placeholder(R.drawable.logo)
                        .into(b.image)
                }
            } catch (_: Exception) {
                Toast.makeText(requireContext(), R.string.fail_to_add_image, Toast.LENGTH_SHORT).show()
            }
        }
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
            mImageURL = savedInstanceState.getParcelable(STATE_IMAGE_URI)
            if (mImageURL != null) {
                Glide.with(this)
                    .load(mImageURL)
                    .placeholder(R.drawable.logo)
                    .into(binding.image)
            }
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

        binding.issuer.doAfterTextChanged { validateForm() }
        binding.label.doAfterTextChanged { validateForm() }
        binding.interval.doAfterTextChanged { validateForm() }
        binding.counter.doAfterTextChanged { validateForm() }

        binding.secret.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validateForm()
            }
            override fun afterTextChanged(s: Editable?) {
                if (!s.isNullOrEmpty()) {
                    var haveData = false
                    for (i in s.length - 1 downTo 0) {
                        val c = s[i]
                        if (c != '=') {
                            haveData = true
                        } else if (haveData) {
                            s.delete(i, i + 1)
                        }
                    }
                }
                validateForm()
            }
        })

        binding.image.setOnClickListener {
            pickImageLauncher.launch("image/*")
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
        val rawIssuer = b.issuer.text?.toString().orEmpty()
        val rawLabel = b.label.text?.toString().orEmpty()
        val secretWithoutPadding = b.secret.text?.toString().orEmpty().trimEnd('=')
        val interval = b.interval.text?.toString()?.toIntOrNull()
        val isHotp = b.typeToggleGroup.checkedButtonId == R.id.hotp
        val counterValid = !isHotp || b.counter.text?.toString()?.toLongOrNull() != null
        val pathValid = rawLabel.isNotEmpty() && !(rawIssuer.isBlank() && rawLabel.trim('/').isEmpty())

        b.add.isEnabled = pathValid &&
            secretWithoutPadding.length >= 8 &&
            interval != null &&
            interval > 0 &&
            counterValid
    }

    private fun submitNewToken() {
        val b = _binding ?: return
        val rawIssuer = b.issuer.text?.toString().orEmpty()
        val rawLabel = b.label.text?.toString().orEmpty()
        val issuer = Uri.encode(rawIssuer)
        val label = Uri.encode(rawLabel)
        val secret = Uri.encode(b.secret.text?.toString().orEmpty())
        val algorithm = b.algorithm.text?.toString().orEmpty().ifBlank { "SHA1" }.lowercase(Locale.US)
        val interval = b.interval.text?.toString()?.toIntOrNull() ?: return
        if (interval <= 0) return

        val isHotp = b.typeToggleGroup.checkedButtonId == R.id.hotp
        val digits = when (b.digitsToggleGroup.checkedButtonId) {
            R.id.digits5 -> 5
            R.id.digits7 -> 7
            R.id.digits8 -> 8
            else -> 6
        }

        val path = when {
            rawIssuer.isNotBlank() -> "$issuer:$label"
            rawLabel.contains(':') -> ":$label"
            else -> label
        }

        var uri = String.format(
            Locale.US,
            "otpauth://%sotp/%s?secret=%s&algorithm=%s&digits=%d&period=%d",
            if (isHotp) "h" else "t",
            path,
            secret,
            algorithm,
            digits,
            interval
        )

        if (rawIssuer.isNotBlank()) {
            uri += String.format(Locale.US, "&issuer=%s", issuer)
        }
        if (isHotp) {
            val counter = b.counter.text?.toString()?.toLongOrNull() ?: return
            uri += String.format(Locale.US, "&counter=%d", counter)
        }
        if (mImageURL != null) {
            val enc = URLEncoder.encode(mImageURL.toString(), "utf-8")
            uri += String.format(Locale.US, "&image=%s", enc)
        }

        b.add.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = OtpTokenFactory.createFromUri(Uri.parse(uri)).copy(
                    issuer = rawIssuer.takeIf { it.isNotBlank() },
                    label = rawLabel,
                    categories = categoryChipHelper.getSelectedCategories()
                )
                otpTokenDatabase.otpTokenDao().insert(tokenCryptoManager.encryptTokenForStorage(token))
                if (isAdded) {
                    findNavController().popBackStack()
                }
            } catch (_: Exception) {
                b.add.isEnabled = true
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
        outState.putParcelable(STATE_IMAGE_URI, mImageURL)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val SHA1_OFFSET = 1
        private const val STATE_ALGORITHM = "state_selected_algorithm"
        private const val STATE_IMAGE_URI = "state_selected_image_uri"
    }
}
