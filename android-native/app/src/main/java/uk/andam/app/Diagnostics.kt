package uk.andam.app

import android.media.MediaCodecList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uk.andam.app.net.Api
import uk.andam.app.ui.Store

/** Server check, internet speed and stream-route speed for the Account → Diagnostics card. */
object Diagnostics {
    data class Result(val ok: Boolean, val value: String)

    /** Round trip to the Andam API. */
    suspend fun server(): Result = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        try {
            Api.clearCache()
            Api.access()
            val ms = (System.nanoTime() - t0) / 1_000_000
            Result(true, "Online · $ms ms")
        } catch (e: Exception) {
            Result(false, "Unreachable (${e.message ?: "error"})")
        }
    }

    /** Download speed to the internet (Cloudflare test file), measured for up to 8 s. */
    suspend fun internet(): Result = measure("https://speed.cloudflare.com/__down?bytes=50000000", 8_000)

    /** Throughput of a real live stream through the Andam relay, measured for 6 s. */
    suspend fun streamRoute(): Result {
        return try {
            val src = Store.provider
            val url = if (src.isNotEmpty()) {
                val first = Api.live(src).firstOrNull() ?: return Result(false, "No channel to test")
                Api.streamUrl(Api.play(src, "live", first.id).play)
            } else {
                val ip = Store.iptvSource.ifEmpty { return Result(false, "No channel to test") }
                val first = Api.iptvChannels(ip).channels.firstOrNull() ?: return Result(false, "No channel to test")
                Api.streamUrl(Api.iptvPlay(ip, first.id).token)
            }
            measure(url, 6_000)
        } catch (e: Exception) {
            Result(false, e.message ?: "Stream test failed")
        }
    }

    private suspend fun measure(url: String, windowMs: Long): Result = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", Config.USER_AGENT).build()
            val t0 = System.nanoTime()
            var firstByteMs = -1L
            var bytes = 0L
            Api.media.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext Result(false, "HTTP ${res.code}")
                val input = res.body?.byteStream() ?: return@withContext Result(false, "Empty response")
                val buf = ByteArray(64 * 1024)
                while ((System.nanoTime() - t0) / 1_000_000 < windowMs) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (firstByteMs < 0) firstByteMs = (System.nanoTime() - t0) / 1_000_000
                    bytes += n
                }
            }
            val seconds = ((System.nanoTime() - t0) / 1e9).coerceAtLeast(0.001)
            val mbps = bytes * 8 / seconds / 1_000_000
            val verdict = when {
                mbps >= 8 -> "good for HD"
                mbps >= 4 -> "OK for HD"
                mbps >= 2 -> "SD only"
                else -> "too slow"
            }
            Result(mbps >= 2, "%.1f Mbps · first byte %d ms · %s".format(mbps, firstByteMs.coerceAtLeast(0), verdict))
        } catch (e: Exception) {
            Result(false, e.message ?: "Failed")
        }
    }

    /** Whether this device has a hardware/software decoder for H.265 (HEVC). */
    fun hevcSupported(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
        }
    }.getOrDefault(false)
}
