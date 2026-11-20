package org.fedorahosted.freeotp.token

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.legacy.TokenCode
import org.fedorahosted.freeotp.ui.ProgressCircle
import org.fedorahosted.freeotp.util.setTokenImage
import java.util.Locale

class TokenLayout : MaterialCardView, View.OnClickListener, Runnable {
    private val tag = TokenLayout::class.java.simpleName
    private lateinit var mProgressInner: ProgressCircle
    private lateinit var mProgressOuter: ProgressCircle
    private lateinit var mImage: ImageView
    private lateinit var mCode: TextView
    private lateinit var mIssuer: TextView
    private lateinit var mLabel: TextView
    private lateinit var mCategory: TextView
    private lateinit var mCounterContainer: View
    private lateinit var mCounterValue: TextView
    private lateinit var mMenu: ImageView
    private lateinit var mPopupMenu: PopupMenu

    private var mCodes: TokenCode? = null
    private var mType: OtpTokenType? = null
    private var mPlaceholder: String = "--- ---"
    private var mStartTime: Long = 0

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    override fun onFinishInflate() {
        super.onFinishInflate()

        mProgressInner = findViewById(R.id.progressInner)
        mProgressOuter = findViewById(R.id.progressOuter)
        mImage = findViewById(R.id.image)
        mCode = findViewById(R.id.code)
        mIssuer = findViewById(R.id.issuer)
        mLabel = findViewById(R.id.label)
        mCategory = findViewById(R.id.category)
        mCounterContainer = findViewById(R.id.counter_container)
        mCounterValue = findViewById(R.id.counter_value)
        mMenu = findViewById(R.id.menu)

        mPopupMenu = PopupMenu(context, mMenu)
        mMenu.setOnClickListener(this)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(this)
        super.onDetachedFromWindow()
    }

    fun bind(token: OtpToken, menu: Int, micl: PopupMenu.OnMenuItemClickListener) {
        mCodes = null

        mPopupMenu.menu.clear()
        mPopupMenu.menuInflater.inflate(menu, mPopupMenu.menu)
        mPopupMenu.setOnMenuItemClickListener(micl)

        isEnabled = true
        removeCallbacks(this)
        mImage.clearAnimation()
        mProgressInner.clearAnimation()
        mProgressOuter.clearAnimation()
        mProgressInner.visibility = View.GONE
        mProgressOuter.visibility = View.GONE

        mPlaceholder = buildPlaceholder(token.digits)

        mImage.setTokenImage(token)
        mImage.alpha = 1.0f

        mCode.text = mPlaceholder
        mCode.setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))

        if (token.issuer.isNullOrBlank()) {
            mIssuer.text = token.label
            mLabel.visibility = View.GONE
        } else {
            mIssuer.text = token.issuer
            mLabel.text = token.label
            mLabel.visibility = View.VISIBLE
        }

        mCategory.visibility = View.GONE
        updateCounter(token)
    }

    fun updateCounter(token: OtpToken) {
        if (token.tokenType == OtpTokenType.HOTP) {
            val displayCounter = formatHotpCounter(token.counter)
            mCounterValue.text = displayCounter
            mCounterContainer.contentDescription = "${context.getString(R.string.counter)}: $displayCounter"
            mCounterContainer.visibility = View.VISIBLE
        } else {
            mCounterContainer.visibility = View.GONE
        }
    }

    private fun animate(view: View, anim: Int, animate: Boolean) {
        val a = AnimationUtils.loadAnimation(view.context, anim)
        if (!animate) {
            a.duration = 0
        }
        view.startAnimation(a)
    }

    fun start(type: OtpTokenType, codes: TokenCode, animate: Boolean) {
        mCodes = codes
        mType = type

        mProgressInner.visibility = View.VISIBLE
        animate(mProgressInner, R.anim.fadein, animate)
        animate(mImage, R.anim.token_image_fadeout, animate)

        when (type) {
            OtpTokenType.HOTP -> isEnabled = false
            OtpTokenType.TOTP -> {
                mProgressOuter.visibility = View.VISIBLE
                animate(mProgressOuter, R.anim.fadein, animate)
            }
        }

        mStartTime = System.currentTimeMillis()
        removeCallbacks(this)
        post(this)
    }

    override fun onClick(v: View) {
        mPopupMenu.show()
    }

    override fun run() {
        val rawCode = mCodes?.currentCode ?: run {
            mCode.text = mPlaceholder
            mCode.setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
            mProgressInner.visibility = View.GONE
            mProgressOuter.visibility = View.GONE
            animate(mImage, R.anim.token_image_fadein, true)
            isEnabled = true
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

        if (!isEnabled) {
            isEnabled = System.currentTimeMillis() - mStartTime > 5000
        }

        mCode.text = formatCodeForDisplay(rawCode)
        mCode.setTextColor(ContextCompat.getColor(context, R.color.primary))
        mProgressInner.setProgress(currentProgress)
        if (mType != OtpTokenType.HOTP) {
            mProgressOuter.setProgress(totalProgress)
        }

        postDelayed(this, 100)
    }

    companion object {
        internal fun formatHotpCounter(counter: Long): String {
            return String.format(Locale.US, "%d", counter.coerceAtLeast(0L))
        }

        internal fun formatCodeForDisplay(raw: String): String {
            return when (raw.length) {
                6 -> "${raw.substring(0, 3)} ${raw.substring(3)}"
                7 -> "${raw.substring(0, 3)} ${raw.substring(3)}"
                8 -> "${raw.substring(0, 4)} ${raw.substring(4)}"
                else -> raw
            }
        }

        internal fun buildPlaceholder(digits: Int): String {
            val safeDigits = digits.coerceIn(4, 10)
            return formatCodeForDisplay("-".repeat(safeDigits))
        }
    }
}
