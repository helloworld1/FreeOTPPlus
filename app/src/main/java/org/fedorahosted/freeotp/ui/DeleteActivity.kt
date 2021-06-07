package org.fedorahosted.freeotp.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.delete.*
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.token.TokenPersistence
import org.fedorahosted.freeotp.util.setTokenImage
import org.fedorahosted.freeotp.util.uiLifecycleScope
import javax.inject.Inject

@AndroidEntryPoint
class DeleteActivity : AppCompatActivity() {
    @Inject lateinit var tokenPersistence: TokenPersistence

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.delete)

        uiLifecycleScope {
            val tokenId = intent.getStringExtra(EXTRA_TOKEN_ID) ?: return@uiLifecycleScope

            val token = tokenPersistence.getToken(tokenId) ?: return@uiLifecycleScope
            (findViewById<View>(R.id.issuer) as TextView).text = token.issuer
            (findViewById<View>(R.id.label) as TextView).text = token.label

            image_view.setTokenImage(token)


            findViewById<View>(R.id.cancel).setOnClickListener {
                finish()
            }

            findViewById<View>(R.id.delete).setOnClickListener {
                uiLifecycleScope {
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
