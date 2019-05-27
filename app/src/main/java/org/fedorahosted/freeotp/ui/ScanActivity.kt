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

import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.token.TokenPersistence

import android.annotation.TargetApi
import android.app.Activity
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.hardware.Camera.Parameters
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import android.widget.Toast

import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import dagger.android.AndroidInjection
import javax.inject.Inject

class ScanActivity : Activity(), SurfaceHolder.Callback {
    @Inject lateinit var tokenPersistence: TokenPersistence

    private val mCameraInfo = CameraInfo()
    private val mScanAsyncTask: ScanAsyncTask
    private val mCameraId: Int
    private var mHandler: Handler? = null
    private var mCamera: Camera? = null

    private class AutoFocusHandler(private val mCamera: Camera) : Handler(), Camera.AutoFocusCallback {

        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            mCamera.autoFocus(this)
        }

        override fun onAutoFocus(success: Boolean, camera: Camera) {
            sendEmptyMessageDelayed(0, 1000)
        }
    }

    init {

        mCameraId = findCamera(mCameraInfo)

        // Create the decoder thread
        mScanAsyncTask = object : ScanAsyncTask() {
            override fun onPostExecute(result: String) {
                super.onPostExecute(result)
                scanResult(result)
            }
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidInjection.inject(this)
        setContentView(R.layout.scan)
        mScanAsyncTask.execute()
    }

    public override fun onDestroy() {
        super.onDestroy()
        mScanAsyncTask.cancel(true)
    }

    override fun onStart() {
        super.onStart()
        (findViewById<View>(R.id.surfaceview) as SurfaceView).holder.addCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (mCamera == null)
            return

        // The code in this section comes from the developer docs. See:
        // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)

        var rotation = 0
        when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> rotation = 0
            Surface.ROTATION_90 -> rotation = 90
            Surface.ROTATION_180 -> rotation = 180
            Surface.ROTATION_270 -> rotation = 270
        }

        var result = 0
        when (mCameraInfo.facing) {
            Camera.CameraInfo.CAMERA_FACING_FRONT -> {
                result = (mCameraInfo.orientation + rotation) % 360
                result = (360 - result) % 360 // compensate the mirror
            }

            Camera.CameraInfo.CAMERA_FACING_BACK -> result = (mCameraInfo.orientation - rotation + 360) % 360
        }

        mCamera!!.setDisplayOrientation(result)
        mCamera!!.startPreview()

        if (mHandler != null)
            mHandler!!.sendEmptyMessageDelayed(0, 100)
    }

    @TargetApi(14)
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceDestroyed(holder)

        try {
            // Open the camera
            mCamera = Camera.open(mCameraId)
            mCamera!!.setPreviewDisplay(holder)
            mCamera!!.setPreviewCallback(mScanAsyncTask)
        } catch (e: Exception) {
            e.printStackTrace()
            surfaceDestroyed(holder)

            // Show error message
            findViewById<View>(R.id.surfaceview).visibility = View.INVISIBLE
            findViewById<View>(R.id.progress).visibility = View.INVISIBLE
            findViewById<View>(R.id.window).visibility = View.INVISIBLE
            findViewById<View>(R.id.textview).visibility = View.VISIBLE
            return
        }

        // Set auto-focus mode
        val params = mCamera!!.parameters
        val modes = params.supportedFocusModes
        if (modes.contains(Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
            params.focusMode = Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
        else if (modes.contains(Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
            params.focusMode = Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        else if (modes.contains(Parameters.FOCUS_MODE_AUTO)) {
            params.focusMode = Parameters.FOCUS_MODE_AUTO
            mHandler = AutoFocusHandler(mCamera!!)
        }
        mCamera!!.parameters = params
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (mCamera == null)
            return

        if (mHandler != null) {
            mCamera!!.cancelAutoFocus()
            mHandler!!.removeMessages(0)
            mHandler = null
        }

        mCamera!!.stopPreview()
        mCamera!!.setPreviewCallback(null)
        mCamera!!.release()
        mCamera = null
    }

    private fun scanResult(result: String) {
        val token = tokenPersistence.addFromUriString(result)
        if (token != null) {
            Toast.makeText(this, R.string.add_token_success, Toast.LENGTH_SHORT).show()
        }
        setResult(RESULT_OK)

        if (token?.image == null) {
            finish()
            return
        }

        val image = findViewById<View>(R.id.image) as ImageView
        Picasso.get()
                .load(token.image)
                .placeholder(R.drawable.scan)
                .into(image, object : Callback {
                    override fun onSuccess() {
                        findViewById<View>(R.id.progress).visibility = View.INVISIBLE
                        image.alpha = 0.9f
                        image.postDelayed({
                            finish()
                        }, 2000)
                    }

                    override fun onError(e: java.lang.Exception) {
                        e.printStackTrace()
                        finish()
                    }
                })

    }


    companion object {

        fun haveCamera(): Boolean {
            return findCamera(CameraInfo()) >= 0
        }

        private fun findCamera(cameraInfo: CameraInfo): Int {
            var cameraId = Camera.getNumberOfCameras()

            // Find a back-facing camera. Otherwise, use the first camera.
            while (cameraId-- > 0) {
                Camera.getCameraInfo(cameraId, cameraInfo)
                if (cameraId == 0 || cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK)
                    break
            }

            return cameraId
        }
    }
}
