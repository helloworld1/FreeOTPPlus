package org.fedorahosted.freeotp.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.token.TokenPersistence
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class ImportExportUtil @Inject constructor(private val context: Context,
                                           private val tokenPersistence: TokenPersistence) {
    suspend fun importJsonFile(uri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri).use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                val stringBuilder = StringBuilder()
                reader.forEachLine {
                    stringBuilder.append(it)
                }
                tokenPersistence.importFromJSON(stringBuilder.toString())
            }
        }
    }

    suspend fun exportJsonFile(uri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "w").use { outputStream ->
                outputStream?.write(tokenPersistence.toJSON().toByteArray())
            }
        }
    }

    suspend fun importKeyUriFile(fileUri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(fileUri)?.reader()?.use { reader ->
                reader.readLines().asReversed().forEach { line ->
                    if (line.isNotBlank()) {
                        tokenPersistence.addFromUriString(line.trim())
                    }
                }

            }
        }
    }

    suspend fun exportKeyUriFile(fileUri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                PrintWriter(outputStream).use { printWriter ->
                    for (i in 0 until tokenPersistence.length()) {
                        val token = tokenPersistence[i]
                        if (token != null) {
                            printWriter.println(token.toString())
                        }
                    }
                }
            }
        }
    }
}