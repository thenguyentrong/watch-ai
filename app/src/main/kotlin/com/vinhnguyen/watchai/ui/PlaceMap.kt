package com.vinhnguyen.watchai.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * A small map of a place for the pop-up, from OpenStreetMap: the place found by name (Nominatim)
 * and a few map tiles around it, no key needed. Only when the user asks for directions; OSM sees
 * the place and the phone's IP address. Their usage policy wants a real app name and light use.
 */
object PlaceMap {
    private const val AGENT = "Buddy/0.1 (personal assistant; https://github.com/thenguyentrong/watch-ai)"
    private val HOSTS = setOf("nominatim.openstreetmap.org", "tile.openstreetmap.org")
    private const val ZOOM = 15
    private const val TILE = 256

    private val http =
        OkHttpClient
            .Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                require(request.url.host in HOSTS) { "not a map host" }
                chain.proceed(request.newBuilder().header("User-Agent", AGENT).build())
            }.build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = LruCache<String, ImageBitmap>(8)

    /** A [width] by [height] map around [place] with a dot on it, or null if it can't be found. */
    suspend fun render(
        place: String,
        width: Int,
        height: Int,
        dot: Int,
    ): ImageBitmap? = cache.get(place) ?: withContext(Dispatchers.IO) {
        runCatching {
            val (lat, lon) = find(place) ?: return@runCatching null
            draw(lat, lon, width, height, dot)
        }.getOrNull()?.also { cache.put(place, it) }
    }

    private fun find(place: String): Pair<Double, Double>? {
        val url =
            "https://nominatim.openstreetmap.org/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", place)
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("limit", "1")
                .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val first = json.parseToJsonElement(response.body.string()).jsonArray.firstOrNull()?.jsonObject ?: return null
            val lat = first["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
            val lon = first["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
            return lat to lon
        }
    }

    private fun draw(
        lat: Double,
        lon: Double,
        width: Int,
        height: Int,
        dot: Int,
    ): ImageBitmap {
        val n = 1 shl ZOOM
        val x = (lon + 180) / 360 * n * TILE
        val rad = lat * PI / 180
        val y = (1 - ln(tan(rad) + 1 / cos(rad)) / PI) / 2 * n * TILE
        val left = x - width / 2.0
        val top = y - height / 2.0
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        for (tx in floor(left / TILE).toInt()..floor((left + width) / TILE).toInt()) {
            for (ty in floor(top / TILE).toInt()..floor((top + height) / TILE).toInt()) {
                if (ty !in 0 until n) continue
                val tile = tile(ZOOM, Math.floorMod(tx, n), ty) ?: continue
                canvas.drawBitmap(tile, (tx * TILE - left).toFloat(), (ty * TILE - top).toFloat(), null)
            }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(width / 2f, height / 2f, height * 0.075f, paint)
        paint.color = dot
        canvas.drawCircle(width / 2f, height / 2f, height * 0.05f, paint)
        return out.asImageBitmap()
    }

    private fun tile(
        z: Int,
        x: Int,
        y: Int,
    ): Bitmap? = http.newCall(Request.Builder().url("https://tile.openstreetmap.org/$z/$x/$y.png").build()).execute().use { response ->
        if (response.isSuccessful) BitmapFactory.decodeStream(response.body.byteStream()) else null
    }
}
