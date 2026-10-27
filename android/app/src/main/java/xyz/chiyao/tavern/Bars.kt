package xyz.chiyao.tavern

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface

/**
 * The space kept free around the pages (status bar, navigation bar, cutout, keyboard; see MainActivity.fitBars)
 * shows the root view's background. This paints it: the upper half in the color of the page's top bar, the
 * lower half in the color of its bottom bar. The pages cover everything else, so only those margins show.
 */
class BarsDrawable(top: Int, bottom: Int) : Drawable() {
    private val paint = Paint()
    var top = top
        private set
    var bottom = bottom
        private set

    fun set(top: Int, bottom: Int) {
        if (top == this.top && bottom == this.bottom) return
        this.top = top
        this.bottom = bottom
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val mid = b.exactCenterY()
        paint.color = top
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), mid, paint)
        paint.color = bottom
        canvas.drawRect(b.left.toFloat(), mid, b.right.toFloat(), b.bottom.toFloat(), paint)
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
    }
}

/**
 * window.TavernBars in SillyTavern's page: assets/inject/bars.js reports the colors of the page's top bar and
 * input area. Keyed like Saver, so only the script the app injected is heard.
 */
class TavernBars(private val key: String, private val onColors: (Int, Int) -> Unit) {
    @JavascriptInterface
    fun colors(key: String, top: String, bottom: String) {
        if (key != this.key) return
        val t = runCatching { Color.parseColor(top) }.getOrNull() ?: return
        val b = runCatching { Color.parseColor(bottom) }.getOrNull() ?: return
        onColors(t, b)
    }
}
