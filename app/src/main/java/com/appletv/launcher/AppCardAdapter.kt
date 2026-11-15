package com.appletv.launcher

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppCardAdapter(
    private val isDock: Boolean,
    private val onAppFocused: (AppEntry, Boolean, Int) -> Unit,
    private val onAppClicked: (AppEntry) -> Unit,
    private val onAppLongClicked: (AppEntry) -> Unit,
    private val onAppDpadUp: ((position: Int) -> Boolean)? = null
) : RecyclerView.Adapter<AppCardAdapter.AppViewHolder>() {

    private val items = mutableListOf<AppEntry>()
    private val focusInterpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f)
    private var showCardLabels: Boolean = true

    fun submitList(newItems: List<AppEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setShowCardLabels(show: Boolean) {
        if (showCardLabels != show) {
            showCardLabels = show
            notifyDataSetChanged()
        }
    }

    fun getItemAt(position: Int): AppEntry? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val layoutRes = if (isDock) R.layout.item_dock_app else R.layout.item_grid_app
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: View = itemView.findViewById(R.id.cardRoot)
        private val scaleTarget: View = itemView.findViewById(R.id.cardFrame) ?: cardRoot
        private val tileSurface: FrameLayout = itemView.findViewById(R.id.tileSurface)
        private val ivBanner: ImageView = itemView.findViewById(R.id.ivBanner)
        private val fallbackContainer: View = itemView.findViewById(R.id.fallbackContainer)
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val tvFallbackName: TextView? = itemView.findViewById(R.id.tvFallbackName)
        private val tvAppLabel: TextView? = itemView.findViewById(R.id.tvAppLabel)
        private val focusBorder: View = itemView.findViewById(R.id.focusBorder)

        init {
            val radiusPx = 16f * itemView.resources.displayMetrics.density
            tileSurface.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            tileSurface.clipToOutline = true
        }

        fun bind(entry: AppEntry, position: Int) {
            tvAppLabel?.text = entry.label
            tvAppLabel?.visibility = if (!isDock && !showCardLabels) View.GONE else View.VISIBLE
            tvFallbackName?.text = entry.label

            if (entry.banner != null) {
                ivBanner.visibility = View.VISIBLE
                fallbackContainer.visibility = View.GONE
                ivBanner.setImageDrawable(entry.banner)
                tileSurface.setBackgroundResource(R.drawable.bg_card_glass)
            } else {
                ivBanner.visibility = View.GONE
                fallbackContainer.visibility = View.VISIBLE
                ivIcon.setImageDrawable(entry.icon)
                tileSurface.background = createTintedGlassBackground(
                    tileSurface.resources.displayMetrics.density,
                    entry.accentColor
                )
            }

            // Apply initial focus state without animation
            val currentlyFocused = cardRoot.isFocused
            scaleTarget.scaleX = if (currentlyFocused) 1.08f else 1.0f
            scaleTarget.scaleY = if (currentlyFocused) 1.08f else 1.0f
            scaleTarget.translationZ = if (currentlyFocused) 18f else 0f
            focusBorder.alpha = if (currentlyFocused) 1f else 0f
            tvAppLabel?.alpha = if (currentlyFocused) 1.0f else 0.72f
            tvAppLabel?.translationY = if (currentlyFocused) 5f else 0f

            cardRoot.setOnFocusChangeListener { _, hasFocus ->
                scaleTarget.animate()
                    .scaleX(if (hasFocus) 1.08f else 1.0f)
                    .scaleY(if (hasFocus) 1.08f else 1.0f)
                    .translationZ(if (hasFocus) 18f else 0f)
                    .setDuration(150L)
                    .setInterpolator(focusInterpolator)
                    .start()

                focusBorder.animate()
                    .alpha(if (hasFocus) 1f else 0f)
                    .setDuration(150L)
                    .setInterpolator(focusInterpolator)
                    .start()

                tvAppLabel?.animate()
                    ?.alpha(if (hasFocus) 1.0f else 0.72f)
                    ?.translationY(if (hasFocus) 5f else 0f)
                    ?.setDuration(150L)
                    ?.setInterpolator(focusInterpolator)
                    ?.start()

                if (hasFocus) {
                    val pos = adapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position
                    onAppFocused(entry, isDock, pos)
                }
            }

            cardRoot.setOnClickListener {
                onAppClicked(entry)
            }

            cardRoot.setOnLongClickListener {
                onAppLongClicked(entry)
                true
            }

            cardRoot.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    val pos = adapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position
                    if (onAppDpadUp?.invoke(pos) == true) {
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

        private fun createTintedGlassBackground(density: Float, accentColor: Int): GradientDrawable {
            val r = Color.red(accentColor)
            val g = Color.green(accentColor)
            val b = Color.blue(accentColor)

            // Blend brand accent subtly into dark glass surface
            val topColor = Color.rgb(
                (26 + r * 0.22f).toInt().coerceAtMost(255),
                (28 + g * 0.22f).toInt().coerceAtMost(255),
                (38 + b * 0.22f).toInt().coerceAtMost(255)
            )
            val bottomColor = Color.rgb(
                (16 + r * 0.10f).toInt().coerceAtMost(255),
                (17 + g * 0.10f).toInt().coerceAtMost(255),
                (24 + b * 0.10f).toInt().coerceAtMost(255)
            )

            return GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(topColor, bottomColor)
            ).apply {
                cornerRadius = 16f * density
                setStroke((1f * density).toInt().coerceAtLeast(1), Color.argb(46, 255, 255, 255))
            }
        }
    }
}
