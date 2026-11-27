package org.fedorahosted.freeotp.ui.addedit

import com.google.android.apps.authenticator.Base32String
import com.google.common.truth.Truth.assertThat
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.junit.Test
import java.security.SecureRandom

class AddTokenViewModelSecretTest {

    private val base32Pattern = Regex("^[A-Z2-7]+$")

    @Test
    fun generateStrongBase32SecretMatchesAlgorithmKeyLengthsAndValidates() {
        val cases = listOf(
            "SHA1" to (20 to 32),
            "MD5" to (20 to 32),
            "SHA256" to (32 to 52),
            "SHA512" to (64 to 103),
        )

        for ((algorithm, expected) in cases) {
            val (expectedByteLen, expectedCharLen) = expected
            val generated = AddTokenViewModel.generateStrongBase32Secret(algorithm)

            assertThat(generated).hasLength(expectedCharLen)
            assertThat(generated.matches(base32Pattern)).isTrue()
            assertThat(AddTokenViewModel.normalizeTypedBase32Secret(generated)).isEqualTo(generated)

            val decoded = Base32String.decode(generated)
            assertThat(decoded).hasLength(expectedByteLen)

            val uiState = AddTokenUiState(
                issuer = "TestIssuer",
                label = "alice@example.com",
                secret = generated,
                algorithm = algorithm,
            )
            assertThat(uiState.isValid).isTrue()

            val token = OtpTokenFactory.create(
                type = OtpTokenType.TOTP,
                issuer = "TestIssuer",
                label = "alice@example.com",
                secret = generated,
                algorithm = algorithm,
            )
            assertThat(token.secret).isEqualTo(generated)
            assertThat(token.algorithm).isEqualTo(algorithm)
        }
    }

    @Test
    fun generateStrongBase32SecretRoundTripsExactRandomBytesAndProducesUniqueSecrets() {
        val deterministicBytes = ByteArray(32) { idx -> ((idx * 37) + 11).toByte() }
        val fakeRandom = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                System.arraycopy(deterministicBytes, 0, bytes, 0, bytes.size)
            }
        }

        val encoded = AddTokenViewModel.generateStrongBase32Secret("SHA256", fakeRandom)
        assertThat(Base32String.decode(encoded)).isEqualTo(deterministicBytes)

        val uniqueSecrets = (1..100)
            .map { AddTokenViewModel.generateStrongBase32Secret("SHA1") }
            .toSet()
        assertThat(uniqueSecrets).hasSize(100)
    }
}
