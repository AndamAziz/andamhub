package uk.andam.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import uk.andam.app.net.Api
import java.io.File

/**
 * In-app updates from GitHub Releases: every CI build publishes `android-build-N` with
 * `Andam-N.apk`, and the app's versionCode is 1000 + N.
 */
object Updater {
    data class Release(val build: Int, val name: String, val apkUrl: String, val sizeBytes: Long, val notes: String)

    private const val LATEST = "https://api.github.com/repos/AndamAziz/andamhub/releases/latest"

    val currentBuild: Int get() = BuildConfig.VERSION_CODE - 1000

    /** The newest release, or null when this app is already up to date. Throws on network errors. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", Config.USER_AGENT)
            .build()
        Api.http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("Update server answered ${res.code}")
            val j = JSONObject(res.body?.string().orEmpty())
            val build = Regex("(\\d+)").find(j.optString("tag_name"))?.value?.toIntOrNull() ?: return@withContext null
            val assets = j.optJSONArray("assets")
            var url = ""
            var size = 0L
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size")
                }
            }
            if (url.isBlank() || build <= currentBuild) null
            else Release(build, j.optString("name"), url, size, j.optString("body"))
        }
    }

    /** Downloads the APK into the app cache, reporting 0..1 progress. */
    suspend fun download(context: Context, release: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "Andam-${release.build}.apk")
        val req = Request.Builder().url(release.apkUrl).header("User-Agent", Config.USER_AGENT).build()
        Api.media.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("Download failed (${res.code})")
            val body = res.body ?: throw IllegalStateException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        out
    }

    /** Hands the APK to Android's installer (it asks once for "install unknown apps"). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
