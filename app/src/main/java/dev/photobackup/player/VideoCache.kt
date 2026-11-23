package dev.photobackup.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.LoadControl

/**
 * Drive videos are cached on the phone as they stream (least recently used first out, [MAX_BYTES] in all), so a
 * looping video or one opened again plays from the phone instead of downloading again. Safe to key by path: the app
 * never overwrites a file on the drive. One [SimpleCache] per folder per process, hence the singleton.
 */
@OptIn(UnstableApi::class)
object VideoCache {
    private const val MAX_BYTES = 512L * 1024 * 1024
    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            context.applicationContext.cacheDir.resolve("video"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    /** Start playing after 1 s buffered (default 2.5 s); drive videos then keep filling ahead while they play. */
    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 15_000,
            /* maxBufferMs = */ 50_000,
            /* bufferForPlaybackMs = */ 1_000,
            /* bufferForPlaybackAfterRebufferMs = */ 2_000,
        )
        .build()

    /**
     * The renderers every player here uses (viewer and review cards). If the primary decoder for a track fails to
     * start, the next one on the phone is tried. Extension renderers are ON (after the phone's own decoders), so an
     * FFmpeg audio decoder on the classpath would be picked up as a fallback; none is bundled yet (no build for
     * media3 1.4.x on Maven Central).
     */
    @OptIn(UnstableApi::class)
    fun renderersFactory(context: Context): RenderersFactory = DefaultRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
}
