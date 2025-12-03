package com.doublesymmetry.trackplayer.model

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.doublesymmetry.kotlinaudio.models.AudioItemOptions
import com.doublesymmetry.kotlinaudio.models.MediaType
import com.doublesymmetry.trackplayer.utils.BundleUtils
import com.doublesymmetry.trackplayer.utils.UriUtils
import com.google.android.exoplayer2.upstream.RawResourceDataSource
import timber.log.Timber

/**
 * @author Milen Pivchev @mpivchev
 */
class Track(context: Context, bundle: Bundle, ratingType: Int) : TrackMetadata() {
    private val trackContext: Context = context
    var uri: Uri? = null
    var resourceId: Int?
    var type = MediaType.DEFAULT
    var contentType: String?
    var userAgent: String?
    var originalItem: Bundle?
    var headers: MutableMap<String, String>? = null
    val queueId: Long

    override fun setMetadata(context: Context, bundle: Bundle?, ratingType: Int) {
        super.setMetadata(context, bundle, ratingType)
        if (originalItem != null && originalItem != bundle) originalItem!!.putAll(bundle)
    }

    fun toAudioItem(): TrackAudioItem {
        // Convert file:// artwork URIs to content:// for Android Auto compatibility
        val artworkUri = artwork?.toString()
        val finalArtworkUri = if (artworkUri != null) {
            try {
                val uri = Uri.parse(artworkUri)
                when {
                    uri.scheme == "file" -> {
                        // Convert file:// to content://
                        val convertedUri = UriUtils.convertFileUriToContentUri(trackContext, artworkUri)
                        if (convertedUri != null) {
                            convertedUri
                        } else {
                            Timber.tag("RNTP-Track").w("Failed to convert artwork file:// URI, using original: $artworkUri")
                            artworkUri
                        }
                    }
                    uri.scheme == "http" || uri.scheme == "https" -> {
                        // Try to find cached version and convert to content://
                        val cachedUri = UriUtils.convertHttpUriToContentUri(trackContext, artworkUri)
                        if (cachedUri != null) {
                            cachedUri
                        } else {
                            artworkUri
                        }
                    }
                    else -> artworkUri
                }
            } catch (e: Exception) {
                Timber.tag("RNTP-Track").w(e, "Error converting artwork URI: $artworkUri")
                artworkUri
            }
        } else {
            null
        }
        
        return TrackAudioItem(this, type, uri.toString(), artist, title, album, finalArtworkUri, duration,
                AudioItemOptions(headers, userAgent, resourceId))
    }

    init {
        resourceId = BundleUtils.getRawResourceId(context, bundle, "url")
        uri = if (resourceId == 0) {
            resourceId = null
            BundleUtils.getUri(context, bundle, "url")
        } else {
            RawResourceDataSource.buildRawResourceUri(resourceId!!)
        }
        val trackType = bundle.getString("type", "default")
        for (t in MediaType.values()) {
            if (t.name.equals(trackType, ignoreCase = true)) {
                type = t
                break
            }
        }
        contentType = bundle.getString("contentType")
        userAgent = bundle.getString("userAgent")
        val httpHeaders = bundle.getBundle("headers")
        if (httpHeaders != null) {
            headers = HashMap()
            for (header in httpHeaders.keySet()) {
                headers!![header] = httpHeaders.getString(header)!!
            }
        }
        setMetadata(context, bundle, ratingType)
        queueId = System.currentTimeMillis()
        originalItem = bundle
    }
}