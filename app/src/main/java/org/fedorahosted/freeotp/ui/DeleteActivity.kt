package org.fedorahosted.freeotp.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

        binding = DeleteBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

                (findViewById<View>(R.id.issuer) as TextView).text = token.issuer
                (findViewById<View>(R.id.label) as TextView).text = token.label
                binding.imageView.setTokenImage(token)
            }

        }
    }

    companion object {
        const val EXTRA_TOKEN_ID = "token_id"
    }
}
