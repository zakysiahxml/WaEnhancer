package com.wmods.wppenhacer.xposed.features.customization

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import java.util.WeakHashMap

/**
 * Header Chat Kaca (fitur sendiri, gaya iOS): toolbar layar percakapan
 * (Conversation) diubah jadi pil kaca mengambang — sudut membulat, latar
 * semi-transparan (frosted), ada jarak dari tepi layar dan dari status bar,
 * plus bayangan halus. Ini yang bikin header di screenshot referensi terlihat
 * "rapi bagus": bukan lagi bar penuh selebar layar, tapi satu pil yang
 * mengambang di atas wallpaper chat.
 *
 * Cara kerja — meniru FloatingBottomBar (yang melakukan hal yang sama untuk
 * bar bawah):
 * - hook Conversation.onCreate (activity percakapan WA = com.whatsapp.Conversation,
 *   nama class ini tidak diobfuscate, sama seperti yang dipakai
 *   WppCore.getCurrentConversation()).
 * - cari toolbar lewat id "toolbar" (id yang sama dipakai CustomToolbar
 *   untuk toolbar beranda), lalu:
 *   - parent AppBarLayout (kalau ada) dibuat transparan + elevation 0,
 *     supaya latar solid di belakang pil tidak kelihatan;
 *   - toolbar diberi background GradientDrawable membulat (radius 28dp,
 *     warna diambil dari warna toolbar sekarang dengan alpha 0.85,
 *     plus stroke tipis) + elevation buat bayangan;
 *   - toolbar diberi margin kiri/kanan 12dp dan atas 8dp supaya mengambang.
 * - guard: kalau WA menimpa background toolbar belakangan, listener
 *   layout-change mengembalikannya ke drawable kaca yang sama.
 *
 * Yang TIDAK dilakukan di v1 (detail iOS lain dari screenshot):
 * - angka "< 40" di sebelah tombol back (butuh TextView baru + hitung
 *   unread, rawan posisi) dan ring hijau di avatar (butuh bungkus
 *   ImageView) — keduanya dicatat buat lanjutkan kalau diminta.
 * - bar input pesan di bawah TIDAK diubah.
 *
 * Jujur: kode ini belum dikompilasi di sini; bukti pertama adalah build
 * GitHub Actions yang hijau + header berubah di HP. Tes hanya di WA
 * companion Redmi, jangan di nomor utama.
 */
class GlassChatHeader(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        private const val CORNER_RADIUS_DP = 28f
        private const val SIDE_MARGIN_DP = 12f
        private const val TOP_MARGIN_DP = 8f
        private const val ELEVATION_DP = 4f
        private const val BACKGROUND_ALPHA = 0.85f
        private const val STROKE_ALPHA = 0.40f
    }

    private val glassDrawables = WeakHashMap<ViewGroup, GradientDrawable>()
    private val guardInstalled = WeakHashMap<ViewGroup, Boolean>()

    override fun doHook() {
        if (!xprefs.getBoolean("glass_chat_header", false)) return

        val conversationClass = try {
            ReflectionUtils.findClass("com.whatsapp.Conversation", classLoader)
        } catch (e: Throwable) {
            log(e)
            return
        }

        conversationClass.resolve().firstMethod {
            name = "onCreate"
            superclass()
            parameters(Bundle::class.java)
        }.hook {
            after {
                val activity = instance as? Activity ?: return@after
                activity.window?.decorView?.post { styleHeader(activity) }
            }
        }
    }

    override fun getPluginName(): String {
        return "Glass Chat Header"
    }

    private fun styleHeader(activity: Activity) {
        try {
            val toolbar = activity.findViewById<ViewGroup>(
                Utils.getID("toolbar", "id")
            ) ?: return
            applyGlass(toolbar)
        } catch (e: Throwable) {
            log(e)
        }
    }

    private fun applyGlass(toolbar: ViewGroup) {
        try {
            // AppBarLayout (pembungkus toolbar di Conversation) dibuat
            // transparan supaya latar solidnya tidak kelihatan di belakang pil.
            var ancestor = toolbar.parent as? ViewGroup
            var depth = 0
            while (ancestor != null && depth < 3) {
                ancestor.clipChildren = false
                ancestor.clipToPadding = false
                if (ancestor.javaClass.simpleName.contains("AppBarLayout")) {
                    ancestor.setBackgroundColor(Color.TRANSPARENT)
                    ancestor.elevation = 0f
                }
                ancestor = ancestor.parent as? ViewGroup
                depth++
            }

            val glass = glassDrawables[toolbar] ?: buildGlassDrawable(toolbar).also {
                glassDrawables[toolbar] = it
            }
            toolbar.background = glass
            toolbar.outlineProvider = ViewOutlineProvider.BACKGROUND
            toolbar.elevation = Utils.dipToPixels(ELEVATION_DP).toFloat()

            val sideMargin = Utils.dipToPixels(SIDE_MARGIN_DP)
            val topMargin = Utils.dipToPixels(TOP_MARGIN_DP)
            val params = toolbar.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null && (params.leftMargin != sideMargin
                        || params.topMargin != topMargin
                        || params.rightMargin != sideMargin
                        || params.bottomMargin != 0)
            ) {
                params.setMargins(sideMargin, topMargin, sideMargin, 0)
                toolbar.layoutParams = params
            }

            installBackgroundGuard(toolbar, glass)
        } catch (e: Throwable) {
            log(e)
        }
    }

    private fun buildGlassDrawable(toolbar: ViewGroup): GradientDrawable {
        val barColor = resolveBarColor(toolbar)
        val glassColor = (barColor and 0x00FFFFFF) or
            ((BACKGROUND_ALPHA * 255).toInt() shl 24)
        val strokeColor = if (isLightColor(barColor)) {
            Color.argb((STROKE_ALPHA * 255).toInt(), 0, 0, 0)
        } else {
            Color.argb((STROKE_ALPHA * 255).toInt(), 255, 255, 255)
        }
        val radius = Utils.dipToPixels(CORNER_RADIUS_DP).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                radius, radius, radius, radius,
                radius, radius, radius, radius
            )
            setColor(glassColor)
            setStroke(Utils.dipToPixels(1f), strokeColor)
        }
    }

    // Kalau WA menimpa background toolbar belakangan (mis. ganti tema),
    // kembalikan ke drawable kaca yang sama. Satu listener per toolbar.
    private fun installBackgroundGuard(toolbar: ViewGroup, glass: GradientDrawable) {
        if (guardInstalled.put(toolbar, true) != null) return
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (toolbar.background !== glass) {
                toolbar.background = glass
            }
        }
    }

    private fun resolveBarColor(bar: ViewGroup): Int {
        val currentBg = bar.background
        if (currentBg is ColorDrawable) {
            return currentBg.color
        }
        return try {
            val typedValue = TypedValue()
            bar.context.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
            typedValue.data
        } catch (_: Throwable) {
            0xFF121212.toInt()
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return (red * 299 + green * 587 + blue * 114) / 1000 > 180
    }
}
