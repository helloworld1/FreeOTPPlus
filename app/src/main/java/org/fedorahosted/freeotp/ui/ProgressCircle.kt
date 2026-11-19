package org.fedorahosted.freeotp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.Style
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import org.fedorahosted.freeotp.R

class ProgressCircle : View {
    private lateinit var mPaint: Paint
    private lateinit var mRectF: RectF
    private lateinit var mRect: Rect
    private var mProgress: Int = 0
    var max: Int = 100
    var hollow: Boolean = false
        set(hollow) {
            field = hollow
            mPaint.style = if (hollow) Style.STROKE else Style.FILL
            mPaint.strokeWidth = if (hollow) mStrokeWidth else 0f
        }
    private var mPadding: Float = 0f
    private var mStrokeWidth: Float = 0f

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) {
        setup(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        setup(context, attrs)
    }

    constructor(context: Context) : super(context) {
        setup(context, null)
    }

    private fun setup(context: Context, attrs: AttributeSet?) {
        val dm = resources.displayMetrics
        mPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, dm)
        mStrokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3.5f, dm)

        mRectF = RectF()
        mRect = Rect()

        mPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
        }

        if (attrs != null) {
            val a = context.theme.obtainStyledAttributes(attrs, R.styleable.ProgressCircle, 0, 0)
            try {
                max = a.getInteger(R.styleable.ProgressCircle_max, 100)
                hollow = a.getBoolean(R.styleable.ProgressCircle_hollow, false)
            } finally {
                a.recycle()
            }
        }
        updatePaintColor(100)
    }

    fun setProgress(progress: Int) {
        mProgress = progress
        val safeMax = if (max > 0) max else 100
        val percent = mProgress * 100 / safeMax
        updatePaintColor(percent)
        invalidate()
    }

    private fun updatePaintColor(percent: Int) {
        val baseColor = if (percent > 25 || mProgress == 0) {
            MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        } else {
            MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
        }
        val alpha = if (hollow) 0xE6 else 0xB3
        mPaint.color = ColorUtils.setAlphaComponent(baseColor, alpha)
    }

    override fun onDraw(canvas: Canvas) {
        val safeMax = if (max > 0) max else 100
        getDrawingRect(mRect)

        val inset = mPadding + if (hollow) mStrokeWidth / 2f else 0f
        mRectF.set(
            mRect.left + paddingLeft + inset,
            mRect.top + paddingTop + inset,
            mRect.right - paddingRight - inset,
            mRect.bottom - paddingBottom - inset
        )

        canvas.drawArc(mRectF, -90f, (mProgress * 360f / safeMax), !hollow, mPaint)
    }
}
