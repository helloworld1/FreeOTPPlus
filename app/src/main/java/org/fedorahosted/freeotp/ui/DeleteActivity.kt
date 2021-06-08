package org.fedorahosted.freeotp.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.databinding.DeleteBinding
import org.fedorahosted.freeotp.data.legacy.TokenPersistence
import org.fedorahosted.freeotp.util.setTokenImage
import javax.inject.Inject

@AndroidEntryPoint
class DeleteActivity : AppCompatActivity() {
    @Inject lateinit var tokenPersistence: TokenPersistence
    private lateinit var binding: DeleteBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DeleteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            val tokenId = intent.getStringExtra(EXTRA_TOKEN_ID) ?: return@launch

            val token = tokenPersistence.getToken(tokenId) ?: return@launch
            (findViewById<View>(R.id.issuer) as TextView).text = token.issuer
            (findViewById<View>(R.id.label) as TextView).text = token.label

            binding.imageView.setTokenImage(token)


            findViewById<View>(R.id.cancel).setOnClickListener {
                finish()
            }

            findViewById<View>(R.id.delete).setOnClickListener {
                lifecycleScope.launch {
                    tokenPersistence.delete(tokenId)
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_TOKEN_ID = "token_id"
    }
}
