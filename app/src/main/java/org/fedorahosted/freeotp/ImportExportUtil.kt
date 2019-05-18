package org.fedorahosted.freeotp

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader


class ImportExportUtil(private val context: Context,
                       private val tokenPersistence: TokenPersistence) {

    suspend fun importJson(uri: Uri) {
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

    suspend fun exportJson(uri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "w").use { outputStream ->
                outputStream?.write(tokenPersistence.toJSON().toByteArray())
            }
        }
    }
}