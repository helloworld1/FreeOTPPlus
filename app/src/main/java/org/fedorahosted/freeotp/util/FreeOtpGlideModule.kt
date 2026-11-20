package org.fedorahosted.freeotp.util

import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.target.Target
import java.io.InputStream

@GlideModule
class FreeOtpGlideModule : AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.append(
            Registry.BUCKET_BITMAP,
            InputStream::class.java,
            Bitmap::class.java,
            SvgBitmapDecoder(glide.bitmapPool)
        )
    }

    override fun isManifestParsingEnabled(): Boolean = false
}

class SvgBitmapDecoder(
    private val bitmapPool: BitmapPool
) : ResourceDecoder<InputStream, Bitmap> {

    override fun handles(source: InputStream, options: Options): Boolean {
        return SvgUtil.looksLikeSvg(source)
    }

    override fun decode(
        source: InputStream,
        width: Int,
        height: Int,
        options: Options
    ): Resource<Bitmap>? {
        val targetWidth = if (width == Target.SIZE_ORIGINAL || width <= 0) {
            SvgUtil.DEFAULT_RENDER_SIZE_PX
        } else {
            width
        }
        val targetHeight = if (height == Target.SIZE_ORIGINAL || height <= 0) {
            SvgUtil.DEFAULT_RENDER_SIZE_PX
        } else {
            height
        }
        val bitmap = SvgUtil.renderSvgToBitmap(
            source = source,
            requestedWidth = targetWidth,
            requestedHeight = targetHeight,
            bitmapProvider = { w, h, config -> bitmapPool.getDirty(w, h, config) }
        )
        return BitmapResource.obtain(bitmap, bitmapPool)
    }
}
