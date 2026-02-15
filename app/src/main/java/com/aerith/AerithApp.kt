package com.aerith

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

class AerithApp : Application(), ImageLoaderFactory {

    companion object {
        lateinit var videoCache: SimpleCache
            private set
    }

    override fun onCreate() {
        super.onCreate()
        val cacheDir = File(cacheDir, "video_cache")
        val databaseProvider = StandaloneDatabaseProvider(this)
        val evictor = LeastRecentlyUsedCacheEvictor(2048L * 1024 * 1024) // 2GB
        videoCache = SimpleCache(cacheDir, evictor, databaseProvider)
    }

    override fun newImageLoader(): ImageLoader {
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Aerith/1.0")
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.3) // Increase memory cache to 30%
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(2048L * 1024 * 1024) // 2GB
                    .build()
            }
            .fetcherDispatcher(Dispatchers.IO)
            .decoderDispatcher(Dispatchers.Default)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
                add(coil.decode.VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
