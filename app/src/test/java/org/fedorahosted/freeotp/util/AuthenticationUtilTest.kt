package org.fedorahosted.freeotp.util

import android.os.Build
import androidx.biometric.BiometricManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AuthenticationUtilTest {

    class FakeFingerprintService(
        private val hardwareDetected: Boolean,
        private val enrolledFingerprints: Boolean
    ) {
        fun isHardwareDetected(): Boolean = hardwareDetected
        fun hasEnrolledFingerprints(): Boolean = enrolledFingerprints
    }

    @Test
    fun resolveStrongBiometricAvailability_onAndroid10_fallsBackToPlatformFingerprint() {
        val available = AuthenticationUtil.resolveStrongBiometricAvailability(
            sdkInt = Build.VERSION_CODES.Q,
            biometricManagerStatus = BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            hasScreenLock = { true },
            fingerprintServiceProvider = {
                FakeFingerprintService(hardwareDetected = true, enrolledFingerprints = true)
            }
        )
        assertThat(available).isTrue()
    }

    @Test
    fun resolveStrongBiometricAvailability_onAndroid10_returnsFalseWhenNoFingerprintEnrolled() {
        val available = AuthenticationUtil.resolveStrongBiometricAvailability(
            sdkInt = Build.VERSION_CODES.Q,
            biometricManagerStatus = BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            hasScreenLock = { true },
            fingerprintServiceProvider = {
                FakeFingerprintService(hardwareDetected = true, enrolledFingerprints = false)
            }
        )
        assertThat(available).isFalse()
    }

    @Test
    fun resolveStrongBiometricAvailability_onAndroid10_returnsFalseWithoutScreenLock() {
        val available = AuthenticationUtil.resolveStrongBiometricAvailability(
            sdkInt = Build.VERSION_CODES.Q,
            biometricManagerStatus = BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            hasScreenLock = { false },
            fingerprintServiceProvider = {
                FakeFingerprintService(hardwareDetected = true, enrolledFingerprints = true)
            }
        )
        assertThat(available).isFalse()
    }

    @Test
    fun resolveStrongBiometricAvailability_onAndroid11Plus_trustsBiometricManagerDirectly() {
        val available = AuthenticationUtil.resolveStrongBiometricAvailability(
            sdkInt = Build.VERSION_CODES.R,
            biometricManagerStatus = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            hasScreenLock = { true },
            fingerprintServiceProvider = {
                FakeFingerprintService(hardwareDetected = true, enrolledFingerprints = true)
            }
        )
        assertThat(available).isFalse()

        val strongSuccess = AuthenticationUtil.resolveStrongBiometricAvailability(
            sdkInt = Build.VERSION_CODES.R,
            biometricManagerStatus = BiometricManager.BIOMETRIC_SUCCESS,
            hasScreenLock = { true },
            fingerprintServiceProvider = { null }
        )
        assertThat(strongSuccess).isTrue()
    }
}
