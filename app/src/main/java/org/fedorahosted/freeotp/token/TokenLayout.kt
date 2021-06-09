package org.fedorahosted.freeotp.token

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView

import com.google.android.material.card.MaterialCardView

import org.fedorahosted.freeotp.ui.ProgressCircle
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.legacy.TokenCode
import org.fedorahosted.freeotp.util.setTokenImage

class TokenLayout : MaterialCardView, View.OnClickListener, Runnable {
    private val tag = TokenLayout::class.java.simpleName
    private lateinit var mProgressInner: ProgressCircle
    private lateinit var mProgressOuter: ProgressCircle
    private lateinit var mImage: ImageView
    private lateinit var mCode: TextView
    private lateinit var mIssuer: TextView
    private lateinit var mLabel: TextView
    private lateinit var mMenu: ImageView
    private lateinit var mPopupMenu: PopupMenu

    private var mCodes: TokenCode? = null
    private var mType: OtpTokenType? = null
    private var mPlaceholder: String? = null
    private var mStartTime: Long = 0

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    override fun onFinishInflate() {
        super.onFinishInflate()

        mProgressInner = findViewById<View>(R.id.progressInner) as ProgressCircle
        mProgressOuter = findViewById<View>(R.id.progressOuter) as ProgressCircle
        mImage = findViewById<View>(R.id.image) as ImageView
        mCode = findViewById<View>(R.id.code) as TextView
        mIssuer = findViewById<View>(R.id.issuer) as TextView
        mLabel = findViewById<View>(R.id.label) as TextView
        mMenu = findViewById<View>(R.id.menu) as ImageView

        mPopupMenu = PopupMenu(context, mMenu)
        mMenu.setOnClickListener(this)
    }

    fun bind(token: OtpToken, menu: Int, micl: PopupMenu.OnMenuItemClickListener) {
        mCodes = null

        // Setup menu.
        mPopupMenu.menu.clear()
        mPopupMenu.menuInflater.inflate(menu, mPopupMenu.menu)
        mPopupMenu.setOnMenuItemClickListener(micl)

        // Cancel all active animations.
        isEnabled = true
        removeCallbacks(this)
        mImage.clearAnimation()
        mProgressInner.clearAnimation()
        mProgressOuter.clearAnimation()
        mProgressInner.visibility = View.GONE
        mProgressOuter.visibility = View.GONE

        // Get the code placeholder.
        val placeholder = CharArray(token.digits)
        for (i in placeholder.indices)
            placeholder[i] = '-'
        mPlaceholder = String(placeholder)

        // Show the image.
        mImage.setTokenImage(token)

        // Set the labels.
        mLabel.text = token.label
        mIssuer.text = token.issuer
        mCode.text = mPlaceholder
        if (mIssuer.text.isEmpty()) {
            mIssuer.text = token.label
            mLabel.visibility = View.GONE
        } else {
            mLabel.visibility = View.VISIBLE
        }
    }

    private fun animate(view: View, anim: Int, animate: Boolean) {
        val a = AnimationUtils.loadAnimation(view.context, anim)
        if (!animate)
            a.duration = 0
        view.startAnimation(a)
    }

    fun start(type: OtpTokenType, codes: TokenCode, animate: Boolean) {
        mCodes = codes
        mType = type

        // Start animations.
        mProgressInner.visibility = View.VISIBLE
        animate(mProgressInner, R.anim.fadein, animate)
        animate(mImage, R.anim.token_image_fadeout, animate)

        // Handle type-specific UI.
        when (type) {
            OtpTokenType.HOTP -> isEnabled = false
            OtpTokenType.TOTP -> {
                mProgressOuter.visibility = View.VISIBLE
                animate(mProgressOuter, R.anim.fadein, animate)
            }
        }

        mStartTime = System.currentTimeMillis()
        post(this)
    }

    override fun onClick(v: View) {
        mPopupMenu.show()
    }

    override fun run() {
        // Get the current data
        val code = mCodes?.currentCode?: run {
            mCode.text = mPlaceholder
            mProgressInner.visibility = View.GONE
            mProgressOuter.visibility = View.GONE
            animate(mImage, R.anim.token_image_fadein, true)
            return
        }

        val currentProgress = mCodes?.currentProgress ?: run {
            Log.w(tag, "Token current progress is null")
            return
        }

        val totalProgress = mCodes?.totalProgress ?: run {
            Log.w(tag, "Token total progress is null")
            return
        }

        // Determine whether to enable/disable the view.
        if (!isEnabled)
            isEnabled = System.currentTimeMillis() - mStartTime > 5000

        // Update the fields
        mCode.text = code
        mProgressInner.setProgress(currentProgress)
        if (mType != OtpTokenType.HOTP)
            mProgressOuter.setProgress(totalProgress)

        postDelayed(this, 100)
    }
}
