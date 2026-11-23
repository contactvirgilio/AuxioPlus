package org.oxycblt.auxio.radio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.isVisible
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Finds and loads a logo for every station, trying hard before giving up:
 * 1. the logo the directory (or the user) gave,
 * 2. the station's website: its app icon (apple-touch-icon), a large site icon, then its share image,
 * 3. Wikipedia, in the station's own language first (Portuguese, Spanish), then English: the picture
 *    at the top of the station's article, which for radio stations is nearly always the logo.
 * A logo smaller than [GOOD_SIZE] px only counts if nothing better turns up. The address found is
 * remembered, so each station is only looked up once. Until then, or if there's none, the station's
 * first letter shows.
 */
object Logos {
    private const val TAG = "Logos"
    private const val GOOD_SIZE = 96
    private const val UA = "AuxioRadio/1.2 (https://github.com/contactvirgilio/AuxioPlus)"
    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var found: android.content.SharedPreferences

    fun init(context: Context) {
        if (!::found.isInitialized) found = context.getSharedPreferences("logos", Context.MODE_PRIVATE)
    }

    fun load(image: ImageView, letter: TextView, station: Station) {
        init(image.context)
        letter.text = station.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        (image.parent as? View)?.clipToOutline = true // Rounded corners.
        val key = station.id + "|" + station.logo
        image.tag = key
        val known = found.getString(key, null)
        val cached = known?.let { bitmaps.get(it) }
        show(image, letter, cached)
        if (cached != null || known == "") return
        if (!pending.add(key + image.hashCode())) return
        val size = maxOf(image.layoutParams?.width ?: 0, 160)
        pool.execute {
            var url = known
            var bitmap = url?.let { runCatching { decode(it, size) }.getOrNull() }
            if (bitmap == null) {
                val hit = runCatching { find(station, size) }.getOrNull()
                url = hit?.first
                bitmap = hit?.second
            }
            main.post {
                pending.remove(key + image.hashCode())
                found.edit { putString(key, url ?: "") }
                if (url != null && bitmap != null) {
                    bitmaps.put(url, bitmap)
                    // A favourite keeps what was found, so the notification shows it too.
                    Favorites.byId(station.id)?.let {
                        if (it.logo != url) {
                            found.edit { putString(it.id + "|" + url, url) }
                            Favorites.update(it.copy(logo = url))
                        }
                    }
                }
                if (image.tag == key) show(image, letter, bitmap)
            }
        }
    }

    private fun show(image: ImageView, letter: TextView, bitmap: Bitmap?) {
        image.setImageBitmap(bitmap)
        image.isVisible = bitmap != null
        letter.isVisible = bitmap == null
    }

    /** Try every source in turn. Returns the address and the picture. */
    private fun find(station: Station, size: Int): Pair<String, Bitmap>? {
        var small: Pair<String, Bitmap>? = null
        val tried = HashSet<String>()
        fun attempt(url: String?): Pair<String, Bitmap>? {
            if (url.isNullOrBlank() || !url.startsWith("http") || !tried.add(url)) return null
            val bitmap = runCatching { decode(url, size) }.getOrNull() ?: return null
            val good = minOf(bitmap.width, bitmap.height) >= GOOD_SIZE &&
                bitmap.width <= bitmap.height * 2.2f // Not a wide banner.
            if (good) return url to bitmap
            if (small == null || bitmap.width > small!!.second.width) small = url to bitmap
            return null
        }
        attempt(station.logo)?.let { return it }
        for (url in fromWebsite(station.homepage)) attempt(url)?.let { return it }
        for (lang in languages(station.country)) {
            for (url in fromWikipedia(station.name, lang)) attempt(url)?.let { return it }
        }
        return small
    }

    // ---- The station's website ----

