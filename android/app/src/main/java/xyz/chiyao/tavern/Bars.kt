package xyz.chiyao.tavern

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.WindowInsetsController

/**
 * The space kept free around the pages (status bar, navigation bar, cutout, keyboard; see MainActivity.fitBars)
 * shows the root view's background. This paints it: the upper half continues the page's top edge, the lower half
 * its bottom edge. The pages cover everything else, so only those margins show.
 *
 * An edge is either a solid color or a row of pixels copied from the screen (EdgeSampler), stretched outwards:
 * SillyTavern's bars are translucent over a blurred background picture, so no CSS color matches what is drawn.
 */
class BarsDrawable(private var topColor: Int, private var bottomColor: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val half = RectF()
    private var topRow: Bitmap? = null
    private var bottomRow: Bitmap? = null

    fun solid(top: Int, bottom: Int) {
        if (topRow == null && bottomRow == null && top == topColor && bottom == bottomColor) return
        topRow = null
        bottomRow = null
        topColor = top
        bottomColor = bottom
        invalidateSelf()
    }

    fun rows(top: Bitmap, bottom: Bitmap) {
        if (topRow?.sameAs(top) == true && bottomRow?.sameAs(bottom) == true) return
        topRow = top
        bottomRow = bottom
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val mid = b.exactCenterY()
        half.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), mid)
        paint(canvas, topRow, topColor)
        half.set(b.left.toFloat(), mid, b.right.toFloat(), b.bottom.toFloat())
        paint(canvas, bottomRow, bottomColor)
    }

    private fun paint(canvas: Canvas, row: Bitmap?, color: Int) {
        paint.color = if (row != null) Color.BLACK else color
        if (row != null) canvas.drawBitmap(row, null, half, paint) else canvas.drawRect(half, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Drawable API")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        /** Dark icons on light bars and light icons on dark ones */
        fun applyIcons(act: Activity, top: Int, bottom: Int) {
            if (Build.VERSION.SDK_INT < 30) return
            val c = act.window.insetsController ?: return
            val status = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            val nav = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            c.setSystemBarsAppearance(if (light(top)) status else 0, status)
            c.setSystemBarsAppearance(if (light(bottom)) nav else 0, nav)
        }

        fun light(color: Int) =
            (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) > 150

        /** Mean color of a row, for the icons and for the margins before the next start-up's first sample */
        fun average(row: Bitmap): Int {
            var r = 0; var g = 0; var b = 0
            val px = IntArray(row.width)
            row.getPixels(px, 0, row.width, 0, 0, row.width, 1)
            for (p in px) { r += Color.red(p); g += Color.green(p); b += Color.blue(p) }
            return Color.rgb(r / px.size, g / px.size, b / px.size)
        }
    }
}

/**
 * Copies, a few times a second, a thin strip just inside the top and the bottom edge of `view` from the screen,
 * scaled down to a row of COLS pixels (enough for a smooth gradient, small enough to copy often).
 * Only the page's pixels are read, never the margins themselves, so what is painted there does not feed back.
 */
class EdgeSampler(private val window: Window, private val view: View, private val onRows: (Bitmap, Bitmap) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var busy = false
    private val tick = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun sample() {
        val density = view.resources.displayMetrics.density
        // past the 1px border SillyTavern draws around the input area
        val inset = Math.round(INSET_DP * density)
        val depth = Math.round(DEPTH_DP * density).coerceAtLeast(1)
        if (busy || !view.isShown || view.width == 0 || view.height < 2 * (inset + depth)) return
        val at = IntArray(2)
        view.getLocationInWindow(at)
        val left = at[0]
        val right = left + view.width
        val top = at[1]
        val bottom = top + view.height
        val topRow = Bitmap.createBitmap(COLS, 1, Bitmap.Config.ARGB_8888)
        val bottomRow = Bitmap.createBitmap(COLS, 1, Bitmap.Config.ARGB_8888)
        busy = true
        try {
            PixelCopy.request(window, Rect(left, top + inset, right, top + inset + depth), topRow, { t ->
                if (t != PixelCopy.SUCCESS) { busy = false; return@request }
                try {
                    PixelCopy.request(window, Rect(left, bottom - inset - depth, right, bottom - inset), bottomRow, { b ->
                        busy = false
                        if (running && b == PixelCopy.SUCCESS) onRows(topRow, bottomRow)
                    }, handler)
                } catch (_: IllegalArgumentException) {
                    busy = false
                }
            }, handler)
        } catch (_: IllegalArgumentException) {
            // the window has no surface yet (or any more)
            busy = false
        }
    }

    private companion object {
        const val COLS = 24
        const val INSET_DP = 2f
        const val DEPTH_DP = 2f
        const val PERIOD_MS = 400L
    }
}
