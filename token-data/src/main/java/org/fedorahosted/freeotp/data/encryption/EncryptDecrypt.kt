package org.fedorahosted.freeotp.data.encryption

import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import com.google.android.apps.authenticator.Base32String
import org.fedorahosted.freeotp.data.EncryptionType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey


// TODO as SINGLETON with DI
// TODO Option to use PLAIN_TEXT or ENCRYPTED when adding Token

class EncryptDecrypt(val context: Context) {

    fun getSavedSecretKey(): SecretKey? {
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(context)
        val strSecretKey = sharedPref.getString("secret_key", null)
        if (strSecretKey == null) {
            return null
        }
        val bytes = android.util.Base64.decode(strSecretKey, android.util.Base64.DEFAULT)
        val ois = ObjectInputStream(ByteArrayInputStream(bytes))
        val secretKey = ois.readObject() as SecretKey
        return secretKey
    }

    fun saveSecretKey(secretKey: SecretKey) {
        val baos = ByteArrayOutputStream()
        val oos = ObjectOutputStream(baos)
        oos.writeObject(secretKey)
        val strToSave =
            String(android.util.Base64.encode(baos.toByteArray(), android.util.Base64.DEFAULT))
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = sharedPref.edit()
        editor.putString("secret_key", strToSave)
        editor.apply()
    }

    fun encrypt(strToEncrypt: String, encryptionType: EncryptionType): String {
        return when (encryptionType) {
            EncryptionType.AES -> {
                var key = getSavedSecretKey()
                if (key == null) {
                    val keygen = KeyGenerator.getInstance("AES")
                    keygen.init(256)
                    key = keygen.generateKey()
                    saveSecretKey(key)
                }
                val plainText = Base32String.decode(strToEncrypt)
                val cipher = Cipher.getInstance("AES")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val cipherText = cipher.doFinal(plainText)
                /*if(getSavedInitializationVector()== null) {
                    saveInitializationVector(cipher.iv)
                }*/

                val sb = StringBuilder()
                for (b in cipherText) {
                    sb.append(b.toChar())
                }
                Log.d("encrypt", "dbg encrypted = [" + sb.toString() + "]")

                return Base32String.encode(cipherText)
            }
            EncryptionType.PLAIN_TEXT -> strToEncrypt
        }
    }

    fun decrypt(dataToDecrypt: String, encryptionType: EncryptionType): String {
        return when (encryptionType) {
            EncryptionType.AES -> {
                val cipher = Cipher.getInstance("AES")
                //val ivSpec = IvParameterSpec(getSavedInitializationVector())
                cipher.init(Cipher.DECRYPT_MODE, getSavedSecretKey())
                val cipherText = cipher.doFinal(Base32String.decode(dataToDecrypt))

                val sb = StringBuilder()
                for (b in cipherText) {
                    sb.append(b.toChar())
                }
                Log.d("decrypt", "dbg decrypted = [" + sb.toString() + "]")

                return Base32String.encode(cipherText)
            }
            EncryptionType.PLAIN_TEXT -> dataToDecrypt
        }

    }
}