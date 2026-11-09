package org.oxycblt.auxio.radio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper

/** Loads station logos from the web, with a first-letter tile while loading or if there is none. */
object Logos {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(image: ImageView, letter: TextView, station: Station) {
        val url = station.logo
        letter.text = station.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        image.tag = url
        (image.parent as? android.view.View)?.clipToOutline = true // Rounded corners.
        val cached = cache.get(url)
        show(image, letter, cached)
        if (cached != null || url.isBlank() || url in failed) return
        val size = maxOf(image.layoutParams?.width ?: 0, 128)
        pool.execute {
            val bitmap = runCatching { fetch(url, size) }.getOrNull()
            main.post {
                if (bitmap == null) failed.add(url) else cache.put(url, bitmap)
                if (image.tag == url) show(image, letter, bitmap)
            }
        }
    }

    private fun show(image: ImageView, letter: TextView, bitmap: Bitmap?) {
        image.setImageBitmap(bitmap)
        image.isVisible = bitmap != null
        letter.isVisible = bitmap == null
    }

    private fun fetch(url: String, size: Int): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", RadioBrowser.USER_AGENT)
        try {
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            connection.disconnect()
        }
    }
}
