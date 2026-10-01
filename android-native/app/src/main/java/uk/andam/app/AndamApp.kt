package uk.andam.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import okhttp3.OkHttpClient
import uk.andam.app.auth.Session
import uk.andam.app.net.Api
import java.util.concurrent.TimeUnit

class AndamApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
        Api.init(this)
    }

    /**
     * Logos and posters come from many different hosts. Load them like a browser does
     * (some hosts refuse unknown agents), follow http/https redirects, and read SVG logos too.
     */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(
                    if (req.header("User-Agent") != null) req
                    else req.newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
                        .header("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
                        .build(),
                )
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }
}
