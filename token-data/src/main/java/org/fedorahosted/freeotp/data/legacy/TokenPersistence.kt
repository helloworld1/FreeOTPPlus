package org.fedorahosted.freeotp.data.legacy

import java.util.ArrayList

import com.google.gson.reflect.TypeToken

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.util.Log
import androidx.annotation.WorkerThread

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenPersistence @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val prefs: SharedPreferences = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val globalPrefs =
        PreferenceManager.getDefaultSharedPreferences(ctx)

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

    suspend fun getToken(id: String): Token? = withContext(Dispatchers.IO) {
        val str = prefs.getString(id, null)

        try {
            gson.fromJson(str, Token::class.java)
        } catch (jse: JsonSyntaxException) {
            // Backwards compatibility for URL-based persistence.
            try {
                Token(str, true)
            } catch (tuie: Token.TokenUriInvalidException) {
                tuie.printStackTrace()
                null
            }
        }
    }

    suspend fun addFromUriString(uriString: String): Token {
        return withContext(Dispatchers.IO) {
            val token = Token(uriString)
            add(token)
            token
        }
    }

    suspend fun move(sourceTokenId: String, targetTokenId: String) = withContext(Dispatchers.IO) {
        val fromPosition = getOrder(sourceTokenId) ?: run {
            Log.e(TAG, "Token $sourceTokenId not found for moving")
            return@withContext
        }

        val toPosition = getOrder(targetTokenId) ?: run {
            Log.e(TAG, "Token $targetTokenId not found for moving")
            return@withContext
        }

        if (fromPosition == toPosition)
            return@withContext

        val order = tokenOrder.toMutableList()
        if (fromPosition < 0 || fromPosition > order.size)
            return@withContext
        if (toPosition < 0 || toPosition > order.size)
            return@withContext

        order.add(toPosition, order.removeAt(fromPosition))
        tokenOrder = order
    }

    suspend fun delete(tokenId: String) = withContext(Dispatchers.IO) {
        val position = getOrder(tokenId) ?: run {
            Log.e(TAG, "Token $tokenId not found for deletion")
            return@withContext
        }

        val order = tokenOrder.toMutableList()
        val key = order.removeAt(position)
        tokenOrder = order
        prefs.edit().remove(key).apply()
    }

    suspend fun save(token: Token) = withContext(Dispatchers.IO) {
        prefs.edit().putString(token.id, gson.toJson(token)).apply()
    }

    suspend fun toJSON(): String {
        return withContext(Dispatchers.IO) {
            gson.toJson(SavedTokens(getTokens(), tokenOrder))
        }
    }

    @WorkerThread
    suspend fun importFromJSON(jsonString: String) = withContext(Dispatchers.IO) {
        val savedTokens = gson.fromJson(jsonString, SavedTokens::class.java)

        tokenOrder = savedTokens.tokenOrder

        for (token in savedTokens.tokens) {
            save(token)
        }
        prefs.edit().apply()
    }

    suspend fun getTokens(): List<Token> = withContext(Dispatchers.IO) {
        tokenOrder.mapNotNull { tokenId ->
            getToken(tokenId)
        }
    }

    fun isLegacyTokenMigrated(): Boolean {
        return globalPrefs.getBoolean(TOKEN_MIGRATED_KEY, false)
    }

    fun setLegacyTokenMigrated() {
        globalPrefs.edit().putBoolean(TOKEN_MIGRATED_KEY, true).apply()
    }

    @Throws(Token.TokenUriInvalidException::class)
    private suspend fun add(token: Token) = withContext(Dispatchers.IO) {
        val key = token.id

        // Shared preference may have key but delete in the order
        // The toke order is the source for tokens in the list
        if (tokenOrder.any { it == key } && getToken(key) != null)
            return@withContext

        val order = tokenOrder.toMutableList()
        order.add(0, key)
        tokenOrder = order
        prefs.edit().putString(key, gson.toJson(token)).apply()
    }

    private suspend fun getOrder(tokenId: String): Int? = withContext(Dispatchers.IO) {
        for ((index, key) in tokenOrder.withIndex()) {
            if (key == tokenId) {
                return@withContext index
            }
        }

        return@withContext null
    }

    companion object {
        val TAG = TokenPersistence::class.java.simpleName

        private const val NAME = "tokens"
        private const val ORDER = "tokenOrder"
        private const val TOKEN_MIGRATED_KEY = "tokenMigrated"
    }
}
