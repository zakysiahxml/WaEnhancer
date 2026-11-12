package com.wmods.wppenhacer.xposed.features.customization

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wmods.wppenhacer.xposed.utils.Utils

/**
 * Blur beneran (frosted glass) buat bar bawah — library Dimezis/BlurView
 * (Apache-2.0) butuh BlurTarget yang membungkus konten, dan itu berarti
 * merombak hierarki view WhatsApp di dalam hook (berisiko). Jadi blur di
 * sini dibuat sendiri, tanpa library:
 *
 * - tangkap isi layar di belakang bar (decor view digambar ke bitmap,
 *   bar disembunyikan sesaat biar tidak ikut kegambar),
 * - blur bitmap-nya pakai RenderEffect (API 31+, jalan di RenderThread
 *   sistem — HP user Android 16, jadi jalur ini yang kepakai),
 * - pasang hasil blur sebagai background bar (sudut membulat).
 * - update diulang tiap chat list di-scroll (di-throttle), biar blur
 *   ngikutin isi yang lewat di belakang bar.
 *
 * Kalau blur gagal (API < 31, capture gagal, dsb) pemanggil harus
 * fallback ke warna semi-transparan biasa — jangan biarkan bar jadi
 * transparan polos.
 */
object GlassBarBlur {

    // Skala tangkapan: 0.25x cukup buat blur (lebih cepat, tetap halus).
    private const val CAPTURE_SCALE = 0.25f
    // Radius blur (dalam px pada bitmap yang sudah di-downscale).
    private const val BLUR_RADIUS = 18f
    // Overlay tipis di atas hasil blur (alpha 0.20): cukup memberi warna
    // abu-abu kebiruan, tapi isi di belakang bar tetap kelihatan.
    private const val OVERLAY_COLOR = 0x332E3B42.toInt()
    // Jeda minimum antar update saat scroll (ms).
    private const val UPDATE_THROTTLE_MS = 120L

    private var lastUpdateMs = 0L
    private var scrollListenerAttachedTo: RecyclerView? = null
    private var currentRadiusDp = 28f

    /** Cari Activity dari context (context bisa terbungkus ContextWrapper). */
    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx != null) {
            if (ctx is Activity) return ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        return null
    }

    /**
     * Pasang blur ke [bar]. Dipanggil sesudah bar selesai di-setup sebagai
     * floating bar. [radiusDp] = radius sudut bar (dp). Return true kalau
     * background blur berhasil dipasang, false kalau harus fallback.
     */
    fun applyBlur(bar: ViewGroup, radiusDp: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val activity = bar.context.findActivity() ?: return false
        val root = activity.window?.decorView as? ViewGroup ?: return false
        currentRadiusDp = radiusDp
        val blurred = captureAndBlur(bar, root, radiusDp) ?: return false
        bar.background = BitmapDrawable(bar.resources, blurred)
        attachScrollUpdater(bar, activity)
        return true
    }

    /** Update blur (dipakai saat konten di belakang bar berubah / scroll). */
    fun refreshBlur(bar: ViewGroup, radiusDp: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastUpdateMs < UPDATE_THROTTLE_MS) return
        lastUpdateMs = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val activity = bar.context.findActivity() ?: return
        val root = activity.window?.decorView as? ViewGroup ?: return
        val blurred = captureAndBlur(bar, root, radiusDp) ?: return
        bar.post { bar.background = BitmapDrawable(bar.resources, blurred) }
    }

    private fun captureAndBlur(bar: ViewGroup, root: ViewGroup, radiusDp: Float): Bitmap? {
        return try {
            val loc = IntArray(2)
            bar.getLocationInWindow(loc)
            val x = loc[0]
            val y = loc[1]
            val w = bar.width
            val h = bar.height
            if (w <= 0 || h <= 0) return null

            val sw = (w * CAPTURE_SCALE).toInt().coerceAtLeast(1)
            val sh = (h * CAPTURE_SCALE).toInt().coerceAtLeast(1)
            val src = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(src)
            canvas.scale(CAPTURE_SCALE, CAPTURE_SCALE)
            canvas.translate(-x.toFloat(), -y.toFloat())
            // Sembunyikan bar sesaat supaya tidak ikut kegambar.
            val oldVisibility = bar.visibility
            bar.visibility = View.INVISIBLE
            try {
                root.draw(canvas)
            } finally {
                bar.visibility = oldVisibility
            }

            val blurred = blurBitmap(src, BLUR_RADIUS)
            src.recycle()
            // Timpa overlay biar warna blur mirip referensi (abu-abu gelap
            // kebiruan, bukan hitam polos).
            val tinted = tintBitmap(blurred, OVERLAY_COLOR)
            blurred.recycle()
            // Bulatkan sudut (radius ikut skala tangkapan).
            roundBitmap(tinted, Utils.dipToPixels(radiusDp).toFloat() * CAPTURE_SCALE)
        } catch (_: Throwable) {
            null
        }
    }

    /** Blur bitmap pakai RenderEffect (API 31+). */
    private fun blurBitmap(src: Bitmap, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val renderNode = RenderNode("glass_blur")
        renderNode.setPosition(0, 0, src.width, src.height)
        renderNode.setRenderEffect(
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        )
        val recordCanvas = renderNode.beginRecording()
        recordCanvas.drawBitmap(src, 0f, 0f, null)
        renderNode.endRecording()
        val outCanvas = Canvas(out)
        outCanvas.drawRenderNode(renderNode)
        return out
    }

    /** Timpa bitmap dengan warna overlay (biar warna blur mirip referensi). */
    private fun tintBitmap(src: Bitmap, overlayColor: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, null)
        val paint = android.graphics.Paint()
        paint.color = overlayColor
        canvas.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), paint)
        return out
    }

    /** Potong bitmap jadi bersudut membulat. */
    private fun roundBitmap(src: Bitmap, radiusPx: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = Path()
        path.addRoundRect(
            RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
            radiusPx, radiusPx, Path.Direction.CW
        )
        canvas.clipPath(path)
        canvas.drawBitmap(src, 0f, 0f, null)
        src.recycle()
        return out
    }

    /**
     * Cari RecyclerView chat list di activity lalu pasang scroll listener
     * yang me-refresh blur (di-throttle). Sekali pasang per RecyclerView.
     */
    private fun attachScrollUpdater(bar: ViewGroup, activity: Activity) {
        try {
            val root = activity.window?.decorView as? ViewGroup ?: return
            val recycler = findRecyclerView(root) ?: return
            if (scrollListenerAttachedTo === recycler) return
            scrollListenerAttachedTo = recycler
            // Radius sudut bar dibaca dari background yang sedang dipakai
            // (disimpan ulang tiap refresh lewat tag).
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    refreshBlur(bar, currentRadiusDp)
                }
            })
        } catch (_: Throwable) {
            // Scroll listener opsional; blur tetap jalan tanpa update live.
        }
    }

    private fun findRecyclerView(root: ViewGroup): RecyclerView? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is RecyclerView) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
            }
        }
        return null
    }
}
