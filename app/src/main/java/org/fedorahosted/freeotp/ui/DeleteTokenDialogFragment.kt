package org.fedorahosted.freeotp.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.databinding.DeleteBinding
import org.fedorahosted.freeotp.util.setTokenImage
import javax.inject.Inject

@AndroidEntryPoint
class DeleteTokenDialogFragment : DialogFragment() {

    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DeleteBinding.inflate(layoutInflater)
        val tokenId = arguments?.getLong(ARG_TOKEN_ID, 0L) ?: 0L

        binding.cancel.setOnClickListener {
            dismiss()
        }

        binding.delete.setOnClickListener {
            if (tokenId == 0L) {
                dismiss()
                return@setOnClickListener
            }
            binding.delete.isEnabled = false
            lifecycleScope.launch {
                viewModel.removeActiveTokenCode(tokenId)
                otpTokenDatabase.otpTokenDao().deleteById(tokenId)
                dismissAllowingStateLoss()
            }
        }

        val issuerView = binding.root.findViewById<TextView>(R.id.issuer)
        val labelView = binding.root.findViewById<TextView>(R.id.label)
        val categoryView = binding.root.findViewById<TextView>(R.id.category)

        lifecycleScope.launch {
            if (tokenId == 0L) {
                dismissAllowingStateLoss()
                return@launch
            }
            otpTokenDatabase.otpTokenDao().get(tokenId).collect { token ->
                if (token == null) {
                    dismissAllowingStateLoss()
                    return@collect
                }

                if (token.issuer.isNullOrBlank()) {
                    issuerView.text = token.label
                    labelView.visibility = View.GONE
                } else {
                    issuerView.text = token.issuer
                    labelView.text = token.label
                    labelView.visibility = View.VISIBLE
                }

                val formattedCategories = CategoryUtil.formatForDisplay(token.categories)
                if (formattedCategories.isBlank()) {
                    categoryView.visibility = View.GONE
                } else {
                    categoryView.visibility = View.VISIBLE
                    categoryView.text = formattedCategories
                }

                binding.imageView.setTokenImage(token)
            }
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_question)
            .setView(binding.root)
            .create()
    }

    companion object {
        const val ARG_TOKEN_ID = "token_id"
    }
}
