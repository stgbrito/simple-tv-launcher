package com.appletv.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppEntry(
    val packageName: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable,
    val accentColor: Int,
    val isLeanback: Boolean,
    val isPinned: Boolean
)

class AppRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("apple_tv_launcher_prefs", Context.MODE_PRIVATE)
    private val colorCache = mutableMapOf<String, Int>()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val KEY_PINNED_PACKAGES = "pinned_dock_packages"
        private const val KEY_HIDDEN_PACKAGES = "hidden_packages"
        private const val KEY_INITIALIZED_DOCK = "dock_initialized_v1"
        private const val KEY_WALLPAPER_TYPE = "wallpaper_type"
        private const val KEY_CUSTOM_WALLPAPER_URL = "custom_wallpaper_url"
        private const val KEY_SHOW_CARD_LABELS = "show_card_labels"
        private const val KEY_AMBIENT_GLOW_ENABLED = "ambient_glow_enabled"
        private const val KEY_CLOCK_24H_FORMAT = "clock_24h_format"
        private const val KEY_CONTINUE_WATCHING_ENABLED = "pref_continue_watching_enabled"
        private const val KEY_HOME_LAYOUT_MODE = "pref_home_layout_mode"
        private const val CUSTOM_WALLPAPER_FILENAME = "custom_wallpaper.jpg"
        private const val MAX_DOCK_ITEMS = 6

        const val WALLPAPER_CHARCOAL = "charcoal"
        const val WALLPAPER_OLED_BLACK = "oled_black"
        const val WALLPAPER_CUSTOM = "custom"

        const val HOME_LAYOUT_CLASSIC = "classic"
        const val HOME_LAYOUT_SHOWCASE = "showcase"

        // Default favorite apps from TV_CONTEXT.md
        val DEFAULT_DOCK_PACKAGES = listOf(
            "com.plexapp.android",
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.stremio.one",
            "com.amazon.amazonvideo.livingroom",
            "org.jellyfin.androidtv"
        )

        // Curated vibrant ambient colors for primary streaming services
        private val KNOWN_BRAND_COLORS = mapOf(
            "com.netflix.ninja" to Color.parseColor("#E50914"),
            "com.google.android.youtube.tv" to Color.parseColor("#FF1744"),
            "com.google.android.youtube.tvmusic" to Color.parseColor("#FF1744"),
            "com.amazon.amazonvideo.livingroom" to Color.parseColor("#00A8E1"),
            "com.plexapp.android" to Color.parseColor("#E5A00D"),
            "com.stremio.one" to Color.parseColor("#7E57C2"),
            "org.jellyfin.androidtv" to Color.parseColor("#00A4DC"),
            "com.wbd.stream" to Color.parseColor("#2962FF"),
            "com.crunchyroll.crunchyroid" to Color.parseColor("#F47521"),
            "com.spotify.tv.android" to Color.parseColor("#1DB954"),
            "org.videolan.vlc" to Color.parseColor("#FF8800"),
            "com.google.android.videos" to Color.parseColor("#4285F4"),
            "com.android.vending" to Color.parseColor("#00C853"),
            "com.nordvpn.android" to Color.parseColor("#4687FF"),
            "com.neilturner.aerialviews" to Color.parseColor("#26C6DA"),
            "com.esaba.downloader" to Color.parseColor("#FF6D00")
        )

        private val EXCLUDED_SELF_PACKAGES = setOf(
            "com.appletv.launcher",
            "com.google.android.tvlauncher"
        )
    }

    init {
        if (!prefs.getBoolean(KEY_INITIALIZED_DOCK, false)) {
            prefs.edit()
                .putString(KEY_PINNED_PACKAGES, DEFAULT_DOCK_PACKAGES.joinToString(","))
                .putBoolean(KEY_INITIALIZED_DOCK, true)
                .apply()
        }
    }

    fun getWallpaperType(): String {
        return prefs.getString(KEY_WALLPAPER_TYPE, WALLPAPER_CHARCOAL) ?: WALLPAPER_CHARCOAL
    }

    fun setWallpaperType(type: String) {
        prefs.edit().putString(KEY_WALLPAPER_TYPE, type).apply()
    }

    fun getCustomWallpaperUrl(): String {
        return prefs.getString(KEY_CUSTOM_WALLPAPER_URL, "") ?: ""
    }

    fun getCustomWallpaperFile(): File {
        return File(context.filesDir, CUSTOM_WALLPAPER_FILENAME)
    }

    fun isShowCardLabels(): Boolean {
        return prefs.getBoolean(KEY_SHOW_CARD_LABELS, true)
    }

    fun setShowCardLabels(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_CARD_LABELS, show).apply()
    }

    fun isAmbientGlowEnabled(): Boolean {
        return prefs.getBoolean(KEY_AMBIENT_GLOW_ENABLED, true)
    }

    fun setAmbientGlowEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AMBIENT_GLOW_ENABLED, enabled).apply()
    }

    fun isClock24HourFormat(): Boolean {
        return prefs.getBoolean(KEY_CLOCK_24H_FORMAT, false)
    }

    fun setClock24HourFormat(is24Hour: Boolean) {
        prefs.edit().putBoolean(KEY_CLOCK_24H_FORMAT, is24Hour).apply()
    }

    fun isContinueWatchingEnabled(): Boolean {
        return prefs.getBoolean(KEY_CONTINUE_WATCHING_ENABLED, false)
    }

    fun setContinueWatchingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTINUE_WATCHING_ENABLED, enabled).apply()
    }

    fun getHomeLayoutMode(): String {
        return prefs.getString(KEY_HOME_LAYOUT_MODE, HOME_LAYOUT_SHOWCASE) ?: HOME_LAYOUT_SHOWCASE
    }

    fun setHomeLayoutMode(mode: String) {
        prefs.edit().putString(KEY_HOME_LAYOUT_MODE, mode).apply()
    }

    fun isShowcaseMode(): Boolean {
        return getHomeLayoutMode() == HOME_LAYOUT_SHOWCASE
    }

    fun getBrandAccentColor(packageName: String?): Int {
        if (packageName.isNullOrBlank()) return Color.parseColor("#0A84FF")
        return KNOWN_BRAND_COLORS[packageName] ?: colorCache[packageName] ?: Color.parseColor("#0A84FF")
    }

    fun clearMemoryCache() {
        colorCache.clear()
        System.gc()
    }

    /**
     * Ensures a Bitmap is strictly in software ARGB_8888 format (never Bitmap.Config.HARDWARE)
     * to prevent MediaTek Mali-G52 GPU SurfaceFlinger row stride corruption.
     */
    private fun ensureSoftwareArgb8888(bitmap: Bitmap): Bitmap {
        return if (bitmap.config == Bitmap.Config.HARDWARE || bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
    }

    /**
     * Scales and center-crops any Bitmap to exact 1920x1080 (ARGB_8888) so its row stride
     * is strictly 64-byte aligned (1920 * 4 = 7680 bytes = 120 * 64) for the TV's display compositor.
     */
    private fun scaleCenterCropTo1920x1080(src: Bitmap): Bitmap {
        val safeSrc = ensureSoftwareArgb8888(src)
        val targetW = 1920
        val targetH = 1080
        if (safeSrc.width == targetW && safeSrc.height == targetH && safeSrc.config == Bitmap.Config.ARGB_8888) {
            return safeSrc
        }

        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale: Float
        var dx = 0f
        var dy = 0f

        if (safeSrc.width * targetH > targetW * safeSrc.height) {
            scale = targetH.toFloat() / safeSrc.height.toFloat()
            dx = (targetW - safeSrc.width * scale) * 0.5f
        } else {
            scale = targetW.toFloat() / safeSrc.width.toFloat()
            dy = (targetH - safeSrc.height * scale) * 0.5f
        }

        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(safeSrc, matrix, paint)
        return out
    }

    /**
     * Loads the saved custom_wallpaper.jpg if present and valid, strictly as ARGB_8888 (1920x1080).
     */
    fun loadCustomWallpaperBitmap(): Bitmap? {
        return try {
            val file = getCustomWallpaperFile()
            if (!file.exists() || file.length() <= 0L) return null
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            scaleCenterCropTo1920x1080(decoded)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts Google Drive FILE_ID from any drive.google.com link.
     */
    fun extractGoogleDriveFileId(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (!trimmed.contains("drive.google.com", ignoreCase = true)) {
            return null
        }

        // Match /file/d/{ID} or /d/{ID}
        val pathRegex = Regex("""/(?:file/)?d/([a-zA-Z0-9_-]+)""")
        pathRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { id ->
            if (id.isNotBlank()) return id
        }

        // Match ?id={ID} or &id={ID}
        val queryRegex = Regex("""[?&]id=([a-zA-Z0-9_-]+)""")
        queryRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { id ->
            if (id.isNotBlank()) return id
        }

        return null
    }

    /**
     * Auto-parses Google Drive share links into Google's direct CDN endpoint:
     * https://lh3.googleusercontent.com/d/{FILE_ID}
     */
    fun resolveWallpaperDownloadUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val driveFileId = extractGoogleDriveFileId(trimmed)
        if (!driveFileId.isNullOrBlank()) {
            return "https://lh3.googleusercontent.com/d/$driveFileId"
        }
        return trimmed
    }

    /**
     * Downloads raw bytes from a URL while explicitly handling HTTP 301/302/303/307/308 redirects
     * (including cross-host redirects to Google's temporary media servers).
     */
    private fun fetchUrlBytesFollowingRedirects(initialUrl: String, maxRedirects: Int = 6): ByteArray {
        var currentUrl = initialUrl
        var redirects = 0

        while (redirects <= maxRedirects) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    connect()
                }

                val code = connection.responseCode
                if (code in listOf(
                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307,
                        308
                    )
                ) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) {
                        throw Exception("Redirect missing Location header (HTTP $code)")
                    }
                    currentUrl = URL(url, location).toString()
                    redirects++
                    continue
                }

                if (code !in 200..299) {
                    throw Exception("HTTP $code")
                }

                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) {
                    throw Exception("Empty response stream")
                }
                return bytes
            } finally {
                connection?.disconnect()
            }
        }
        throw Exception("Too many redirects")
    }

    private fun decodeWallpaperBytes(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            return null
        }

        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > 2560 || boundsOpts.outHeight / sampleSize > 1440) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        return scaleCenterCropTo1920x1080(rawBitmap)
    }

    /**
     * Asynchronously downloads an image from a direct URL or Google Drive share link,
     * trying https://lh3.googleusercontent.com/d/{FILE_ID} first and falling back to
     * https://drive.google.com/thumbnail?id={FILE_ID}&sz=w2560 if needed.
     * Saves to context.filesDir/custom_wallpaper.jpg and persists wallpaper_type = "custom".
     */
    fun downloadAndSaveCustomWallpaper(
        rawUrl: String,
        onResult: (success: Boolean, bitmap: Bitmap?, errorMessage: String?) -> Unit
    ) {
        val primaryUrl = resolveWallpaperDownloadUrl(rawUrl)
        if (primaryUrl.isBlank() || (!primaryUrl.startsWith("http://") && !primaryUrl.startsWith("https://"))) {
            onResult(false, null, "Please enter a valid http:// or https:// URL")
            return
        }

        val driveId = extractGoogleDriveFileId(rawUrl)
        val candidateUrls = mutableListOf(primaryUrl)
        if (!driveId.isNullOrBlank()) {
            candidateUrls.add("https://drive.google.com/thumbnail?id=$driveId&sz=w2560")
            candidateUrls.add("https://drive.google.com/uc?export=download&id=$driveId")
        }

        Thread {
            var decodedBitmap: Bitmap? = null
            var lastError: String? = null

            for (candidateUrl in candidateUrls.distinct()) {
                try {
                    val bytes = fetchUrlBytesFollowingRedirects(candidateUrl)
                    val bmp = decodeWallpaperBytes(bytes)
                    if (bmp != null) {
                        decodedBitmap = bmp
                        break
                    } else {
                        lastError = "URL did not return a valid image"
                    }
                } catch (e: Exception) {
                    lastError = e.localizedMessage ?: "Download failed"
                }
            }

            val finalBitmap = decodedBitmap
            if (finalBitmap == null) {
                mainHandler.post {
                    onResult(false, null, lastError ?: "Unable to download or decode wallpaper image")
                }
                return@Thread
            }

            try {
                val outFile = getCustomWallpaperFile()
                FileOutputStream(outFile).use { fos ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                    fos.flush()
                }

                prefs.edit()
                    .putString(KEY_WALLPAPER_TYPE, WALLPAPER_CUSTOM)
                    .putString(KEY_CUSTOM_WALLPAPER_URL, rawUrl.trim())
                    .apply()

                mainHandler.post {
                    onResult(true, finalBitmap, null)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    onResult(false, null, e.localizedMessage ?: "Failed to save wallpaper to storage")
                }
            }
        }.start()
    }

    fun getPinnedPackageOrder(): List<String> {
        val raw = prefs.getString(KEY_PINNED_PACKAGES, null) ?: return DEFAULT_DOCK_PACKAGES
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun savePinnedPackageOrder(packages: List<String>) {
        prefs.edit()
            .putString(KEY_PINNED_PACKAGES, packages.distinct().take(MAX_DOCK_ITEMS).joinToString(","))
            .apply()
    }

    fun togglePinToDock(packageName: String): Boolean {
        val current = getPinnedPackageOrder().toMutableList()
        return if (current.contains(packageName)) {
            current.remove(packageName)
            savePinnedPackageOrder(current)
            false
        } else {
            if (current.size >= MAX_DOCK_ITEMS) {
                current.removeAt(current.lastIndex)
            }
            current.add(packageName)
            savePinnedPackageOrder(current)
            true
        }
    }

    fun isPinned(packageName: String): Boolean {
        return getPinnedPackageOrder().contains(packageName)
    }

    fun getHiddenPackages(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN_PACKAGES, emptySet()) ?: emptySet()
    }

    fun hideApp(packageName: String) {
        val updated = getHiddenPackages().toMutableSet()
        updated.add(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN_PACKAGES, updated).apply()

        val pinned = getPinnedPackageOrder().toMutableList()
        if (pinned.remove(packageName)) {
            savePinnedPackageOrder(pinned)
        }
    }

    fun unhideApp(packageName: String) {
        val updated = getHiddenPackages().toMutableSet()
        if (updated.remove(packageName)) {
            prefs.edit().putStringSet(KEY_HIDDEN_PACKAGES, updated).apply()
        }
    }

    fun unhideAllApps() {
        prefs.edit().remove(KEY_HIDDEN_PACKAGES).apply()
    }

    private fun queryAllLaunchablePackages(): Map<String, Pair<ResolveInfo, Boolean>> {
        val pm = context.packageManager
        val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val standardIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val leanbackResolved = pm.queryIntentActivities(leanbackIntent, 0)
        val standardResolved = pm.queryIntentActivities(standardIntent, 0)

        val resolvedByPackage = linkedMapOf<String, Pair<ResolveInfo, Boolean>>()

        for (info in leanbackResolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg in EXCLUDED_SELF_PACKAGES) continue
            resolvedByPackage[pkg] = info to true
        }

        for (info in standardResolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg in EXCLUDED_SELF_PACKAGES) continue
            if (!resolvedByPackage.containsKey(pkg)) {
                resolvedByPackage[pkg] = info to false
            }
        }
        return resolvedByPackage
    }

    private fun sanitizeDrawableForGpu(drawable: Drawable?): Drawable? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) {
            val bmp = drawable.bitmap
            if (bmp != null && bmp.config == Bitmap.Config.HARDWARE) {
                return BitmapDrawable(
                    context.resources,
                    bmp.copy(Bitmap.Config.ARGB_8888, false)
                )
            }
        }
        return drawable
    }

    private fun buildAppEntry(
        pkg: String,
        resolveInfo: ResolveInfo,
        isLeanback: Boolean,
        isPinned: Boolean
    ): AppEntry? {
        val pm = context.packageManager
        val actInfo = resolveInfo.activityInfo ?: return null
        val appInfo = actInfo.applicationInfo

        val label = resolveInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() }
            ?: appInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() }
            ?: pkg

        val banner = sanitizeDrawableForGpu(loadBannerDrawable(pm, actInfo))
        val rawIcon = actInfo.loadIcon(pm) ?: appInfo.loadIcon(pm)
        val icon = sanitizeDrawableForGpu(rawIcon) ?: rawIcon
        val accentColor = colorCache.getOrPut(pkg) {
            KNOWN_BRAND_COLORS[pkg] ?: extractDominantVibrantColor(banner ?: icon)
        }

        return AppEntry(
            packageName = pkg,
            label = label,
            banner = banner,
            icon = icon,
            accentColor = accentColor,
            isLeanback = isLeanback,
            isPinned = isPinned
        )
    }

    fun loadApps(): Pair<List<AppEntry>, List<AppEntry>> {
        val hidden = getHiddenPackages()
        val pinnedOrder = getPinnedPackageOrder()
        val pinnedSet = pinnedOrder.toSet()
        val resolvedByPackage = queryAllLaunchablePackages()

        val allEntriesByPkg = mutableMapOf<String, AppEntry>()
        for ((pkg, pair) in resolvedByPackage) {
            if (pkg in hidden) continue
            val (resolveInfo, isLeanback) = pair
            val entry = buildAppEntry(pkg, resolveInfo, isLeanback, pkg in pinnedSet) ?: continue
            allEntriesByPkg[pkg] = entry
        }

        val dockApps = pinnedOrder.mapNotNull { allEntriesByPkg[it] }

        val gridApps = allEntriesByPkg.values.sortedWith(
            compareByDescending<AppEntry> { it.isLeanback }
                .thenBy { it.label.lowercase() }
        )

        return dockApps to gridApps
    }

    /**
     * Returns all currently hidden AppEntry items sorted alphabetically.
     */
    fun loadHiddenAppEntries(): List<AppEntry> {
        val hidden = getHiddenPackages()
        if (hidden.isEmpty()) return emptyList()
        val pinnedSet = getPinnedPackageOrder().toSet()
        val resolvedByPackage = queryAllLaunchablePackages()

        val list = mutableListOf<AppEntry>()
        for (pkg in hidden) {
            val pair = resolvedByPackage[pkg] ?: continue
            val entry = buildAppEntry(pkg, pair.first, pair.second, pkg in pinnedSet) ?: continue
            list.add(entry)
        }
        return list.sortedBy { it.label.lowercase() }
    }

    private fun loadBannerDrawable(pm: PackageManager, activityInfo: ActivityInfo): Drawable? {
        return try {
            activityInfo.loadBanner(pm) ?: activityInfo.applicationInfo?.loadBanner(pm)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractDominantVibrantColor(drawable: Drawable): Int {
        return try {
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                val srcBmp = ensureSoftwareArgb8888(drawable.bitmap)
                Bitmap.createScaledBitmap(srcBmp, 24, 24, false)
            } else {
                val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, 24, 24)
                drawable.draw(canvas)
                bmp
            }

            var bestColor = Color.parseColor("#3B82F6")
            var bestScore = -1f
            val hsv = FloatArray(3)

            for (y in 0 until bitmap.height step 2) {
                for (x in 0 until bitmap.width step 2) {
                    val pixel = bitmap.getPixel(x, y)
                    val alpha = Color.alpha(pixel)
                    if (alpha < 180) continue
                    Color.colorToHSV(pixel, hsv)
                    val saturation = hsv[1]
                    val value = hsv[2]
                    if (value in 0.25f..0.98f && saturation >= 0.22f) {
                        val score = saturation * 1.4f + value * 0.6f
                        if (score > bestScore) {
                            bestScore = score
                            hsv[2] = hsv[2].coerceAtLeast(0.72f)
                            bestColor = Color.HSVToColor(hsv)
                        }
                    }
                }
            }
            bestColor
        } catch (_: Exception) {
            Color.parseColor("#3B82F6")
        }
    }
}
