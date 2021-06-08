/*
 * FreeOTP
 *
 * Authors: Nathaniel McCallum <npmccallum@redhat.com>
 *
 * Copyright (C) 2014  Nathaniel McCallum, Red Hat
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

package org.fedorahosted.freeotp.data.legacy

class TokenCode(private val mCode: String, private val mStart: Long, private val mUntil: Long) {
    private var mNext: TokenCode? = null

    val currentCode: String?
        get() {
            val active = getActive(System.currentTimeMillis()) ?: return null
            return active.mCode
        }

    val totalProgress: Int
        get() {
            val cur = System.currentTimeMillis()
            val total = last.mUntil - mStart
            val state = total - (cur - mStart)
            return (state * 1000 / total).toInt()
        }

    val currentProgress: Int
        get() {
            val cur = System.currentTimeMillis()
            val active = getActive(cur) ?: return 0

            val total = active.mUntil - active.mStart
            val state = total - (cur - active.mStart)
            return (state * 1000 / total).toInt()
        }

    private val last: TokenCode
        get() = if (mNext == null) this else this.mNext!!.last

    constructor(prev: TokenCode, code: String, start: Long, until: Long) : this(code, start, until) {
        prev.mNext = this
    }

    constructor(code: String, start: Long, until: Long, next: TokenCode) : this(code, start, until) {
        mNext = next
    }

    private fun getActive(curTime: Long): TokenCode? {
        if (curTime in mStart until mUntil)
            return this

        return if (mNext == null) null else this.mNext!!.getActive(curTime)

    }
}
