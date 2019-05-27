package org.fedorahosted.freeotp.token

import java.util.ArrayList

import com.google.gson.reflect.TypeToken
import org.fedorahosted.freeotp.token.Token.TokenUriInvalidException

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.annotation.WorkerThread

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.fedorahosted.freeotp.R
import javax.inject.Inject
import javax.inject.Singleton

private const val NAME = "tokens"
private const val ORDER = "tokenOrder"

@Singleton
class TokenPersistence @Inject constructor(private val ctx: Context) {
    private val TAG = TokenPersistence::class.java.simpleName
    private val prefs: SharedPreferences = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val gson: Gson = Gson()

    private val tokenOrder: MutableList<String>
        get() {
            val type = object : TypeToken<List<String>>() {}.type
            val str = prefs.getString(ORDER, "[]")
            return ArrayList(gson.fromJson<List<String>>(str, type))
        }

    private fun setTokenOrder(order: List<String>): SharedPreferences.Editor {
        return prefs.edit().putString(ORDER, gson.toJson(order))
    }

    operator fun get(id: String): Token? {
        val str = prefs.getString(id, null)

        try {
            return gson.fromJson(str, Token::class.java)
        } catch (jse: JsonSyntaxException) {
            // Backwards compatibility for URL-based persistence.
            try {
                return Token(str, true)
            } catch (tuie: TokenUriInvalidException) {
                tuie.printStackTrace()
            }

        }

        return null
    }

    @Throws(TokenUriInvalidException::class)
    fun add(token: Token) {
        val key = token.id

        if (prefs.contains(key))
            return

        val order = tokenOrder
        order.add(0, key)
        setTokenOrder(order).putString(key, gson.toJson(token)).apply()
    }

    fun addFromUriString(uriString: String): Token? {
        try {
            val token = Token(uriString)
            add(token)
            return token
        } catch (e: TokenUriInvalidException) {
            Toast.makeText(ctx, R.string.invalid_token, Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
        return null
    }

    fun move(sourceTokenId: String, targetTokenId: String) {
        val fromPosition = getOrder(sourceTokenId) ?: run {
            Log.e(TAG, "Token $sourceTokenId not found for moving")
            return
        }

        val toPosition = getOrder(targetTokenId) ?: run {
            Log.e(TAG, "Token $targetTokenId not found for moving")
            return
        }

        if (fromPosition == toPosition)
            return

        val order = tokenOrder
        if (fromPosition < 0 || fromPosition > order.size)
            return
        if (toPosition < 0 || toPosition > order.size)
            return

        order.add(toPosition, order.removeAt(fromPosition))
        setTokenOrder(order).apply()
    }

    fun delete(tokenId: String) {
        val position = getOrder(tokenId) ?: run {
            Log.e(TAG, "Token $tokenId not found for deletion")
            return
        }

        val key = tokenOrder.removeAt(position)
        setTokenOrder(tokenOrder).remove(key).apply()
    }

    fun save(token: Token) {
        prefs.edit().putString(token.id, gson.toJson(token)).apply()
    }

    @WorkerThread
    fun toJSON(): String {
        return gson.toJson(SavedTokens(getTokens(), tokenOrder))
    }

    @WorkerThread
    fun importFromJSON(jsonString: String) {
        val savedTokens = gson.fromJson(jsonString, SavedTokens::class.java)

        setTokenOrder(savedTokens.tokenOrder).apply()

        for (token in savedTokens.tokens) {
            save(token)
        }
        prefs.edit().apply()
    }

    fun getTokens(): List<Token> {
        return tokenOrder.mapNotNull {tokenId ->
            get(tokenId)
        }
    }

    private fun getOrder(tokenId: String): Int? {
        for ((index,key) in tokenOrder.withIndex()) {
            if (key == tokenId) {
                return index
            }
        }

        return null
    }
}
