package com.aerith

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
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
        videoCache = SimpleCache(cacheDir, NoOpCacheEvictor(), databaseProvider)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.3) // Increase memory cache to 30%
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512 * 1024 * 1024) // 512MB
                    .build()
            }
            .fetcherDispatcher(Dispatchers.IO)
            .decoderDispatcher(Dispatchers.Default)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
