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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    private var tokenOrder: List<String>
        get() {
            val type = object : TypeToken<List<String>>() {}.type
            val str = prefs.getString(ORDER, "[]")
            return ArrayList(gson.fromJson<List<String>>(str, type))
        }
        set(value) {
            prefs.edit().putString(ORDER, gson.toJson(value)).apply()
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

    suspend fun addFromUriString(uriString: String): Token {
        return withContext(Dispatchers.IO) {
            val token = Token(uriString)
            add(token)
            token
        }
    }

    suspend fun move(sourceTokenId: String, targetTokenId: String) {
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

        withContext(Dispatchers.IO) {
            val order = tokenOrder.toMutableList()
            if (fromPosition < 0 || fromPosition > order.size)
                return@withContext
            if (toPosition < 0 || toPosition > order.size)
                return@withContext

            order.add(toPosition, order.removeAt(fromPosition))
            tokenOrder = order
        }
    }

    suspend fun delete(tokenId: String) {
        withContext(Dispatchers.IO) {
            val position = getOrder(tokenId) ?: run {
                Log.e(TAG, "Token $tokenId not found for deletion")
                return@withContext
            }

            val order = tokenOrder.toMutableList()
            val key = order.removeAt(position)
            tokenOrder = order
            prefs.edit().remove(key).apply()
        }
    }

    suspend fun save(token: Token) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(token.id, gson.toJson(token)).apply()
        }
    }

    suspend fun toJSON(): String {
        return withContext(Dispatchers.IO) {
            gson.toJson(SavedTokens(getTokens(), tokenOrder))
        }
    }

    @WorkerThread
    suspend fun importFromJSON(jsonString: String) {
        withContext(Dispatchers.IO) {
            val savedTokens = gson.fromJson(jsonString, SavedTokens::class.java)

            tokenOrder = savedTokens.tokenOrder

            for (token in savedTokens.tokens) {
                save(token)
            }
            prefs.edit().apply()
        }
    }

    suspend fun getTokens(): List<Token> {
        return withContext(Dispatchers.IO) {
            tokenOrder.mapNotNull { tokenId ->
                get(tokenId)
            }

        }
    }

    @Throws(TokenUriInvalidException::class)
    private suspend fun add(token: Token) {
        withContext(Dispatchers.IO) {
            val key = token.id

            // Shared preference may have key but delete in the order
            // The toke order is the source for tokens in the list
            if (tokenOrder.any { it == key })
                return@withContext

            val order = tokenOrder.toMutableList()
            order.add(0, key)
            tokenOrder = order
            prefs.edit().putString(key, gson.toJson(token)).apply()
        }
    }

    private fun getOrder(tokenId: String): Int? {
        for ((index, key) in tokenOrder.withIndex()) {
            if (key == tokenId) {
                return index
            }
        }

        return null
    }
}
