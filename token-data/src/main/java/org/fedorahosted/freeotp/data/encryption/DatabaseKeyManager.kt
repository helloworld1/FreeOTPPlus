package org.fedorahosted.freeotp.data.encryption

import android.content.Context
import android.os.Build
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.security.AlgorithmParameters
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object DatabaseKeyManager {
    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    /**
     * Extension function that converts a ByteArray to a hex encoded String
     */
    fun ByteArray.toHex(): CharArray {
        val result = StringBuilder()
        forEach {
            val octet = it.toInt()
            val firstIndex = (octet and 0xF0).ushr(4)
            val secondIndex = octet and 0x0F
            result.append(HEX_CHARS[firstIndex])
            result.append(HEX_CHARS[secondIndex])
        }
        return result.toString().toCharArray()
    }

    private var rawByteKey: ByteArray? = null
    private var dbCharKey: CharArray? = null

    /**
     * Generates a new database key.
     */
    fun createNewKey() {
        // This is the raw key that we'll be encrypting + storing
        rawByteKey = generateRandomKey()
        // This is the key that will be used by Room
        dbCharKey = rawByteKey!!.toHex()
    }


    fun persistRawKey(context: Context, userPasscode: CharArray) {
        val storable = toStorable(rawByteKey!!, userPasscode)
        // Implementation explained in next step
        saveToPrefs(context, storable)
    }

    /**
     * Returns a [Storable] instance with the db key encrypted using PBE.
     *
     * @param rawDbKey the raw database key
     * @param userPasscode the user's passcode
     * @return storable instance
     */
    fun toStorable(rawDbKey: ByteArray, userPasscode: CharArray): Storable {
        // Generate a random 8 byte salt
        val salt = ByteArray(8).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                SecureRandom.getInstanceStrong().nextBytes(this)
            } else {
                SecureRandom().nextBytes(this)
            }
        }
        val secret: SecretKey = generateSecretKey(userPasscode, salt)

        // Now encrypt the database key with PBE
        val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val params: AlgorithmParameters = cipher.parameters
        val iv: ByteArray = params.getParameterSpec(IvParameterSpec::class.java).iv
        val ciphertext: ByteArray = cipher.doFinal(rawDbKey)

        // Return the IV and CipherText which can be stored to disk
        return Storable(
            Base64.encodeToString(iv, Base64.DEFAULT),
            Base64.encodeToString(ciphertext, Base64.DEFAULT),
            Base64.encodeToString(salt, Base64.DEFAULT)
        )
    }

    private fun generateSecretKey(passcode: CharArray, salt: ByteArray): SecretKey {
        // Initialize PBE with password
        val factory: SecretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(passcode, salt, 65536, 256)
        val tmp: SecretKey = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }


    /**
     * Retrieves the [Storable] instance from prefs.
     *
     * @param context the caller's context
     * @return the storable instance
     */
    fun getStorable(context: Context): Storable? {
        val prefs = context.getSharedPreferences(
            "database",
            Context.MODE_PRIVATE
        )
        val serialized = prefs.getString("key", null)
        if (serialized.isNullOrBlank()) {
            return null
        }

        return try {
            Gson().fromJson(serialized,
                object : TypeToken<Storable>() {}.type
            )
        } catch (ex: JsonSyntaxException) {
            null
        }
    }

    /**
     * Returns the database key suitable for using with Room.
     *
     * @param passcode the user's passcode
     * @param context the caller's context
     */
    fun getCharKey(passcode: CharArray, context: Context): CharArray {
        if (dbCharKey == null) {
            initKey(passcode, context)
        }
        return dbCharKey ?: error("Failed to decrypt database key")
    }

    private fun initKey(passcode: CharArray, context: Context) {
        val storable = getStorable(context)
        if (storable == null) {
            createNewKey()
            persistRawKey(context, passcode)
        } else {
            rawByteKey = getRawByteKey(passcode, storable)
            dbCharKey = rawByteKey!!.toHex()
        }
    }

    /**
     * Decrypts the [Storable] instance using the [passcode].
     *
     * @pararm passcode the user's passcode
     * @param storable the storable instance previously saved with [saveToPrefs]
     * @return the raw byte key previously generated with [generateRandomKey]
     */
    fun getRawByteKey(passcode: CharArray, storable: Storable): ByteArray {
        val aesWrappedKey = Base64.decode(storable.key, Base64.DEFAULT)
        val iv = Base64.decode(storable.iv, Base64.DEFAULT)
        val salt = Base64.decode(storable.salt, Base64.DEFAULT)
        val secret: SecretKey = generateSecretKey(passcode, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))
        return cipher.doFinal(aesWrappedKey)
    }


    fun generateRandomKey(): ByteArray =
        ByteArray(32).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                SecureRandom.getInstanceStrong().nextBytes(this)
            } else {
                SecureRandom().nextBytes(this)
            }
        }


    /**
     * Save the storable instance to preferences.
     *
     * @param storable a storable instance
     */
    fun saveToPrefs(context: Context, storable: Storable) {
        val serialized = Gson().toJson(storable)
        val prefs = context.getSharedPreferences(
            "database",
            Context.MODE_PRIVATE
        )
        prefs.edit().putString("key", serialized).apply()
    }


    /**
     * Container for everything needed for decrypting the database.
     *
     * @param iv initialization vector
     * @param key encrypted database key
     * @param salt cryptographic salt
     */
    data class Storable(val iv: String, val key: String, val salt: String)

}