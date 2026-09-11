package com.doublesymmetry.kotlinaudio.players.components

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.DefaultSsChunkSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import com.doublesymmetry.kotlinaudio.models.AudioItem
import com.doublesymmetry.kotlinaudio.models.CacheConfig
import com.doublesymmetry.kotlinaudio.models.MediaType
import com.doublesymmetry.kotlinaudio.utils.isUriLocalFile

/**
 * The per-item media-source assembly that used to live in `BaseAudioPlayer.getMediaSourceFromAudioItem`.
 *
 * It is a real [MediaSource.Factory] now rather than a helper, because that is what lets the engine
 * drive ExoPlayer through the `MediaItem` APIs — `setMediaItems(items, startIndex, startPositionMs)`
 * (the atomic `loadQueue`) and `replaceMediaItem` (metadata overrides that do not interrupt
 * playback). Everything the old helper needed per item — headers, user agent, raw resource id and
 * [MediaType] — travels on the item as an [com.doublesymmetry.kotlinaudio.models.AudioItemHolder]
 * tag and is read back here.
 */
@UnstableApi
internal class MediaFactory(
    private val context: Context,
    /** Reassignable: the cache config can arrive after the player exists (Android Auto cold start). */
    var cacheConfig: CacheConfig? = null,
    var cache: SimpleCache? = null,
) : MediaSource.Factory {

    private var drmSessionManagerProvider: DrmSessionManagerProvider? = null
    private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        drmSessionManagerProvider = provider
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        loadErrorHandlingPolicy = policy
        return this
    }

    override fun getSupportedTypes(): IntArray =
        intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_SS)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val audioItem = mediaItem.localConfiguration?.tag as? com.doublesymmetry.kotlinaudio.models.AudioItemHolder
        val item = audioItem?.audioItem
        val dataSourceFactory = dataSourceFactoryFor(item, mediaItem)
        val source = when (item?.type) {
            MediaType.DASH -> DashMediaSource.Factory(DefaultDashChunkSource.Factory(dataSourceFactory), dataSourceFactory)
                .applyPolicies()
                .createMediaSource(mediaItem)
            MediaType.HLS -> HlsMediaSource.Factory(dataSourceFactory)
                .applyPolicies()
                .createMediaSource(mediaItem)
            MediaType.SMOOTH_STREAMING -> SsMediaSource.Factory(DefaultSsChunkSource.Factory(dataSourceFactory), dataSourceFactory)
                .applyPolicies()
                .createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory, progressiveExtractorsFactory())
                .applyPolicies()
                .createMediaSource(mediaItem)
        }
        return source
    }

    private fun <T : MediaSource.Factory> T.applyPolicies(): T {
        drmSessionManagerProvider?.let { setDrmSessionManagerProvider(it) }
        loadErrorHandlingPolicy?.let { setLoadErrorHandlingPolicy(it) }
        return this
    }

    private fun dataSourceFactoryFor(item: AudioItem?, mediaItem: MediaItem): DataSource.Factory {
        val uri = mediaItem.localConfiguration?.uri ?: Uri.parse(item?.audioUrl ?: "")
        val userAgent =
            if (item?.options == null || item.options!!.userAgent.isNullOrBlank()) {
                Util.getUserAgent(context, APPLICATION_NAME)
            } else {
                item.options!!.userAgent
            }

        return when {
            item?.options?.resourceId != null -> DataSource.Factory { RawResourceDataSource(context) }
            isUriLocalFile(uri) -> DefaultDataSource.Factory(context)
            else -> {
                val http = DefaultHttpDataSource.Factory().apply {
                    setUserAgent(userAgent)
                    setAllowCrossProtocolRedirects(true)
                    item?.options?.headers?.let { setDefaultRequestProperties(it.toMap()) }
                }
                enableCaching(http)
            }
        }
    }

    private fun enableCaching(factory: DataSource.Factory): DataSource.Factory {
        val cache = this.cache
        val config = this.cacheConfig
        return if (cache == null || config == null || (config.maxCacheSize ?: 0) <= 0) {
            factory
        } else {
            CacheDataSource.Factory().apply {
                setCache(cache)
                setUpstreamDataSourceFactory(factory)
                setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            }
        }
    }

    companion object {
        const val APPLICATION_NAME = "react-native-track-player"

        /**
         * The extractor stack every progressive source is built on.
         *
         * The two flags make ExoPlayer seek by arithmetic when an MP3 has no seek table (our
         * prayers), even if the response carries no Content-Length — every file we serve is CBR.
         * [CbrMp3Extractor] then hides the `Info` frame's table of contents so the *table* cannot be
         * used either. Media3 fixed that upstream in 1.4 (androidx/media#1376), so on this stack the
         * wrapper is belt and braces rather than the fix it was on ExoPlayer 2.19.1 — it is kept
         * because `CbrMp3SeekAccuracyTest` measures the property, not the mechanism, and because a
         * future media3 regression would otherwise be silent.
         *
         * Exposed so `CbrMp3SeekAccuracyTest` can measure the byte offsets *this* stack asks for,
         * rather than a copy of it assembled in the test that could agree with itself while the
         * player wiring drifts.
         */
        fun progressiveExtractorsFactory(): ExtractorsFactory {
            val extractors = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)
            return CbrMp3Extractor.wrapping(extractors)
        }
    }
}
