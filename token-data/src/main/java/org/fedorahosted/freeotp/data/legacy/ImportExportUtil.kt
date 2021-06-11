package org.fedorahosted.freeotp.data.legacy

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.data.MigrationUtil
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class ImportExportUtil @Inject constructor(@ApplicationContext private val context: Context,
                                           private val migrationUtil: MigrationUtil,
                                           private val gson: Gson,
                                           private val otpTokenDatabase: OtpTokenDatabase,
                                           private val tokenPersistence: TokenPersistence
) {
    suspend fun importJsonFile(uri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri).use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                val stringBuilder = StringBuilder()
                reader.forEachLine {
                    stringBuilder.append(it)
                }


                val savedTokens = gson.fromJson(stringBuilder.toString(), SavedTokens::class.java)

                val newTokens = migrationUtil.convertLegacyTokensToOtpTokens(savedTokens.tokens);
                otpTokenDatabase.otpTokenDao().insertAll(newTokens)

            }
        }
    }

    suspend fun exportJsonFile(uri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "w").use { outputStream ->
                val otpTokens = otpTokenDatabase.otpTokenDao().getAll().first() ?: return@use
                val legacyTokens = migrationUtil.convertOtpTokensToLegacyTokens(otpTokens)
                val tokenOrder = otpTokens.map {
                    if (it.issuer != null) {
                        "${it.issuer}:${it.label}"
                    } else {
                        it.label
                    }
                }.toList()

                val jsonString = gson.toJson(SavedTokens(legacyTokens, tokenOrder))

                outputStream?.write(jsonString.toByteArray())
            }
        }
    }

    suspend fun importKeyUriFile(fileUri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(fileUri)?.reader()?.use { reader ->
                reader.readLines().filter {
                    it.isNotBlank()

                }.map { line ->
                    OtpTokenFactory.createFromUri(Uri.parse(line.trim()))
                }
            } ?.let { tokens ->
                otpTokenDatabase.otpTokenDao().insertAll(tokens)
            }
        }
    }

    suspend fun exportKeyUriFile(fileUri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                PrintWriter(outputStream).use { printWriter ->
                    val tokens = otpTokenDatabase.otpTokenDao().getAll().first()
                    for (token in tokens ) {
                        printWriter.println(token.toString())
                    }
                }
            }
        }
    }
}