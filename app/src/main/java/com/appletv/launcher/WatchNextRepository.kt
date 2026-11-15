package com.appletv.launcher

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.tvprovider.media.tv.TvContractCompat
import java.util.concurrent.Executors

data class WatchNextItem(
    val id: Long,
    val title: String,
    val episodeSubtitle: String?,
    val posterArtUri: String?,
    val lastPlaybackPositionMillis: Long,
    val durationMillis: Long,
    val lastEngagementTimeUtcMillis: Long,
    val progressPercent: Int,
    val intentUri: String?,
    val packageName: String?,
    val launchIntent: Intent?
)

class WatchNextRepository(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val queryExecutor = Executors.newSingleThreadExecutor()
    private var watchNextObserver: ContentObserver? = null
    private var previewObserver: ContentObserver? = null

    /**
     * Queries Android TV's WatchNextPrograms table (and falls back to PreviewPrograms if empty)
     * on a background thread using strictly null selection, selectionArgs, and sortOrder to avoid
     * TvProvider SecurityException on non-system signatures.
     * Filters browsable == 1 and sorts by lastEngagementTimeUtcMillis DESC in Kotlin memory.
     */
    fun queryWatchNextItemsAsync(onLoaded: (List<WatchNextItem>) -> Unit) {
        queryExecutor.execute {
            val result = queryWatchNextItemsSync()
            mainHandler.post {
                onLoaded(result)
            }
        }
    }

    private fun queryWatchNextItemsSync(): List<WatchNextItem> {
        val watchNextItems = queryUriSafely(
            uri = TvContractCompat.WatchNextPrograms.CONTENT_URI,
            isPreviewProgramFallback = false
        )
        if (watchNextItems.isNotEmpty()) {
            return watchNextItems
        }

        // Fallback: If WatchNextPrograms is empty on this TV, query PreviewPrograms
        // to pull active "Play Next / Resume" preview cards published by installed streaming apps
        return queryUriSafely(
            uri = TvContractCompat.PreviewPrograms.CONTENT_URI,
            isPreviewProgramFallback = true
        )
    }

    private fun queryUriSafely(
        uri: Uri,
        isPreviewProgramFallback: Boolean
    ): List<WatchNextItem> {
        val rawItems = mutableListOf<WatchNextItem>()
        try {
            // Pass projection = null, selection = null, selectionArgs = null, sortOrder = null
            // to prevent TvProvider.java SecurityException on non-system apps
            val cursor = context.contentResolver.query(
                uri,
                null,
                null,
                null,
                null
            ) ?: return emptyList()

            cursor.use { c ->
                val idIdx = c.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                val titleIdx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_TITLE)
                val epTitleIdx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_TITLE)
                val descIdx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION)
                val posterIdx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI)
                val thumbIdx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_THUMBNAIL_URI)
                val posIdx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
                val durIdx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_DURATION_MILLIS)
                val intentIdx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTENT_URI)
                val pkgIdx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_PACKAGE_NAME)
                val engIdx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS)
                val browsableIdx = c.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_BROWSABLE)
                val weightIdx = c.getColumnIndex(TvContractCompat.PreviewPrograms.COLUMN_WEIGHT)

                while (c.moveToNext()) {
                    // Filter out non-browsable items (browsable == 1)
                    if (browsableIdx >= 0 && !c.isNull(browsableIdx)) {
                        val browsable = c.getInt(browsableIdx)
                        if (browsable != 1) continue
                    }

                    val id = c.getLongOrNull(idIdx) ?: continue
                    val title = c.getStringOrNull(titleIdx) ?: continue

                    val episodeTitle = c.getStringOrNull(epTitleIdx)
                        ?: c.getStringOrNull(descIdx)

                    val posterUri = c.getStringOrNull(posterIdx)
                        ?: c.getStringOrNull(thumbIdx)

                    val positionMs = (c.getLongOrNull(posIdx) ?: 0L).coerceAtLeast(0L)
                    val durationMs = (c.getLongOrNull(durIdx) ?: 0L).coerceAtLeast(0L)
                    val progressPercent = if (durationMs > 0L && positionMs > 0L) {
                        ((positionMs.toFloat() / durationMs.toFloat()) * 100f).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }

                    val intentUri = c.getStringOrNull(intentIdx)
                    val packageName = c.getStringOrNull(pkgIdx)

                    val engagementTimeMs = c.getLongOrNull(engIdx)
                        ?: c.getLongOrNull(weightIdx)
                        ?: id

                    val parsedIntent = parseDeepLinkIntent(intentUri, packageName)

                    rawItems.add(
                        WatchNextItem(
                            id = id,
                            title = title,
                            episodeSubtitle = episodeTitle,
                            posterArtUri = posterUri,
                            lastPlaybackPositionMillis = positionMs,
                            durationMillis = durationMs,
                            lastEngagementTimeUtcMillis = engagementTimeMs,
                            progressPercent = progressPercent,
                            intentUri = intentUri,
                            packageName = packageName,
                            launchIntent = parsedIntent
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }

        // Deduplicate by title + packageName and sort in Kotlin memory
        val sorted = rawItems
            .distinctBy { "${it.packageName.orEmpty()}_${it.title}_${it.episodeSubtitle.orEmpty()}" }
            .sortedByDescending { it.lastEngagementTimeUtcMillis }

        return if (isPreviewProgramFallback) {
            // For PreviewPrograms fallback, prioritize items with playback progress or top 12 cards
            val withProgress = sorted.filter { it.progressPercent > 0 }
            if (withProgress.isNotEmpty()) withProgress.take(12) else sorted.take(12)
        } else {
            sorted
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? {
        if (index < 0 || isNull(index)) return null
        return try {
            getString(index)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun Cursor.getLongOrNull(index: Int): Long? {
        if (index < 0 || isNull(index)) return null
        return try {
            getLong(index)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDeepLinkIntent(intentUri: String?, packageName: String?): Intent? {
        if (!intentUri.isNullOrBlank()) {
            try {
                return Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (_: Exception) {
            }
            try {
                return Intent(Intent.ACTION_VIEW, Uri.parse(intentUri)).apply {
                    if (!packageName.isNullOrBlank()) {
                        setPackage(packageName)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (_: Exception) {
            }
        }
        if (!packageName.isNullOrBlank()) {
            val pm = context.packageManager
            return (pm.getLeanbackLaunchIntentForPackage(packageName)
                ?: pm.getLaunchIntentForPackage(packageName))?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return null
    }

    /**
     * Registers ContentObservers on WatchNextPrograms.CONTENT_URI and PreviewPrograms.CONTENT_URI.
     */
    fun registerObserver(onChanged: () -> Unit) {
        unregisterObserver()
        try {
            val wnObserver = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    onChanged()
                }
            }
            context.contentResolver.registerContentObserver(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                true,
                wnObserver
            )
            watchNextObserver = wnObserver
        } catch (_: Exception) {
        }

        try {
            val pvObserver = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    onChanged()
                }
            }
            context.contentResolver.registerContentObserver(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                true,
                pvObserver
            )
            previewObserver = pvObserver
        } catch (_: Exception) {
        }
    }

    /**
     * Unregisters ContentObservers when Continue Watching is toggled off or Activity stops.
     */
    fun unregisterObserver() {
        watchNextObserver?.let { obs ->
            try {
                context.contentResolver.unregisterContentObserver(obs)
            } catch (_: Exception) {
            }
        }
        watchNextObserver = null

        previewObserver?.let { obs ->
            try {
                context.contentResolver.unregisterContentObserver(obs)
            } catch (_: Exception) {
            }
        }
        previewObserver = null
    }
}
