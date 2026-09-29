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

    /** Throughput of a real provider live channel through the Andam relay, measured for 6 s. */
    suspend fun providerRoute(): Result = try {
        val src = Store.provider
        if (src.isEmpty()) Result(false, "No provider on this account")
        else {
            val first = Api.live(src).firstOrNull()
            if (first == null) Result(false, "No channel to test")
            else measure(Api.streamUrl(Api.play(src, "live", first.id).play), 6_000, first.name)
        }
    } catch (e: Exception) {
        Result(false, e.message ?: "Stream test failed")
    }

    /** Same for the first IPTV playlist channel. */
    suspend fun iptvRoute(): Result = try {
        val ip = Store.iptvSource
        val first = if (ip.isEmpty()) null else Api.iptvChannels(ip).channels.firstOrNull()
        if (first == null) Result(false, "No channel to test")
        else measure(Api.streamUrl(Api.iptvPlay(ip, first.id).token), 6_000, first.name)
    } catch (e: Exception) {
        Result(false, e.message ?: "Stream test failed")
    }

    private suspend fun measure(url: String, windowMs: Long, label: String = ""): Result = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", Config.USER_AGENT).build()
            val t0 = System.nanoTime()
            var firstByteMs = -1L
            var bytes = 0L
            Api.media.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    val why = when (res.code) {
                        504 -> "provider not responding (timeout)"
                        502 -> "relay could not reach the provider"
                        403, 401 -> "refused by the provider"
                        else -> "HTTP ${res.code}"
                    }
                    return@withContext Result(false, if (label.isNotEmpty()) "$label: $why" else why)
                }
                val type = res.header("Content-Type").orEmpty().lowercase()
                val input = res.body?.byteStream() ?: return@withContext Result(false, "Empty response")
                // An HLS channel answers with a small playlist, not a byte stream: speed is not
                // measurable this way, so report that the route works and how fast it answered.
                if ("mpegurl" in type) {
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val prefix = if (label.isNotEmpty()) "$label · " else ""
                    return@withContext Result(true, prefix + "HLS playlist OK · answered in $ms ms")
                }
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
            val prefix = if (label.isNotEmpty()) "$label · " else ""
            Result(mbps >= 2, prefix + "%.1f Mbps · first byte %d ms · %s".format(mbps, firstByteMs.coerceAtLeast(0), verdict))
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
