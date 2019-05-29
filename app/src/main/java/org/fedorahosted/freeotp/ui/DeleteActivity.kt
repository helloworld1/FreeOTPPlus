package org.fedorahosted.freeotp.ui

import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.token.TokenPersistence

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity

import com.squareup.picasso.Picasso
import dagger.android.AndroidInjection
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.util.UiLifecycleScope
import javax.inject.Inject

class DeleteActivity : AppCompatActivity() {
    @Inject lateinit var tokenPersistence: TokenPersistence
    @Inject lateinit var uiLifecycleScope: UiLifecycleScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidInjection.inject(this)
        lifecycle.addObserver(uiLifecycleScope)

        setContentView(R.layout.delete)

        val tokenId = intent.getStringExtra(EXTRA_TOKEN_ID) ?: return

        val token = tokenPersistence[tokenId] ?: return
        (findViewById<View>(R.id.issuer) as TextView).text = token.issuer
        (findViewById<View>(R.id.label) as TextView).text = token.label
        Picasso.get()
                .load(token.image)
                .placeholder(R.drawable.logo)
                .into(findViewById<View>(R.id.image) as ImageView)

        findViewById<View>(R.id.cancel).setOnClickListener { finish() }

        findViewById<View>(R.id.delete).setOnClickListener {
            uiLifecycleScope.launch {
                tokenPersistence.delete(tokenId)
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_TOKEN_ID = "token_id"
    }
}
