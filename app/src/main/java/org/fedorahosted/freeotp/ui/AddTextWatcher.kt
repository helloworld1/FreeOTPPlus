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

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText


open class AddTextWatcher(activity: Activity) : TextWatcher {
    private val mButton: Button = activity.findViewById(R.id.add)
    private val mIssuer: EditText = activity.findViewById(R.id.issuer)
    private val mLabel: EditText = activity.findViewById(R.id.label)
    private val mSecret: EditText = activity.findViewById(R.id.secret)
    private val mInterval: EditText = activity.findViewById(R.id.interval)

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {

    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        mButton.isEnabled = false

        if (mIssuer.text.isEmpty())
            return

        if (mLabel.text.isEmpty())
            return

        if (mSecret.text.length < 8)
            return

        if (mInterval.text.isEmpty())
            return

        mButton.isEnabled = true
    }

    override fun afterTextChanged(s: Editable) {

    }
}
