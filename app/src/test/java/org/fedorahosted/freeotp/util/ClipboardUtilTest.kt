package org.fedorahosted.freeotp.util

import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClipboardUtilTest {
    @Test
    fun shouldShowAppCopyFeedback_onlyReturnsTrueBelowAndroid13() {
        assertThat(ClipboardUtil.shouldShowAppCopyFeedback(Build.VERSION_CODES.M)).isTrue()
        assertThat(ClipboardUtil.shouldShowAppCopyFeedback(Build.VERSION_CODES.Q)).isTrue()
        assertThat(ClipboardUtil.shouldShowAppCopyFeedback(Build.VERSION_CODES.S_V2)).isTrue()

        assertThat(ClipboardUtil.shouldShowAppCopyFeedback(Build.VERSION_CODES.TIRAMISU)).isFalse()
        assertThat(ClipboardUtil.shouldShowAppCopyFeedback(34)).isFalse()
        assertThat(ClipboardUtil.shouldShowAppCopyFeedback(35)).isFalse()
        assertThat(ClipboardUtil.shouldShowAppCopyFeedback(37)).isFalse()
    }
}
