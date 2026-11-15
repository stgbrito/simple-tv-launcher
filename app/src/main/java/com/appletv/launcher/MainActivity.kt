package com.appletv.launcher

import android.app.Dialog
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import coil.Coil
import coil.ImageLoader
import com.appletv.launcher.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AppRepository
    private lateinit var watchNextRepository: WatchNextRepository
    private lateinit var watchNextAdapter: WatchNextAdapter
    private lateinit var dockAdapter: AppCardAdapter
    private lateinit var gridAdapter: AppCardAdapter

    enum class LauncherStage {
        DOCK,               // Stage 1: Dock at bottom, wallpaper showcase
        CONTINUE_WATCHING,  // Stage 2: Continue watching focused, grid peeking below
        ALL_APPS_FULL       // Stage 3: All apps grid full screen (3 full rows), CW slid off-screen
    }

    private var currentStage: LauncherStage = LauncherStage.DOCK
    private var lastFocusedWatchNextPosition: Int = 0
    private var lastFocusedWatchNextIndex: Int = 0
    private var lastFocusedDockIndex: Int = 0
    private var lastFocusedGridIndex: Int = 0
    private var initialFocusRequested: Boolean = false
    private val isGridExpanded: Boolean
        get() = currentStage != LauncherStage.DOCK

    // Apple's signature fluid ease curve: cubic-bezier(0.25, 0.1, 0.25, 1.0)
    private val appleFluidInterpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f)

    companion object {
        private const val GRID_SPAN_COUNT = 5
        private const val SLIDE_DURATION_MS = 350L
        private const val DOCK_MODE_GRID_ALPHA = 0.45f
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshApps(preserveFocus = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Globally disable hardware bitmaps in Coil to prevent MediaTek Mali-G52 GPU stride corruption
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                .allowHardware(false)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .build()
        )

        // Standard window flags only — strictly preserving Sony SystemUI overlays & Google Assistant
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository(this)
        watchNextRepository = WatchNextRepository(this)
        applySavedWallpaper()
        applyClockFormat()
        setupStatusCluster()
        setupRecyclerViews()
        gridAdapter.setShowCardLabels(repository.isShowCardLabels())
        binding.heroAmbientView.setAmbientGlowEnabled(repository.isAmbientGlowEnabled())
        syncContinueWatchingState()

        // Initialize resting positions once layout measures topHeroAndDockContainer
        binding.topHeroAndDockContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if ((bottom - top) != (oldBottom - oldTop) && !isGridExpanded) {
                applyStagePositions(inGrid = false, animate = false)
            }
        }

        binding.root.post {
            updateAppGridViewportHeight()
            applyStagePositions(inGrid = isGridExpanded, animate = false)
        }

        refreshApps(preserveFocus = false)
    }

    private fun applySavedWallpaper() {
        val type = repository.getWallpaperType()
        binding.heroAmbientView.setAmbientGlowEnabled(repository.isAmbientGlowEnabled())
        if (type == AppRepository.WALLPAPER_CUSTOM) {
            val bitmap = repository.loadCustomWallpaperBitmap()
            if (bitmap != null) {
                binding.heroAmbientView.setWallpaperMode(AppRepository.WALLPAPER_CUSTOM, bitmap)
            } else {
                // Graceful fallback to tvOS Charcoal Gradient if custom file is missing/corrupt
                binding.heroAmbientView.setWallpaperMode(AppRepository.WALLPAPER_CHARCOAL, null)
            }
        } else {
            binding.heroAmbientView.setWallpaperMode(type, null)
        }
    }

    private fun applyClockFormat() {
        val is24h = repository.isClock24HourFormat()
        val pattern = if (is24h) "HH:mm" else "h:mm a"
        binding.textClock.format12Hour = pattern
        binding.textClock.format24Hour = pattern
    }

    /**
     * Enforces the optional "Continue Watching Shelf" preference:
     * - When OFF (default): Unregisters ContentObserver, performs zero TV provider queries,
     *   clears adapter, and sets rvContinueWatching.visibility = View.GONE.
     * - When ON: Registers ContentObserver on WatchNextPrograms.CONTENT_URI and loads items;
     *   shows rvContinueWatching only if items.isNotEmpty().
     */
    private fun syncContinueWatchingState(notifyIfEmpty: Boolean = false) {
        if (!repository.isContinueWatchingEnabled()) {
            watchNextRepository.unregisterObserver()
            watchNextAdapter.submitList(emptyList())
            binding.rvContinueWatching.visibility = View.GONE
            binding.root.post {
                if (!isGridExpanded) {
                    applyStagePositions(inGrid = false, animate = false)
                }
            }
            return
        }

        watchNextRepository.registerObserver {
            loadContinueWatchingItems(notifyIfEmpty = false)
        }
        loadContinueWatchingItems(notifyIfEmpty = notifyIfEmpty)
    }

    private fun loadContinueWatchingItems(notifyIfEmpty: Boolean = false) {
        if (!repository.isContinueWatchingEnabled()) {
            binding.rvContinueWatching.visibility = View.GONE
            return
        }
        watchNextRepository.queryWatchNextItemsAsync { items ->
            if (isFinishing || isDestroyed || !repository.isContinueWatchingEnabled()) {
                binding.rvContinueWatching.visibility = View.GONE
                return@queryWatchNextItemsAsync
            }
            watchNextAdapter.submitList(items)
            val shouldShow = items.isNotEmpty()
            val visibilityChanged = (binding.rvContinueWatching.visibility == View.VISIBLE) != shouldShow
            binding.rvContinueWatching.visibility = if (shouldShow) View.VISIBLE else View.GONE
            if (notifyIfEmpty && items.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "Continue Watching enabled — play a video in Plex/Stremio/YouTube to populate",
                    Toast.LENGTH_LONG
                ).show()
            }
            if (visibilityChanged) {
                binding.root.post {
                    transitionToStage(currentStage, animate = true)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageChangeReceiver, filter)
        if (repository.isContinueWatchingEnabled()) {
            syncContinueWatchingState()
        }
    }

    override fun onStop() {
        super.onStop()
        watchNextRepository.unregisterObserver()
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (_: Exception) {
        }
    }

    /**
     * Calculates the resting downward offset of gridSectionContainer when the Dock is visible.
     * In Classic Mode: positions grid cleanly below the top hero stage and floating dock.
     * In Showcase Mode: pushes gridSectionContainer completely below the screen (screenHeightPx)
     * so zero grid tiles peek through and the custom wallpaper remains 100% visible.
     */
    private fun getGridRestingOffsetPx(): Float {
        val density = resources.displayMetrics.density
        val screenHeightPx = resources.displayMetrics.heightPixels.toFloat()
        if (repository.isShowcaseMode()) {
            return screenHeightPx
        }
        val stickyBarHeightPx = 80f * density
        val dockBottomPx = binding.topHeroAndDockContainer.height.takeIf { it > 0 }?.toFloat()
            ?: (284f * density)
        val extraClearancePx = 22f * density
        return (dockBottomPx - stickyBarHeightPx + extraClearancePx).coerceAtLeast(212f * density)
    }

    /**
     * Calculates the resting translationY for topHeroAndDockContainer.
     * In Showcase Mode: moves hero title + dock down near the bottom of the screen (approx 56dp from bottom).
     * In Classic Mode: rests at translationY = 0f.
     */
    private fun getHeroRestingYPx(): Float {
        if (!repository.isShowcaseMode()) return 0f
        val density = resources.displayMetrics.density
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        val containerH = binding.topHeroAndDockContainer.height.takeIf { it > 0 }?.toFloat()
            ?: (320f * density)
        val bottomMarginPx = 56f * density
        return (screenH - containerH - bottomMarginPx).coerceAtLeast(0f)
    }

    /**
     * Calculates the measured pixel offset needed to slide Continue Watching + Header
     * completely out of the viewport so rvAppGrid expands to full screen (Stage 3).
     */
    private fun getContinueWatchingOffsetPx(): Float {
        if (!isContinueWatchingShelfVisible()) return 0f
        val appGridTop = binding.rvAppGrid.top.toFloat()
        if (appGridTop > 0f) return appGridTop

        val density = resources.displayMetrics.density
        val cwHeight = binding.rvContinueWatching.height.takeIf { it > 0 }?.toFloat() ?: (147f * density)
        val cwMarginBottom = 12f * density
        val headerHeight = 24f * density
        return cwHeight + cwMarginBottom + headerHeight
    }

    /**
     * Ensures rvAppGrid height dynamically matches the full viewport below topStickyBar (460dp on 1080p).
     */
    private fun updateAppGridViewportHeight() {
        binding.root.post {
            val density = resources.displayMetrics.density
            val stickyBarH = binding.topStickyBar.height.takeIf { it > 0 } ?: (80f * density).toInt()
            val screenH = resources.displayMetrics.heightPixels
            val viewportHeightPx = screenH - stickyBarH
            if (viewportHeightPx > 0 && binding.rvAppGrid.layoutParams.height != viewportHeightPx) {
                val lp = binding.rvAppGrid.layoutParams
                lp.height = viewportHeightPx
                binding.rvAppGrid.layoutParams = lp
            }
        }
    }

    /**
     * 3-Stage Vertical Glide using Apple's 350ms PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f):
     * - Stage 1 (DOCK): Wallpaper Showcase — Full wallpaper visible, Floating Dock at bottom,
     *   Continue Watching & All Apps hidden below screen fold, subtle scrim (0.15f).
     * - Stage 2 (CONTINUE_WATCHING): Slide Dock off-screen, Continue Watching focused right
     *   below topStickyBar, top rows of All Apps visible below, full scrim (1.0f).
     * - Stage 3 (ALL_APPS_FULL): Slide Continue Watching out of viewport (-continueWatchingOffsetPx),
     *   rvAppGrid expands to fill FULL screen below 80dp sticky bar showing 3 FULL ROWS of app cards.
     */
    private fun transitionToStage(stage: LauncherStage, animate: Boolean) {
        currentStage = stage
        val density = resources.displayMetrics.density
        val isShowcase = repository.isShowcaseMode()

        val heroRestingY = getHeroRestingYPx()
        val heroHeightPx = binding.topHeroAndDockContainer.height.takeIf { it > 0 }?.toFloat()
            ?: (340f * density)

        val heroTargetY: Float
        val heroTargetAlpha: Float
        val heroTargetScale: Float
        val gridTargetY: Float
        val gridTargetAlpha: Float
        val cwTargetAlpha: Float
        val gridSectionLabelAlpha: Float
        val compactHeaderAlpha: Float
        val targetScrimAlpha: Float

        when (stage) {
            LauncherStage.DOCK -> {
                heroTargetY = heroRestingY
                heroTargetAlpha = 1.0f
                heroTargetScale = 1.0f

                gridTargetY = getGridRestingOffsetPx()
                gridTargetAlpha = if (isShowcase) 0.0f else DOCK_MODE_GRID_ALPHA
                cwTargetAlpha = 1.0f
                gridSectionLabelAlpha = 1.0f
                compactHeaderAlpha = 0.0f
                targetScrimAlpha = if (isShowcase) 0.15f else 1.0f
            }
            LauncherStage.CONTINUE_WATCHING -> {
                heroTargetY = -heroHeightPx.coerceAtLeast(340f * density)
                heroTargetAlpha = 0.0f
                heroTargetScale = 0.96f

                gridTargetY = 0f
                gridTargetAlpha = 1.0f
                cwTargetAlpha = 1.0f
                gridSectionLabelAlpha = 1.0f
                compactHeaderAlpha = 1.0f
                targetScrimAlpha = 1.0f
            }
            LauncherStage.ALL_APPS_FULL -> {
                heroTargetY = -heroHeightPx.coerceAtLeast(340f * density)
                heroTargetAlpha = 0.0f
                heroTargetScale = 0.96f

                gridTargetY = -getContinueWatchingOffsetPx()
                gridTargetAlpha = 1.0f
                cwTargetAlpha = 0.0f
                gridSectionLabelAlpha = 0.0f
                compactHeaderAlpha = 1.0f
                targetScrimAlpha = 1.0f
            }
        }

        binding.topHeroAndDockContainer.animate().cancel()
        binding.gridSectionContainer.animate().cancel()
        binding.rvContinueWatching.animate().cancel()
        binding.tvGridHeader.animate().cancel()
        binding.gridTopHeader.animate().cancel()

        binding.heroAmbientView.setCustomScrimAlpha(targetScrimAlpha)

        if (animate) {
            binding.topHeroAndDockContainer.animate()
                .translationY(heroTargetY)
                .alpha(heroTargetAlpha)
                .scaleX(heroTargetScale)
                .scaleY(heroTargetScale)
                .setDuration(SLIDE_DURATION_MS)
                .setInterpolator(appleFluidInterpolator)
                .start()

            binding.gridSectionContainer.animate()
                .translationY(gridTargetY)
                .alpha(gridTargetAlpha)
                .setDuration(SLIDE_DURATION_MS)
                .setInterpolator(appleFluidInterpolator)
                .start()

            binding.rvContinueWatching.animate()
                .alpha(cwTargetAlpha)
                .setDuration(SLIDE_DURATION_MS)
                .setInterpolator(appleFluidInterpolator)
                .start()

            binding.tvGridHeader.animate()
                .alpha(gridSectionLabelAlpha)
                .setDuration(SLIDE_DURATION_MS)
                .setInterpolator(appleFluidInterpolator)
                .start()

            binding.gridTopHeader.animate()
                .alpha(compactHeaderAlpha)
                .setDuration(SLIDE_DURATION_MS)
                .setInterpolator(appleFluidInterpolator)
                .start()
        } else {
            binding.topHeroAndDockContainer.translationY = heroTargetY
            binding.topHeroAndDockContainer.alpha = heroTargetAlpha
            binding.topHeroAndDockContainer.scaleX = heroTargetScale
            binding.topHeroAndDockContainer.scaleY = heroTargetScale

            binding.gridSectionContainer.translationY = gridTargetY
            binding.gridSectionContainer.alpha = gridTargetAlpha
            binding.rvContinueWatching.alpha = cwTargetAlpha
            binding.tvGridHeader.alpha = gridSectionLabelAlpha
            binding.gridTopHeader.alpha = compactHeaderAlpha
        }
    }

    private fun applyStagePositions(inGrid: Boolean, animate: Boolean) {
        val target = if (inGrid) {
            if (binding.rvAppGrid.hasFocus()) LauncherStage.ALL_APPS_FULL
            else if (isContinueWatchingShelfVisible()) LauncherStage.CONTINUE_WATCHING
            else LauncherStage.ALL_APPS_FULL
        } else {
            LauncherStage.DOCK
        }
        transitionToStage(target, animate)
    }

    private fun setupStatusCluster() {
        binding.btnInputs.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.08f else 1.0f)
                .scaleY(if (hasFocus) 1.08f else 1.0f)
                .translationZ(if (hasFocus) 12f else 0f)
                .setDuration(150L)
                .setInterpolator(appleFluidInterpolator)
                .start()
            if (hasFocus) {
                if (isGridExpanded) {
                    applyStagePositions(inGrid = false, animate = true)
                }
                binding.tvHeroBadge.text = getString(R.string.section_system_badge)
                binding.tvHeroTitle.text = getString(R.string.btn_inputs)
                binding.tvHeroSubtitle.text = "Switch HDMI, Bravia Sync & TV Tuner Sources"
                binding.heroAmbientView.animateToColor(Color.parseColor("#0A84FF"))
            }
        }

        binding.btnSettings.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.08f else 1.0f)
                .scaleY(if (hasFocus) 1.08f else 1.0f)
                .translationZ(if (hasFocus) 12f else 0f)
                .setDuration(150L)
                .setInterpolator(appleFluidInterpolator)
                .start()
            if (hasFocus) {
                if (isGridExpanded) {
                    applyStagePositions(inGrid = false, animate = true)
                }
                binding.tvHeroBadge.text = getString(R.string.section_system_badge)
                binding.tvHeroTitle.text = getString(R.string.btn_settings)
                binding.tvHeroSubtitle.text = "Wallpaper, Themes & Sony Android TV Settings"
                binding.heroAmbientView.animateToColor(Color.parseColor("#5E5CE6"))
            }
        }

        binding.btnInputs.setOnClickListener {
            openSonyInputSelector()
        }

        binding.btnSettings.setOnClickListener {
            showLauncherSettingsDialog()
        }

        binding.btnSettings.setOnLongClickListener {
            openAndroidTvSettings()
            true
        }
    }

    private fun setupRecyclerViews() {
        watchNextAdapter = WatchNextAdapter(
            onItemFocused = { item, pos ->
                lastFocusedWatchNextPosition = pos
                lastFocusedWatchNextIndex = pos
                if (currentStage != LauncherStage.CONTINUE_WATCHING) {
                    transitionToStage(LauncherStage.CONTINUE_WATCHING, animate = true)
                }
                updateHeroStageForWatchNext(item)
            },
            onItemClicked = { item ->
                launchWatchNextItem(item)
            }
        )

        dockAdapter = AppCardAdapter(
            isDock = true,
            onAppFocused = { entry, _, pos ->
                lastFocusedDockIndex = pos
                if (currentStage != LauncherStage.DOCK) {
                    binding.rvAppGrid.scrollToPosition(0)
                    transitionToStage(LauncherStage.DOCK, animate = true)
                }
                updateHeroStage(entry, isDock = true)
            },
            onAppClicked = { entry -> launchApp(entry.packageName) },
            onAppLongClicked = { entry -> showAppOptionsDialog(entry) }
        )

        gridAdapter = AppCardAdapter(
            isDock = false,
            onAppFocused = { entry, _, pos ->
                lastFocusedGridIndex = pos
                if (currentStage != LauncherStage.ALL_APPS_FULL) {
                    transitionToStage(LauncherStage.ALL_APPS_FULL, animate = true)
                }
                updateHeroStage(entry, isDock = false)
            },
            onAppClicked = { entry -> launchApp(entry.packageName) },
            onAppLongClicked = { entry -> showAppOptionsDialog(entry) },
            onAppDpadUp = { pos ->
                if (pos < GRID_SPAN_COUNT) {
                    if (isContinueWatchingShelfVisible()) {
                        transitionToStage(LauncherStage.CONTINUE_WATCHING, animate = true)
                        val targetPos = lastFocusedWatchNextPosition.coerceIn(0, watchNextAdapter.itemCount - 1)
                        binding.rvContinueWatching.scrollToPosition(targetPos)
                        binding.rvContinueWatching.post {
                            binding.rvContinueWatching.findViewHolderForAdapterPosition(targetPos)?.itemView?.requestFocus()
                        }
                        true
                    } else if (dockAdapter.itemCount > 0) {
                        transitionToStage(LauncherStage.DOCK, animate = true)
                        focusDockAt(lastFocusedDockIndex)
                        true
                    } else {
                        transitionToStage(LauncherStage.DOCK, animate = true)
                        binding.btnInputs.requestFocus()
                        true
                    }
                } else {
                    false
                }
            }
        )

        binding.rvContinueWatching.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = watchNextAdapter
            itemAnimator = null
        }

        binding.rvDock.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = dockAdapter
            itemAnimator = null
        }

        binding.rvAppGrid.apply {
            layoutManager = GridLayoutManager(this@MainActivity, GRID_SPAN_COUNT)
            adapter = gridAdapter
            itemAnimator = null
        }
    }

    private fun refreshApps(preserveFocus: Boolean) {
        val wasInGrid = binding.rvAppGrid.hasFocus()
        val (dockApps, gridApps) = repository.loadApps()

        dockAdapter.submitList(dockApps)
        gridAdapter.submitList(gridApps)

        binding.dockGlassContainer.visibility = if (dockApps.isEmpty()) View.GONE else View.VISIBLE

        if (!initialFocusRequested || !preserveFocus) {
            initialFocusRequested = true
            binding.root.post {
                updateAppGridViewportHeight()
                if (dockApps.isNotEmpty()) {
                    transitionToStage(LauncherStage.DOCK, animate = false)
                    focusDockAt(0)
                } else if (gridApps.isNotEmpty()) {
                    transitionToStage(LauncherStage.ALL_APPS_FULL, animate = false)
                    focusGridAt(0)
                }
            }
        } else {
            binding.root.post {
                updateAppGridViewportHeight()
                if (wasInGrid && gridApps.isNotEmpty()) {
                    transitionToStage(LauncherStage.ALL_APPS_FULL, animate = false)
                    focusGridAt(lastFocusedGridIndex.coerceIn(0, gridApps.lastIndex))
                } else if (dockApps.isNotEmpty()) {
                    transitionToStage(LauncherStage.DOCK, animate = false)
                    focusDockAt(lastFocusedDockIndex.coerceIn(0, dockApps.lastIndex))
                } else if (gridApps.isNotEmpty()) {
                    transitionToStage(LauncherStage.ALL_APPS_FULL, animate = false)
                    focusGridAt(0)
                }
            }
        }
    }

    private fun updateHeroStage(entry: AppEntry, isDock: Boolean) {
        binding.tvHeroBadge.text = if (isDock) {
            getString(R.string.section_dock_badge)
        } else {
            getString(R.string.section_grid_badge)
        }
        binding.tvGridModeBadge.text = getString(R.string.section_grid_badge)
        binding.tvHeroTitle.text = entry.label
        binding.tvHeroSubtitle.text = getString(R.string.hero_default_subtitle)
        binding.tvGridModeTitle.text = entry.label
        binding.heroAmbientView.animateToColor(entry.accentColor)
    }

    private fun updateHeroStageForWatchNext(item: WatchNextItem) {
        binding.tvHeroBadge.text = "UP NEXT  •  CONTINUE WATCHING"
        binding.tvGridModeBadge.text = "UP NEXT"
        binding.tvHeroTitle.text = item.title
        val subtitleParts = mutableListOf<String>()
        if (!item.episodeSubtitle.isNullOrBlank()) {
            subtitleParts.add(item.episodeSubtitle)
        }
        if (item.progressPercent > 0) {
            subtitleParts.add("${item.progressPercent}% watched")
        }
        if (subtitleParts.isEmpty()) {
            subtitleParts.add("Press Select to resume playback")
        }
        binding.tvHeroSubtitle.text = subtitleParts.joinToString("  •  ")
        binding.tvGridModeTitle.text = item.title
        binding.heroAmbientView.animateToColor(repository.getBrandAccentColor(item.packageName))
    }

    private fun launchWatchNextItem(item: WatchNextItem) {
        val deepLink = item.launchIntent
        if (deepLink != null) {
            try {
                startActivity(deepLink)
                return
            } catch (_: Exception) {
            }
        }
        if (!item.packageName.isNullOrBlank()) {
            launchApp(item.packageName)
        }
    }

    private fun isContinueWatchingShelfVisible(): Boolean {
        return binding.rvContinueWatching.visibility == View.VISIBLE && watchNextAdapter.itemCount > 0
    }

    private fun focusWatchNextAt(index: Int): Boolean {
        val count = watchNextAdapter.itemCount
        if (count == 0 || binding.rvContinueWatching.visibility != View.VISIBLE) return false
        val target = index.coerceIn(0, count - 1)
        lastFocusedWatchNextPosition = target
        lastFocusedWatchNextIndex = target
        val holder = binding.rvContinueWatching.findViewHolderForAdapterPosition(target)
        return if (holder != null) {
            holder.itemView.requestFocus()
        } else {
            binding.rvContinueWatching.scrollToPosition(target)
            binding.rvContinueWatching.post {
                binding.rvContinueWatching.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            }
            true
        }
    }

    private fun focusDockAt(index: Int): Boolean {
        val count = dockAdapter.itemCount
        if (count == 0) return false
        val target = index.coerceIn(0, count - 1)
        val holder = binding.rvDock.findViewHolderForAdapterPosition(target)
        return if (holder != null) {
            holder.itemView.requestFocus()
        } else {
            binding.rvDock.scrollToPosition(target)
            binding.rvDock.post {
                binding.rvDock.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            }
            true
        }
    }

    private fun focusGridAt(index: Int): Boolean {
        val count = gridAdapter.itemCount
        if (count == 0) return false
        val target = index.coerceIn(0, count - 1)
        val holder = binding.rvAppGrid.findViewHolderForAdapterPosition(target)
        return if (holder != null) {
            holder.itemView.requestFocus()
        } else {
            binding.rvAppGrid.scrollToPosition(target)
            binding.rvAppGrid.post {
                binding.rvAppGrid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            }
            true
        }
    }

    /**
     * Strict key handling:
     * Handles ONLY D-pad navigation, Center/Enter, and Back (consumes Back at root level).
     * Passes all Sony remote keys (KEYCODE_VOICE_ASSIST, KEYCODE_SEARCH, KEYCODE_SETTINGS,
     * KEYCODE_TV_INPUT, KEYCODE_MENU, volume keys, etc.) straight to super.onKeyDown().
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // Consume BACK on root home screen so the launcher never closes;
                // Graceful tvOS hierarchical back navigation:
                if (currentStage == LauncherStage.ALL_APPS_FULL) {
                    if (isContinueWatchingShelfVisible()) {
                        transitionToStage(LauncherStage.CONTINUE_WATCHING, animate = true)
                        focusWatchNextAt(lastFocusedWatchNextPosition)
                        return true
                    } else if (dockAdapter.itemCount > 0) {
                        binding.rvAppGrid.scrollToPosition(0)
                        transitionToStage(LauncherStage.DOCK, animate = true)
                        focusDockAt(lastFocusedDockIndex)
                        return true
                    }
                    return true
                } else if (currentStage == LauncherStage.CONTINUE_WATCHING) {
                    if (dockAdapter.itemCount > 0) {
                        transitionToStage(LauncherStage.DOCK, animate = true)
                        focusDockAt(lastFocusedDockIndex)
                        return true
                    }
                    return true
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (handleDpadUp()) true else super.onKeyDown(keyCode, event)
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (handleDpadDown()) true else super.onKeyDown(keyCode, event)
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (handleDpadLeft()) true else super.onKeyDown(keyCode, event)
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (handleDpadRight()) true else super.onKeyDown(keyCode, event)
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                super.onKeyDown(keyCode, event)
            }

            else -> {
                // Strictly forward KEYCODE_VOICE_ASSIST, KEYCODE_SEARCH, KEYCODE_SETTINGS,
                // KEYCODE_TV_INPUT, KEYCODE_MENU, and all system/volume keys to Android/Sony OS
                super.onKeyDown(keyCode, event)
            }
        }
    }

    private fun handleDpadUp(): Boolean {
        if (binding.rvContinueWatching.hasFocus()) {
            // From Continue Watching shelf, glide back down to Stage 1 (Dock Mode)
            transitionToStage(LauncherStage.DOCK, animate = true)
            return if (dockAdapter.itemCount > 0) {
                focusDockAt(lastFocusedDockIndex)
            } else {
                binding.btnInputs.requestFocus()
            }
        }
        if (binding.rvDock.hasFocus()) {
            // From Dock, move up to Top Sticky Bar (btnInputs)
            return binding.btnInputs.requestFocus()
        }
        if (binding.rvAppGrid.hasFocus()) {
            if (lastFocusedGridIndex < GRID_SPAN_COUNT) {
                binding.rvAppGrid.scrollToPosition(0)
                if (isContinueWatchingShelfVisible()) {
                    transitionToStage(LauncherStage.CONTINUE_WATCHING, animate = true)
                    val targetPos = lastFocusedWatchNextPosition.coerceIn(0, watchNextAdapter.itemCount - 1)
                    binding.rvContinueWatching.scrollToPosition(targetPos)
                    binding.rvContinueWatching.post {
                        binding.rvContinueWatching.findViewHolderForAdapterPosition(targetPos)?.itemView?.requestFocus()
                    }
                    return true
                } else if (dockAdapter.itemCount > 0) {
                    transitionToStage(LauncherStage.DOCK, animate = true)
                    return focusDockAt(lastFocusedDockIndex)
                } else {
                    transitionToStage(LauncherStage.DOCK, animate = true)
                    return binding.btnInputs.requestFocus()
                }
            }
        }
        return false
    }

    private fun handleDpadDown(): Boolean {
        if (binding.btnInputs.isFocused || binding.btnSettings.isFocused) {
            return if (dockAdapter.itemCount > 0) {
                transitionToStage(LauncherStage.DOCK, animate = true)
                focusDockAt(lastFocusedDockIndex)
            } else if (isContinueWatchingShelfVisible()) {
                transitionToStage(LauncherStage.CONTINUE_WATCHING, animate = true)
                focusWatchNextAt(lastFocusedWatchNextPosition)
            } else {
                transitionToStage(LauncherStage.ALL_APPS_FULL, animate = true)
                focusGridAt(lastFocusedGridIndex)
            }
        }
        if (binding.rvDock.hasFocus()) {
            return if (isContinueWatchingShelfVisible()) {
                transitionToStage(LauncherStage.CONTINUE_WATCHING, animate = true)
                focusWatchNextAt(lastFocusedWatchNextPosition)
            } else if (gridAdapter.itemCount > 0) {
                transitionToStage(LauncherStage.ALL_APPS_FULL, animate = true)
                val targetCol = ((lastFocusedDockIndex.toFloat() / dockAdapter.itemCount.coerceAtLeast(1)) * GRID_SPAN_COUNT)
                    .toInt()
                    .coerceIn(0, GRID_SPAN_COUNT - 1)
                    .coerceAtMost(gridAdapter.itemCount - 1)
                focusGridAt(targetCol)
            } else {
                true
            }
        }
        if (binding.rvContinueWatching.hasFocus()) {
            if (gridAdapter.itemCount > 0) {
                transitionToStage(LauncherStage.ALL_APPS_FULL, animate = true)
                val targetCol = lastFocusedGridIndex.coerceIn(0, (GRID_SPAN_COUNT - 1).coerceAtMost(gridAdapter.itemCount - 1))
                return focusGridAt(targetCol)
            }
            return true
        }
        return false
    }

    private fun handleDpadLeft(): Boolean {
        if (binding.rvContinueWatching.hasFocus() && lastFocusedWatchNextIndex == 0) {
            return true // Clamp left edge of Continue Watching shelf
        }
        if (binding.rvDock.hasFocus() && lastFocusedDockIndex == 0) {
            return true // Clamp left edge of floating dock
        }
        if (binding.rvAppGrid.hasFocus() && lastFocusedGridIndex % GRID_SPAN_COUNT == 0) {
            return true // Clamp left edge of app grid
        }
        return false
    }

    private fun handleDpadRight(): Boolean {
        if (binding.rvContinueWatching.hasFocus() && lastFocusedWatchNextIndex >= watchNextAdapter.itemCount - 1) {
            return true // Clamp right edge of Continue Watching shelf
        }
        if (binding.rvDock.hasFocus() && lastFocusedDockIndex >= dockAdapter.itemCount - 1) {
            return true // Clamp right edge of floating dock
        }
        if (binding.rvAppGrid.hasFocus()) {
            val isRightmostCol = (lastFocusedGridIndex % GRID_SPAN_COUNT) == (GRID_SPAN_COUNT - 1)
            val isLastItem = lastFocusedGridIndex >= gridAdapter.itemCount - 1
            if (isRightmostCol || isLastItem) {
                return true // Clamp right edge of app grid
            }
        }
        return false
    }

    private fun showAppOptionsDialog(entry: AppEntry) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_app_options)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val ivDialogIcon = dialog.findViewById<ImageView>(R.id.ivDialogIcon)
        val tvDialogTitle = dialog.findViewById<TextView>(R.id.tvDialogTitle)
        val tvDialogPkg = dialog.findViewById<TextView>(R.id.tvDialogPkg)
        val btnTogglePin = dialog.findViewById<TextView>(R.id.btnTogglePin)
        val btnAppInfo = dialog.findViewById<TextView>(R.id.btnAppInfo)
        val btnHideApp = dialog.findViewById<TextView>(R.id.btnHideApp)
        val btnWallpaperSettings = dialog.findViewById<TextView>(R.id.btnWallpaperSettings)
        val btnUnhideAll = dialog.findViewById<TextView>(R.id.btnUnhideAll)

        ivDialogIcon.setImageDrawable(entry.icon)
        tvDialogTitle.text = entry.label
        tvDialogPkg.text = entry.packageName

        val currentlyPinned = repository.isPinned(entry.packageName)
        btnTogglePin.text = getString(
            if (currentlyPinned) R.string.action_unpin_dock else R.string.action_pin_dock
        )

        btnTogglePin.setOnClickListener {
            repository.togglePinToDock(entry.packageName)
            dialog.dismiss()
            refreshApps(preserveFocus = true)
        }

        btnAppInfo.setOnClickListener {
            dialog.dismiss()
            openAppDetailsSettings(entry.packageName)
        }

        btnHideApp.setOnClickListener {
            repository.hideApp(entry.packageName)
            dialog.dismiss()
            refreshApps(preserveFocus = true)
        }

        btnWallpaperSettings.setOnClickListener {
            dialog.dismiss()
            showWallpaperSettingsDialog()
        }

        if (repository.getHiddenPackages().isNotEmpty()) {
            btnUnhideAll.visibility = View.VISIBLE
            btnUnhideAll.setOnClickListener {
                repository.unhideAllApps()
                dialog.dismiss()
                refreshApps(preserveFocus = true)
            }
        } else {
            btnUnhideAll.visibility = View.GONE
        }

        dialog.show()
        btnTogglePin.requestFocus()
    }

    private fun showLauncherSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_launcher_settings)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnEditDock = dialog.findViewById<TextView>(R.id.btnEditDock)
        val btnHiddenApps = dialog.findViewById<TextView>(R.id.btnHiddenApps)
        val btnOpenWallpaper = dialog.findViewById<TextView>(R.id.btnOpenWallpaper)
        val btnToggleCardLabels = dialog.findViewById<TextView>(R.id.btnToggleCardLabels)
        val btnToggleAmbientGlow = dialog.findViewById<TextView>(R.id.btnToggleAmbientGlow)
        val btnToggleContinueWatching = dialog.findViewById<TextView>(R.id.btnToggleContinueWatching)
        val btnToggleHomeLayout = dialog.findViewById<TextView>(R.id.btnToggleHomeLayout)
        val btnToggleClockFormat = dialog.findViewById<TextView>(R.id.btnToggleClockFormat)
        val btnTrimCache = dialog.findViewById<TextView>(R.id.btnTrimCache)
        val btnSleepTv = dialog.findViewById<TextView>(R.id.btnSleepTv)
        val btnRestartTv = dialog.findViewById<TextView>(R.id.btnRestartTv)
        val btnOpenSystemSettings = dialog.findViewById<TextView>(R.id.btnOpenSystemSettings)

        fun refreshLabels() {
            val hiddenCount = repository.getHiddenPackages().size
            btnHiddenApps.text = if (hiddenCount > 0) "Hidden Apps ($hiddenCount)" else "Hidden Apps"
            btnToggleCardLabels.text = "Card Text Labels: " + if (repository.isShowCardLabels()) "Show" else "Hide"
            btnToggleAmbientGlow.text = "Ambient Glow: " + if (repository.isAmbientGlowEnabled()) "Enabled" else "Disabled"
            btnToggleContinueWatching.text = "Continue Watching Shelf: " + if (repository.isContinueWatchingEnabled()) "Enabled" else "Disabled"
            btnToggleHomeLayout.text = "Home Layout: " + if (repository.isShowcaseMode()) "Wallpaper Showcase (Bottom Dock)" else "Classic Top Stage"
            btnToggleClockFormat.text = "Clock Format: " + if (repository.isClock24HourFormat()) "24-Hour" else "12-Hour (AM/PM)"
        }

        refreshLabels()

        // Section 1: Apps
        btnEditDock.setOnClickListener {
            dialog.dismiss()
            showEditDockDialog()
        }

        btnHiddenApps.setOnClickListener {
            dialog.dismiss()
            showHiddenAppsDialog()
        }

        // Section 2: Appearance
        btnOpenWallpaper.setOnClickListener {
            dialog.dismiss()
            showWallpaperSettingsDialog()
        }

        btnToggleCardLabels.setOnClickListener {
            val next = !repository.isShowCardLabels()
            repository.setShowCardLabels(next)
            gridAdapter.setShowCardLabels(next)
            refreshLabels()
        }

        btnToggleAmbientGlow.setOnClickListener {
            val next = !repository.isAmbientGlowEnabled()
            repository.setAmbientGlowEnabled(next)
            binding.heroAmbientView.setAmbientGlowEnabled(next)
            refreshLabels()
        }

        btnToggleContinueWatching.setOnClickListener {
            val next = !repository.isContinueWatchingEnabled()
            repository.setContinueWatchingEnabled(next)
            syncContinueWatchingState(notifyIfEmpty = next)
            refreshLabels()
        }

        btnToggleHomeLayout.setOnClickListener {
            val next = if (repository.isShowcaseMode()) AppRepository.HOME_LAYOUT_CLASSIC else AppRepository.HOME_LAYOUT_SHOWCASE
            repository.setHomeLayoutMode(next)
            applyStagePositions(inGrid = isGridExpanded, animate = true)
            refreshLabels()
        }

        // Section 3: Clock & System Tools
        btnToggleClockFormat.setOnClickListener {
            val next = !repository.isClock24HourFormat()
            repository.setClock24HourFormat(next)
            applyClockFormat()
            refreshLabels()
        }

        btnTrimCache.setOnClickListener {
            dialog.dismiss()
            trimRamAndCaches()
        }

        btnSleepTv.setOnClickListener {
            dialog.dismiss()
            sleepTv()
        }

        btnRestartTv.setOnClickListener {
            dialog.dismiss()
            restartTv()
        }

        btnOpenSystemSettings.setOnClickListener {
            dialog.dismiss()
            openAndroidTvSettings()
        }

        dialog.show()
        btnEditDock.requestFocus()
    }

    private fun showHiddenAppsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_app_list)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = dialog.findViewById<TextView>(R.id.tvAppListTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvAppListSubtitle)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvEmptyListState)
        val svAppList = dialog.findViewById<ScrollView>(R.id.svAppList)
        val llContainer = dialog.findViewById<LinearLayout>(R.id.llAppListContainer)
        val btnClose = dialog.findViewById<TextView>(R.id.btnCloseAppList)

        tvTitle.text = "Hidden Apps"
        tvSubtitle.text = "Select an app to unhide it and restore it to the All Apps grid"

        fun populateHiddenRows() {
            llContainer.removeAllViews()
            val hiddenEntries = repository.loadHiddenAppEntries()
            if (hiddenEntries.isEmpty()) {
                tvEmpty.text = "No hidden apps"
                tvEmpty.visibility = View.VISIBLE
                svAppList.visibility = View.GONE
                btnClose.requestFocus()
            } else {
                tvEmpty.visibility = View.GONE
                svAppList.visibility = View.VISIBLE
                val inflater = LayoutInflater.from(this)
                hiddenEntries.forEachIndexed { index, entry ->
                    val row = inflater.inflate(R.layout.item_dialog_app_row, llContainer, false)
                    val ivIcon = row.findViewById<ImageView>(R.id.ivRowAppIcon)
                    val tvLabel = row.findViewById<TextView>(R.id.tvRowAppLabel)
                    val tvStatus = row.findViewById<TextView>(R.id.tvRowAppStatus)

                    ivIcon.setImageDrawable(entry.icon)
                    tvLabel.text = entry.label
                    tvStatus.text = "Unhide"

                    row.setOnClickListener {
                        repository.unhideApp(entry.packageName)
                        refreshApps(preserveFocus = true)
                        Toast.makeText(
                            this,
                            "Restored ${entry.label}",
                            Toast.LENGTH_SHORT
                        ).show()
                        populateHiddenRows()
                    }
                    llContainer.addView(row)
                    if (index == 0) {
                        row.post { row.requestFocus() }
                    }
                }
            }
        }

        populateHiddenRows()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showEditDockDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_app_list)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = dialog.findViewById<TextView>(R.id.tvAppListTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvAppListSubtitle)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvEmptyListState)
        val svAppList = dialog.findViewById<ScrollView>(R.id.svAppList)
        val llContainer = dialog.findViewById<LinearLayout>(R.id.llAppListContainer)
        val btnClose = dialog.findViewById<TextView>(R.id.btnCloseAppList)

        tvTitle.text = "Edit Dock"
        tvSubtitle.text = "Toggle apps to pin or unpin from the Floating Glass Dock (up to 6 apps)"
        tvEmpty.visibility = View.GONE
        svAppList.visibility = View.VISIBLE

        val (_, allVisibleApps) = repository.loadApps()
        val inflater = LayoutInflater.from(this)

        allVisibleApps.forEachIndexed { index, entry ->
            val row = inflater.inflate(R.layout.item_dialog_app_row, llContainer, false)
            val ivIcon = row.findViewById<ImageView>(R.id.ivRowAppIcon)
            val tvLabel = row.findViewById<TextView>(R.id.tvRowAppLabel)
            val tvStatus = row.findViewById<TextView>(R.id.tvRowAppStatus)

            ivIcon.setImageDrawable(entry.icon)
            tvLabel.text = entry.label

            fun updatePinBadge() {
                val pinned = repository.isPinned(entry.packageName)
                tvStatus.text = if (pinned) "✓ Pinned" else "Pin"
                tvStatus.alpha = if (pinned) 1.0f else 0.55f
            }

            updatePinBadge()

            row.setOnClickListener {
                repository.togglePinToDock(entry.packageName)
                refreshApps(preserveFocus = true)
                // Refresh all badges in case the 6-app limit bumped an item
                for (i in 0 until llContainer.childCount) {
                    val child = llContainer.getChildAt(i)
                    val pkg = child.tag as? String ?: continue
                    val statusView = child.findViewById<TextView>(R.id.tvRowAppStatus)
                    val isNowPinned = repository.isPinned(pkg)
                    statusView.text = if (isNowPinned) "✓ Pinned" else "Pin"
                    statusView.alpha = if (isNowPinned) 1.0f else 0.55f
                }
            }
            row.tag = entry.packageName
            llContainer.addView(row)
            if (index == 0) {
                row.post { row.requestFocus() }
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun trimRamAndCaches() {
        Thread {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "pm trim-caches 999G")).waitFor()
            } catch (_: Exception) {
                try {
                    Runtime.getRuntime().exec("pm trim-caches 999G").waitFor()
                } catch (_: Exception) {
                }
            }
            repository.clearMemoryCache()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, "Cache Trimmed", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun sleepTv() {
        Thread {
            try {
                Runtime.getRuntime().exec("input keyevent KEYCODE_SLEEP")
            } catch (_: Exception) {
                try {
                    Instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_SLEEP)
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    private fun restartTv() {
        Thread {
            try {
                val rebootIntent = Intent(Intent.ACTION_REBOOT).apply {
                    putExtra("nowait", 1)
                    putExtra("interval", 1)
                    putExtra("window", 0)
                }
                sendBroadcast(rebootIntent)
            } catch (_: Exception) {
            }
            try {
                Runtime.getRuntime().exec("reboot")
            } catch (_: Exception) {
                try {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "svc power reboot || reboot"))
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    private fun showWallpaperSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_wallpaper_settings)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnThemeCharcoal = dialog.findViewById<TextView>(R.id.btnThemeCharcoal)
        val btnThemeOled = dialog.findViewById<TextView>(R.id.btnThemeOled)
        val btnThemeCustomUrl = dialog.findViewById<TextView>(R.id.btnThemeCustomUrl)

        val currentType = repository.getWallpaperType()
        btnThemeCharcoal.text = if (currentType == AppRepository.WALLPAPER_CHARCOAL) {
            "✓  " + getString(R.string.wallpaper_theme_charcoal)
        } else {
            getString(R.string.wallpaper_theme_charcoal)
        }
        btnThemeOled.text = if (currentType == AppRepository.WALLPAPER_OLED_BLACK) {
            "✓  " + getString(R.string.wallpaper_theme_oled)
        } else {
            getString(R.string.wallpaper_theme_oled)
        }
        btnThemeCustomUrl.text = if (currentType == AppRepository.WALLPAPER_CUSTOM) {
            "✓  " + getString(R.string.wallpaper_theme_custom_url)
        } else {
            getString(R.string.wallpaper_theme_custom_url)
        }

        btnThemeCharcoal.setOnClickListener {
            repository.setWallpaperType(AppRepository.WALLPAPER_CHARCOAL)
            applySavedWallpaper()
            dialog.dismiss()
        }

        btnThemeOled.setOnClickListener {
            repository.setWallpaperType(AppRepository.WALLPAPER_OLED_BLACK)
            applySavedWallpaper()
            dialog.dismiss()
        }

        btnThemeCustomUrl.setOnClickListener {
            dialog.dismiss()
            showCustomWallpaperUrlDialog()
        }

        dialog.show()
        when (currentType) {
            AppRepository.WALLPAPER_OLED_BLACK -> btnThemeOled.requestFocus()
            AppRepository.WALLPAPER_CUSTOM -> btnThemeCustomUrl.requestFocus()
            else -> btnThemeCharcoal.requestFocus()
        }
    }

    private fun showCustomWallpaperUrlDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_custom_wallpaper_url)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val etWallpaperUrl = dialog.findViewById<EditText>(R.id.etWallpaperUrl)
        val tvUrlStatus = dialog.findViewById<TextView>(R.id.tvUrlStatus)
        val btnCancelUrl = dialog.findViewById<TextView>(R.id.btnCancelUrl)
        val btnSubmitUrl = dialog.findViewById<TextView>(R.id.btnSubmitUrl)

        val savedUrl = repository.getCustomWallpaperUrl()
        if (savedUrl.isNotEmpty()) {
            etWallpaperUrl.setText(savedUrl)
            etWallpaperUrl.setSelection(savedUrl.length)
        }

        btnCancelUrl.setOnClickListener {
            dialog.dismiss()
        }

        val submitAction = {
            val inputUrl = etWallpaperUrl.text?.toString()?.trim().orEmpty()
            if (inputUrl.isEmpty()) {
                tvUrlStatus.visibility = View.VISIBLE
                tvUrlStatus.setTextColor(Color.parseColor("#FF453A"))
                tvUrlStatus.text = "Please paste a valid image or Google Drive URL."
            } else {
                tvUrlStatus.visibility = View.VISIBLE
                tvUrlStatus.setTextColor(Color.parseColor("#0A84FF"))
                tvUrlStatus.text = getString(R.string.wallpaper_downloading)
                btnSubmitUrl.isEnabled = false
                btnCancelUrl.isEnabled = false

                repository.downloadAndSaveCustomWallpaper(inputUrl) { success, bitmap, errorMsg ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        btnSubmitUrl.isEnabled = true
                        btnCancelUrl.isEnabled = true

                        if (success && bitmap != null) {
                            binding.heroAmbientView.setWallpaperMode(
                                AppRepository.WALLPAPER_CUSTOM,
                                bitmap
                            )
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.wallpaper_applied_toast),
                                Toast.LENGTH_SHORT
                            ).show()
                            dialog.dismiss()
                        } else {
                            tvUrlStatus.visibility = View.VISIBLE
                            tvUrlStatus.setTextColor(Color.parseColor("#FF453A"))
                            tvUrlStatus.text = errorMsg ?: getString(R.string.wallpaper_failed_toast)
                            // Ensure fallback remains active if no custom wallpaper was previously saved
                            applySavedWallpaper()
                        }
                    }
                }
            }
        }

        btnSubmitUrl.setOnClickListener {
            submitAction()
        }

        dialog.show()
        etWallpaperUrl.requestFocus()
    }

    private fun launchApp(packageName: String) {
        try {
            val pm = packageManager
            val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
                ?: pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (_: Exception) {
        }
    }

    private fun openAndroidTvSettings() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun openSonyInputSelector() {
        // 1. Primary: Launch Sony SystemUI QuickLauncher Input Ribbon (com.android.tv.action.VIEW_INPUTS)
        try {
            val sonyInputOverlay = Intent("com.android.tv.action.VIEW_INPUTS").apply {
                setClassName("com.android.systemui", "com.sony.dtv.systemui.quicklauncher.LaunchActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(sonyInputOverlay)
            return
        } catch (_: Exception) {
        }

        // 2. Fallback: Dispatch KEYCODE_TV_INPUT (178) or SETUP_INPUTS
        Thread {
            try {
                Instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_TV_INPUT)
            } catch (_: Exception) {
                runOnUiThread {
                    try {
                        val setupInputs = Intent("android.media.tv.action.SETUP_INPUTS").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(setupInputs)
                    } catch (_: Exception) {
                    }
                }
            }
        }.start()
    }

    private fun openAppDetailsSettings(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
