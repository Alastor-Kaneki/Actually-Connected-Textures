package com.alastor.ctmforge

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.alastor.ctmforge.core.Converter
import com.alastor.ctmforge.core.Img
import com.alastor.ctmforge.core.net.Http
import com.alastor.ctmforge.data.Library
import com.alastor.ctmforge.data.Settings
import com.alastor.ctmforge.ui.RemoteImages

class App : Application() {
    lateinit var settings: Settings
        private set
    lateinit var library: Library
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        library = Library(this)
        Http.userAgent = "alastor/ctm-forge/${BuildConfig.VERSION_CODE} (com.alastor.ctmforge)"
        Converter.fallbackDecoder = ::decodeWithAndroid
        RemoteImages.init(this)
    }
}

/** Decodes non-PNG tiles (JPEG, WebP, ...) with Android, keeping alpha unpremultiplied. */
fun decodeWithAndroid(bytes: ByteArray): Img? {
    val opts = BitmapFactory.Options().apply {
        inPremultiplied = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    return try {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        Img(bmp.width, bmp.height, px)
    } finally {
        bmp.recycle()
    }
}
