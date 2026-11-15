package com.appletv.launcher

import android.graphics.Bitmap
import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class WatchNextAdapter(
    private val onItemFocused: (WatchNextItem, Int) -> Unit,
    private val onItemClicked: (WatchNextItem) -> Unit
) : RecyclerView.Adapter<WatchNextAdapter.WatchNextViewHolder>() {

    private val items = mutableListOf<WatchNextItem>()
    private val focusInterpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f)

    fun submitList(newItems: List<WatchNextItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): WatchNextItem? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WatchNextViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_watch_next, parent, false)
        return WatchNextViewHolder(view)
    }

    override fun onBindViewHolder(holder: WatchNextViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class WatchNextViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: View = itemView.findViewById(R.id.watchNextCardRoot)
        private val tileSurface: FrameLayout = itemView.findViewById(R.id.watchNextTileSurface)
        private val ivPoster: ImageView = itemView.findViewById(R.id.ivWatchNextPoster)
        private val tvCardTitle: TextView = itemView.findViewById(R.id.tvWatchNextCardTitle)
        private val pbProgress: ProgressBar = itemView.findViewById(R.id.pbWatchNextProgress)
        private val focusBorder: View = itemView.findViewById(R.id.watchNextFocusBorder)

        init {
            val radiusPx = 12f * itemView.resources.displayMetrics.density
            tileSurface.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            tileSurface.clipToOutline = true
        }

        fun bind(item: WatchNextItem, position: Int) {
            tvCardTitle.text = item.title

            if (!item.posterArtUri.isNullOrBlank()) {
                ivPoster.load(item.posterArtUri) {
                    allowHardware(false)
                    bitmapConfig(Bitmap.Config.ARGB_8888)
                    crossfade(true)
                }
            } else {
                ivPoster.setImageDrawable(null)
            }

            if (item.progressPercent > 0) {
                pbProgress.visibility = View.VISIBLE
                pbProgress.progress = item.progressPercent.coerceIn(1, 100)
            } else {
                pbProgress.visibility = View.GONE
            }

            val currentlyFocused = cardRoot.isFocused
            cardRoot.scaleX = if (currentlyFocused) 1.08f else 1.0f
            cardRoot.scaleY = if (currentlyFocused) 1.08f else 1.0f
            cardRoot.translationZ = if (currentlyFocused) 18f else 0f
            focusBorder.alpha = if (currentlyFocused) 1f else 0f

            cardRoot.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
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

                if (hasFocus) {
                    val pos = adapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position
                    onItemFocused(item, pos)
                }
            }

            cardRoot.setOnClickListener {
                onItemClicked(item)
            }
        }
    }
}
