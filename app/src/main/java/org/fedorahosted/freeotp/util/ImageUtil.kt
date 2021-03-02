package org.fedorahosted.freeotp.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.token.Token
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageUtil @Inject constructor(val context: Context) {
    suspend fun saveImageUriToFile(uri: Uri):Uri = withContext(Dispatchers.IO) {
        val outputFile = File(context.filesDir.absolutePath + "/image" + uri.toString().hashCode() + ".png")
        val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)

        outputFile.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 0, it)
        }

        outputFile.toUri()
    }

    suspend fun fetchImages(tokens: List<Token>) = withContext(Dispatchers.IO) {
        tokens.forEach {
            if (it.image != null) {
                Picasso.get().load(it.image).fetch()
            }
        }
    }
}
