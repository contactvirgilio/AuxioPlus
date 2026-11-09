package org.oxycblt.auxio.radio

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Searches the free, community-run radio-browser.info directory. */
object RadioBrowser {
    private val servers =
        listOf(
            "https://all.api.radio-browser.info",
            "https://de1.api.radio-browser.info",
            "https://de2.api.radio-browser.info",
            "https://fi1.api.radio-browser.info",
        )
    private var working = servers.first()

    /** Stations whose name matches [query], most popular first. A blank query gives the top 50. */
    suspend fun search(query: String): List<Station> =
        withContext(Dispatchers.IO) {
            val path =
                if (query.isBlank()) "/json/stations/topclick/50?hidebroken=true"
                else
                    "/json/stations/search?limit=60&hidebroken=true&order=clickcount&reverse=true&name=" +
                        URLEncoder.encode(query.trim(), "UTF-8")
            val array = JSONArray(get(path))
            val seen = HashSet<String>()
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val url = o.optString("url_resolved").ifBlank { o.optString("url") }
                    if (url.isBlank() || !seen.add(url)) continue
                    val codec = o.optString("codec").takeIf { it.isNotBlank() && it != "UNKNOWN" }
                    val bitrate = o.optInt("bitrate").takeIf { it > 0 }?.let { "${it}k" }
                    val place =
                        listOf(o.optString("state"), o.optString("country"))
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                    val quality = listOfNotNull(codec, bitrate).joinToString(" ")
                    add(
                        Station(
                            id = o.optString("stationuuid").ifBlank { Station.newId() },
                            name = o.optString("name").trim().ifBlank { url },
                            url = url,
                            logo = o.optString("favicon"),
                            info = listOf(quality, place).filter { it.isNotBlank() }.joinToString(" · "),
                        )
                    )
                }
            }
        }

    private fun get(path: String): String {
        var last: Exception? = null
        for (server in listOf(working) + servers.filter { it != working }) {
            try {
                val text = fetchText(server + path)
                working = server
                return text
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("No server")
    }

    fun fetchText(url: String, maxBytes: Int = 4 * 1024 * 1024): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.use { input ->
                val bytes = input.readNBytesCompat(maxBytes)
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    const val USER_AGENT = "AuxioRadio/1.0"
}

/** Turns .pls and .m3u playlist links into the stream they point to. Others pass through. */
object StreamResolver {
    suspend fun resolve(url: String): String =
        withContext(Dispatchers.IO) {
            val path = url.substringBefore('?').lowercase()
            if (!path.endsWith(".pls") && !path.endsWith(".m3u")) return@withContext url
            runCatching {
                    RadioBrowser.fetchText(url, 64 * 1024)
                        .lineSequence()
                        .map { line ->
                            val t = line.trim()
                            // .pls lines look like "File1=http://...", .m3u lines are bare URLs.
                            if (t.startsWith("http")) t else t.substringAfter('=', "").trim()
                        }
                        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                }
                .getOrNull() ?: url
        }
}
