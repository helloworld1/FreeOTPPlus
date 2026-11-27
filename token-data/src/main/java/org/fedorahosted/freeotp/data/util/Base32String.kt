/*
 * Copyright 2009 Google Inc. All Rights Reserved.
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

package org.fedorahosted.freeotp.data.util

import java.util.Locale

/**
 * Encodes arbitrary byte arrays as case-insensitive base-32 strings.
 *
 * The implementation is slightly different than in RFC 4648. During encoding,
 * padding is not added, and during decoding the last incomplete chunk is not
 * taken into account. The result is that multiple strings decode to the same
 * byte array, for example, string of sixteen 7s ("7...7") and seventeen 7s both
 * decode to the same byte array.
 */
open class Base32String protected constructor(alphabet: String) {
    private val digits: CharArray = alphabet.toCharArray()
    private val mask: Int = digits.size - 1
    private val shift: Int = Integer.numberOfTrailingZeros(digits.size)
    private val charMap: HashMap<Char, Int> = HashMap<Char, Int>(digits.size).apply {
        for (i in digits.indices) {
            put(digits[i], i)
        }
    }

    @Throws(DecodingException::class)
    protected open fun decodeInternal(encoded: String?): ByteArray {
        if (encoded == null) {
            return ByteArray(0)
        }
        // Remove all whitespace (including newlines, tabs, non-breaking spaces) and separators
        var cleaned = encoded.trim()
            .replace(SEPARATOR, "")
            .replace(WHITESPACE_REGEX, "")

        // Remove trailing padding
        cleaned = cleaned.replaceFirst(PADDING_REGEX, "")

        // Canonicalize to all upper case
        cleaned = cleaned.uppercase(Locale.US)
        if (cleaned.isEmpty()) {
            return ByteArray(0)
        }

        val encodedLength = cleaned.length
        val outLength = encodedLength * shift / 8
        val result = ByteArray(outLength)
        var buffer = 0
        var next = 0
        var bitsLeft = 0
        for (c in cleaned) {
            val charValue = charMap[c] ?: throw DecodingException("Illegal character: $c")
            buffer = (buffer shl shift) or (charValue and mask)
            bitsLeft += shift
            if (bitsLeft >= 8) {
                result[next++] = (buffer shr (bitsLeft - 8)).toByte()
                bitsLeft -= 8
            }
        }
        return result
    }

    protected open fun encodeInternal(data: ByteArray): String {
        if (data.isEmpty()) {
            return ""
        }

        // shift is the number of bits per output character, so the length of the
        // output is the length of the input multiplied by 8/shift, rounded up.
        if (data.size >= (1 shl 28)) {
            throw IllegalArgumentException()
        }

        val outputLength = (data.size * 8 + shift - 1) / shift
        val result = StringBuilder(outputLength)

        var buffer = data[0].toInt()
        var next = 1
        var bitsLeft = 8
        while (bitsLeft > 0 || next < data.size) {
            if (bitsLeft < shift) {
                if (next < data.size) {
                    buffer = (buffer shl 8) or (data[next++].toInt() and 0xff)
                    bitsLeft += 8
                } else {
                    val pad = shift - bitsLeft
                    buffer = buffer shl pad
                    bitsLeft += pad
                }
            }
            val index = mask and (buffer shr (bitsLeft - shift))
            bitsLeft -= shift
            result.append(digits[index])
        }
        return result.toString()
    }

    class DecodingException(message: String?) : Exception(message)

    companion object {
        private val INSTANCE = Base32String("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567") // RFC 4648/3548
        internal const val SEPARATOR = "-"
        private val WHITESPACE_REGEX = Regex("[\\s\\u00A0]+")
        private val PADDING_REGEX = Regex("[=]*$")

        @JvmStatic
        internal fun getInstance(): Base32String = INSTANCE

        @JvmStatic
        @Throws(DecodingException::class)
        fun decode(encoded: String?): ByteArray = getInstance().decodeInternal(encoded)

        @JvmStatic
        fun encode(data: ByteArray): String = getInstance().encodeInternal(data)
    }
}
