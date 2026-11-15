package com.appletv.launcher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max

/**
 * Lightweight hardware-accelerated background & ambient stage view.
 * Supports:
 * 1. "charcoal": Deep #0B0B0E tvOS Charcoal Gradient with dynamic app-colored radial glow.
 * 2. "oled_black": True #000000 OLED Pitch Black.
 * 3. "custom": Custom wallpaper Bitmap with #8A000000 (55% black) dimming overlay and
 *    a top-to-bottom vignette gradient for crisp contrast and legibility.
 */
class HeroAmbientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val charcoalColor = Color.parseColor("#0B0B0E")
    private val oledBlackColor = Color.parseColor("#000000")
    private val customDimOverlayColor = Color.parseColor("#8A000000") // 55% black dimming scrim

    private var wallpaperType: String = AppRepository.WALLPAPER_CHARCOAL
    private var customBitmap: Bitmap? = null
    private var ambientGlowEnabled: Boolean = true
    private val bitmapMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var currentGlowColor: Int = Color.parseColor("#3B82F6")
    private var colorAnimator: ValueAnimator? = null

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val secondaryGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val customVignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShaders(w, h)
        updateBitmapMatrix(w, h)
    }

    fun setWallpaperMode(type: String, bitmap: Bitmap?) {
        wallpaperType = type
        customBitmap = if (bitmap != null && bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        if (width > 0 && height > 0) {
            updateShaders(width, height)
            updateBitmapMatrix(width, height)
        }
        invalidate()
    }

    private var customScrimAlpha: Float = 1.0f // 1.0f = full 55% dimming, 0.0f = subtle/no flat dimming

    fun setCustomScrimAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0f, 1f)
        if (customScrimAlpha != clamped) {
            customScrimAlpha = clamped
            invalidate()
        }
    }

    fun setAmbientGlowEnabled(enabled: Boolean) {
        if (ambientGlowEnabled == enabled) return
        ambientGlowEnabled = enabled
        invalidate()
    }

    fun animateToColor(targetColor: Int) {
        if (targetColor == currentGlowColor) return
        colorAnimator?.cancel()
        val startColor = currentGlowColor
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), startColor, targetColor).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                currentGlowColor = animator.animatedValue as Int
                if (width > 0 && height > 0) {
                    updateShaders(width, height)
                    invalidate()
                }
            }
            start()
        }
    }

    private fun updateBitmapMatrix(w: Int, h: Int) {
        val bmp = customBitmap ?: return
        if (w <= 0 || h <= 0 || bmp.width <= 0 || bmp.height <= 0) return

        // Center-crop scaling matrix
        val scale: Float
        var dx = 0f
        var dy = 0f

        if (bmp.width * h > w * bmp.height) {
            scale = h.toFloat() / bmp.height.toFloat()
            dx = (w - bmp.width * scale) * 0.5f
        } else {
            scale = w.toFloat() / bmp.width.toFloat()
            dy = (h - bmp.height * scale) * 0.5f
        }

        bitmapMatrix.reset()
        bitmapMatrix.setScale(scale, scale)
        bitmapMatrix.postTranslate(dx, dy)
    }

    private fun updateShaders(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        val r = Color.red(currentGlowColor)
        val g = Color.green(currentGlowColor)
        val b = Color.blue(currentGlowColor)

        // Primary ambient radial glow behind the Hero title (top-left/center)
        val centerX = w * 0.24f
        val centerY = h * 0.22f
        val radius = max(w, h) * 0.62f

        val centerAlpha = if (wallpaperType == AppRepository.WALLPAPER_CUSTOM) 48 else 86
        val midAlpha = if (wallpaperType == AppRepository.WALLPAPER_CUSTOM) 18 else 34
        val centerAlphaColor = Color.argb(centerAlpha, r, g, b)
        val midAlphaColor = Color.argb(midAlpha, r, g, b)
        val edgeColor = Color.argb(0, 11, 11, 14)

        glowPaint.shader = RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(centerAlphaColor, midAlphaColor, edgeColor),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )

        // Subtle secondary ambient wash near top-right behind the dock/hero preview
        val secX = w * 0.72f
        val secY = h * 0.16f
        val secRadius = max(w, h) * 0.45f
        val secAlpha = if (wallpaperType == AppRepository.WALLPAPER_CUSTOM) 24 else 42
        val secCenterColor = Color.argb(secAlpha, r, g, b)

        secondaryGlowPaint.shader = RadialGradient(
            secX,
            secY,
            secRadius,
            intArrayOf(secCenterColor, edgeColor),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        // Bottom vertical fade into deep #0B0B0E for default charcoal mode
        vignettePaint.shader = LinearGradient(
            0f,
            h * 0.35f,
            0f,
            h.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.argb(180, 11, 11, 14), charcoalColor),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )

        // Top-to-bottom subtle vignette gradient over custom wallpapers
        customVignettePaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            intArrayOf(
                Color.argb(150, 8, 8, 12),
                Color.argb(30, 8, 8, 12),
                Color.argb(110, 8, 8, 12),
                Color.argb(210, 8, 8, 12)
            ),
            floatArrayOf(0f, 0.32f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val bmp = customBitmap
        if (wallpaperType == AppRepository.WALLPAPER_CUSTOM && bmp != null && !bmp.isRecycled) {
            // 1. Draw custom wallpaper center-cropped
            canvas.drawBitmap(bmp, bitmapMatrix, bitmapPaint)
            // 2. Apply #8A000000 (55% black) dimming overlay (modulated by customScrimAlpha)
            if (customScrimAlpha > 0f) {
                val overlayAlpha = (0x8A * customScrimAlpha).toInt().coerceIn(0, 255)
                canvas.drawColor(Color.argb(overlayAlpha, 0, 0, 0))
            }
            // 3. Apply subtle top-to-bottom vignette gradient & optional ambient tint
            canvas.drawRect(0f, 0f, w, h, customVignettePaint)
            if (ambientGlowEnabled) {
                canvas.drawRect(0f, 0f, w, h, glowPaint)
            }
        } else if (wallpaperType == AppRepository.WALLPAPER_OLED_BLACK) {
            // Pure #000000 OLED Pitch Black (optional ambient glow if enabled)
            canvas.drawColor(oledBlackColor)
            if (ambientGlowEnabled) {
                canvas.drawRect(0f, 0f, w, h, glowPaint)
            }
        } else {
            // Default tvOS Charcoal Gradient (#0B0B0E + ambient radial glow)
            canvas.drawColor(charcoalColor)
            if (ambientGlowEnabled) {
                canvas.drawRect(0f, 0f, w, h, secondaryGlowPaint)
                canvas.drawRect(0f, 0f, w, h, glowPaint)
            }
            canvas.drawRect(0f, 0f, w, h, vignettePaint)
        }
    }
}
