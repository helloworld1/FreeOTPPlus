package org.fedorahosted.freeotp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageUtil @Inject constructor(val context: Context) {

    suspend fun saveImageUriToFile(uri: Uri):Uri = withContext(Dispatchers.IO) {
        val outputFile = File(context.filesDir.absolutePath + "/" + "image" + uri.toString().hashCode() + ".jpg")
        val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)

        outputFile.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }

        outputFile.toUri()
    }
}