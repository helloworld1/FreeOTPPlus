package org.fedorahosted.freeotp.common.encryption

import android.content.Context
import android.util.Base64
import java.math.BigInteger
import java.nio.charset.Charset
import java.security.AlgorithmParameters
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor


class EncryptDecrypt(val context: Context) {

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    private var salt: ByteArray = "12345678".toByteArray()
    var iterationCount = 1024
    var keyStrength = 256
    lateinit var iv: ByteArray


    // Source: https://gist.github.com/scotttam/874426/e5a0e1317995e9388083eb455c5bb160ec2e1afd

    private fun strToPBKDF2Hash(str: String): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val spec: KeySpec = PBEKeySpec(str.toCharArray(), salt, iterationCount, keyStrength)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    fun encrypt(
        strToEncrypt: String,
        encryptionType: EncryptionType,
        secretKey: String? = null
    ): String {
        return encrypt(
            strToEncrypt,
            encryptionType,
            if (secretKey != null) strToPBKDF2Hash(secretKey) else secretKey
        )
    }

    private fun encrypt(
        strToEncrypt: String,
        encryptionType: EncryptionType,
        secretKey: SecretKey? = null
    ): String {
        return when (encryptionType) {
            EncryptionType.AES -> {
                if (secretKey == null)
                    throw java.lang.IllegalArgumentException("Need to provide 'secretKey' for AES!")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val params: AlgorithmParameters = cipher.parameters
                iv = params.getParameterSpec(IvParameterSpec::class.java).iv
                val utf8EncryptedData: ByteArray = cipher.doFinal(strToEncrypt.toByteArray())
                return Base64.encodeToString(utf8EncryptedData, Base64.DEFAULT)
            }
            EncryptionType.PLAIN_TEXT -> strToEncrypt
        }
    }


    fun decrypt(
        dataToDecrypt: String,
        encryptionType: EncryptionType,
        secretKey: String? = null
    ): String {
        return when (encryptionType) {
            EncryptionType.AES -> {
                if (secretKey == null)
                    throw java.lang.IllegalArgumentException("Need to provide 'secretKey' for AES!")
                cipher.init(Cipher.DECRYPT_MODE, strToPBKDF2Hash(secretKey), IvParameterSpec(iv))
                val decryptedData: ByteArray = Base64.decode(dataToDecrypt, Base64.DEFAULT)
                val utf8: ByteArray = cipher.doFinal(decryptedData)
                return String(utf8, Charset.forName("UTF8"))
            }
            EncryptionType.PLAIN_TEXT -> dataToDecrypt
        }

    }


    //Source: https://howtodoinjava.com/java/java-security/how-to-generate-secure-password-hash-md5-sha-pbkdf2-bcrypt-examples/
    fun generatePasswordHash(password: String): String {
        val iterations = 1000
        val chars = password.toCharArray()
        val salt = getSalt()
        val spec = PBEKeySpec(chars, salt, iterations, 64 * 8)
        val skf: SecretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val hash: ByteArray = skf.generateSecret(spec).encoded
        return iterations.toString() + ":" + toHex(salt) + ":" + toHex(hash)
    }

    private fun getSalt(): ByteArray {
        val sr: SecureRandom = SecureRandom.getInstance("SHA1PRNG")
        val salt = ByteArray(16)
        sr.nextBytes(salt)
        return salt
    }

    private fun toHex(array: ByteArray): String {
        val bi = BigInteger(1, array)
        val hex: String = bi.toString(16)
        val paddingLength = array.size * 2 - hex.length
        return if (paddingLength > 0) {
            String.format("%0" + paddingLength + "d", 0) + hex
        } else {
            hex
        }
    }

    fun validatePassword(enteredPassword: String, actualPasswordHash: String): Boolean {
        val parts = actualPasswordHash.split(":".toRegex()).toTypedArray()
        val iterations = parts[0].toInt()
        val salt = fromHex(parts[1])
        val hash = fromHex(parts[2])
        val spec = PBEKeySpec(
            enteredPassword.toCharArray(),
            salt, iterations, hash.size * 8
        )
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val testHash = skf.generateSecret(spec).encoded
        var diff = hash.size xor testHash.size
        var i = 0
        while (i < hash.size && i < testHash.size) {
            diff = diff or ((hash[i] xor testHash[i]).toInt())
            i++
        }
        return diff == 0
    }

    private fun fromHex(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = hex.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        }
        return bytes
    }


}