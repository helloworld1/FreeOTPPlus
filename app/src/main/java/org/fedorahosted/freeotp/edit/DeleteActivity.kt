package org.fedorahosted.freeotp.edit

import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.TokenPersistence

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity

import com.squareup.picasso.Picasso
import dagger.android.AndroidInjection
import javax.inject.Inject

class DeleteActivity : AppCompatActivity() {
    @Inject lateinit var tokenPersistence: TokenPersistence

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidInjection.inject(this)

        setContentView(R.layout.delete)

        val position = intent.getIntExtra(EXTRA_POSITION, -1)

        val token = tokenPersistence[position]
        (findViewById<View>(R.id.issuer) as TextView).text = token!!.issuer
        (findViewById<View>(R.id.label) as TextView).text = token.label
        Picasso.get()
                .load(token.image)
                .placeholder(R.drawable.logo)
                .into(findViewById<View>(R.id.image) as ImageView)

        findViewById<View>(R.id.cancel).setOnClickListener { finish() }

        findViewById<View>(R.id.delete).setOnClickListener {
            tokenPersistence.delete(position)
            finish()
        }
    }

    companion object {
        const val EXTRA_POSITION = "position"
    }
}