    private val linkTag = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)

    private fun attr(tag: String, name: String) =
        Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    /** Logo candidates from the homepage, best first. */
    private fun fromWebsite(homepage: String): List<String> {
        if (!homepage.startsWith("http")) return emptyList()
        val base = runCatching { URL(homepage) }.getOrNull() ?: return emptyList()
        val html = runCatching { text(homepage, 512 * 1024) }.getOrNull() ?: ""
        val touch = ArrayList<String>()
        val icons = ArrayList<Pair<Int, String>>()
        val shares = ArrayList<String>()
        for (m in linkTag.findAll(html)) {
            val tag = m.value
            val rel = attr(tag, "rel")?.lowercase() ?: continue
            val href = attr(tag, "href") ?: continue
            val abs = runCatching { URL(base, href.replace("&amp;", "&")).toString() }.getOrNull() ?: continue
            when {
                "apple-touch-icon" in rel -> touch.add(abs)
                "icon" in rel -> {
                    val px = attr(tag, "sizes")?.substringBefore('x')?.toIntOrNull() ?: 0
                    icons.add(px to abs)
                }
            }
        }
        for (m in metaTag.findAll(html)) {
            val tag = m.value
            val prop = (attr(tag, "property") ?: attr(tag, "name"))?.lowercase() ?: continue
            if (prop == "og:image" || prop == "twitter:image") attr(tag, "content")?.let {
                runCatching { URL(base, it.replace("&amp;", "&")).toString() }.getOrNull()?.let(shares::add)
            }
        }
        val origin = "${base.protocol}://${base.host}"
        return touch +
            icons.filter { it.first >= GOOD_SIZE }.sortedByDescending { it.first }.map { it.second } +
            listOf("$origin/apple-touch-icon.png") +
            shares +
            icons.filter { it.first < GOOD_SIZE }.map { it.second }
    }

    // ---- Wikipedia ----

    private fun languages(country: String) =
        when (country.uppercase()) {
            "PT", "BR", "AO", "MZ", "CV" -> listOf("pt", "en")
            "ES", "MX", "AR", "CO", "CL", "PE", "VE", "DO", "CU", "UY", "EC" -> listOf("es", "en")
            else -> listOf("en", "pt", "es")
        }

    /** "Canarias Radio (Tenerife) - 104.7 FM" becomes "Canarias Radio". */
    private fun cleanName(name: String) =
        name.replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            .split(Regex("\\s[-–|·:]\\s"))
            .first()
            // Frequencies only ("FM 90.4", "104.7", "97 FM"), so a name like "Z 101" keeps its number.
            .replace(Regex("(?i)\\b(fm|am)\\s*\\d{2,3}([.,]\\d)?\\b|\\b\\d{2,3}[.,]\\d\\s*(fm|mhz)?\\b|\\b\\d{2,3}\\s*(fm|mhz)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { name.trim() }

    private fun plain(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    /** Pictures from the best matching articles, best first. */
    private fun fromWikipedia(name: String, lang: String): List<String> {
        val clean = cleanName(name)
        // Every meaningful word of the name has to be in the article's title.
        val words =
            plain(clean).split(Regex("[^a-z0-9]+")).filter {
                it.length >= 2 && it !in setOf("radio", "fm", "am", "the", "la", "el", "de", "da", "do", "of")
            }
        if (words.isEmpty()) return emptyList()
        val query = URLEncoder.encode("$clean radio", "UTF-8")
        val api =
            "https://$lang.wikipedia.org/w/api.php?action=query&format=json&generator=search" +
                "&gsrsearch=$query&gsrlimit=6&prop=pageimages&piprop=thumbnail&pithumbsize=320&redirects=1"
        return try {
            val pages = JSONObject(text(api, 256 * 1024)).optJSONObject("query")?.optJSONObject("pages")
                ?: return emptyList()
            val hits = ArrayList<Pair<Int, String>>()
            for (id in pages.keys()) {
                val page = pages.getJSONObject(id)
                val thumb = page.optJSONObject("thumbnail")?.optString("source") ?: continue
                val title = plain(page.optString("title"))
                if (words.all { it in title }) hits.add(page.optInt("index", 99) to thumb)
            }
            hits.sortedBy { it.first }.map { it.second }
        } catch (e: Exception) {
            Log.w(TAG, "wikipedia $lang $name: $e")
            emptyList()
        }
    }

    // ---- Network ----

    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        // Follow redirects ourselves, since http -> https isn't followed automatically.
        repeat(5) {
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code in 300..399) {
                val next = c.getHeaderField("Location") ?: return c
                c.disconnect()
                current = URL(current, next)
            } else return c
        }
        error("Too many redirects")
    }

    private fun bytes(url: String, max: Int): ByteArray {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() < max) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    private fun text(url: String, max: Int) = String(bytes(url, max), Charsets.UTF_8)

    private fun decode(url: String, size: Int): Bitmap? {
        val data = bytes(url, 4 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
