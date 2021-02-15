/*
 * FreeOTP
 *
 * Authors: Nathaniel McCallum <npmccallum@redhat.com>
 *
 * Copyright (C) 2013  Nathaniel McCallum, Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import androidx.appcompat.app.AppCompatDelegate

import org.fedorahosted.freeotp.R

class ProgressCircle : View {
    private var mPaint: Paint? = null
    private var mRectF: RectF? = null
    private var mRect: Rect? = null
    private var mProgress: Int = 0
    var max: Int = 0
    var hollow: Boolean = false
        set(hollow) {
            field = hollow
            mPaint!!.style = if (hollow) Style.STROKE else Style.FILL
            mPaint!!.strokeWidth = if (hollow) mStrokeWidth else 0f
        }
    private var mPadding: Float = 0.toFloat()
    private var mStrokeWidth: Float = 0.toFloat()

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
        mStrokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, dm)

        mRectF = RectF()
        mRect = Rect()

        mPaint = Paint()
        mPaint!!.setARGB(0x99, 0x33, 0x33, 0x33)
        mPaint!!.isAntiAlias = true
        mPaint!!.strokeCap = Paint.Cap.BUTT

        if (attrs != null) {
            val t = context.theme
            val a = t.obtainStyledAttributes(attrs, R.styleable.ProgressCircle, 0, 0)

            try {
                max = a.getInteger(R.styleable.ProgressCircle_max, 100)
                hollow = a.getBoolean(R.styleable.ProgressCircle_hollow, false)
            } finally {
                a.recycle()
            }
        }
    }

    fun setProgress(progress: Int) {
        mProgress = progress

        val percent = mProgress * 100 / max
        if (percent > 25 || mProgress == 0) {
            if (AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_NO && AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)
                mPaint!!.setARGB(0x99, 0x2B, 0xAD, 0x00)
            else
                mPaint!!.setARGB(0x99, 0x33, 0x33, 0x33)
        } else
            mPaint!!.setARGB(0x99, 0xff, 0xe0 * percent / 25, 0x00)

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        getDrawingRect(mRect)

        mRect!!.left += (paddingLeft + mPadding).toInt()
        mRect!!.top += (paddingTop + mPadding).toInt()
        mRect!!.right -= (paddingRight + mPadding).toInt()
        mRect!!.bottom -= (paddingBottom + mPadding).toInt()
        mRectF!!.set(mRect!!)

        canvas.drawArc(mRectF!!, -90f, (mProgress * 360 / max).toFloat(), !hollow, mPaint!!)
    }
}
