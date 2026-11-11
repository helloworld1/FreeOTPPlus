package org.fedorahosted.freeotp.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.databinding.DeleteBinding
import org.fedorahosted.freeotp.util.setTokenImage
import javax.inject.Inject

@AndroidEntryPoint
class DeleteActivity : AppCompatActivity() {
    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    private lateinit var binding: DeleteBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = DeleteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val buttonLayout = binding.root.findViewById<View>(R.id.button_layout)
        ViewCompat.setOnApplyWindowInsetsListener(buttonLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                insets.bottom
            )
            windowInsets
        }

        lifecycleScope.launch {
            val tokenId = intent.getLongExtra(EXTRA_TOKEN_ID, 0L)

            if (tokenId == 0L) {
                return@launch
            }

            binding.cancel.setOnClickListener {
                finish()
            }

            binding.delete.setOnClickListener {
                lifecycleScope.launch {
                    otpTokenDatabase.otpTokenDao().deleteById(tokenId)
                    setResult(RESULT_OK)
                }
            }

            otpTokenDatabase.otpTokenDao().get(tokenId).collect { token ->
                if (token == null) {
                    finish()
                    return@collect
                }

                val issuerView = findViewById<TextView>(R.id.issuer)
                val labelView = findViewById<TextView>(R.id.label)
                val categoryView = findViewById<TextView>(R.id.category)

                if (token.issuer.isNullOrBlank()) {
                    issuerView.text = token.label
                    labelView.visibility = View.GONE
                } else {
                    issuerView.text = token.issuer
                    labelView.text = token.label
                    labelView.visibility = View.VISIBLE
                }

                if (token.category.isNullOrBlank()) {
                    categoryView.visibility = View.GONE
                } else {
                    categoryView.visibility = View.VISIBLE
                    categoryView.text = token.category
                }

                binding.imageView.setTokenImage(token)
            }

        }
    }

    companion object {
        const val EXTRA_TOKEN_ID = "token_id"
    }
}
