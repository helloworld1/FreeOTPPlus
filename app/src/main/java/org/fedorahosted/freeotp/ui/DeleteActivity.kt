package org.fedorahosted.freeotp.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.squareup.picasso.Picasso
import dagger.android.AndroidInjection
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.token.TokenPersistence
import org.fedorahosted.freeotp.util.uiLifecycleScope
import javax.inject.Inject

class DeleteActivity : AppCompatActivity() {
    @Inject lateinit var tokenPersistence: TokenPersistence

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidInjection.inject(this@DeleteActivity)

        setContentView(R.layout.delete)

        uiLifecycleScope {
            val tokenId = intent.getStringExtra(EXTRA_TOKEN_ID) ?: return@uiLifecycleScope

            val token = tokenPersistence.getToken(tokenId) ?: return@uiLifecycleScope
            (findViewById<View>(R.id.issuer) as TextView).text = token.issuer
            (findViewById<View>(R.id.label) as TextView).text = token.label
            Picasso.get()
                    .load(token.image)
                    .placeholder(R.drawable.logo)
                    .into(findViewById<View>(R.id.image) as ImageView)

            findViewById<View>(R.id.cancel).setOnClickListener { finish() }

            findViewById<View>(R.id.delete).setOnClickListener {
                uiLifecycleScope {
                    tokenPersistence.delete(tokenId)
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_TOKEN_ID = "token_id"
    }
}
