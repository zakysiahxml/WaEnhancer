package com.wmods.wppenhacer.xposed.features.customization

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hookAll
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.utils.Utils

/**
 * Gradien Status Bar (fitur sendiri): status bar WA di SEMUA layar — beranda,
 * chat/conversation, pengaturan, dll — diwarnai gradien 2 warna (atas -> bawah)
 * yang dipilih sendiri lewat color picker bawaan.
 *
 * Cara kerja: Android cuma bisa kasih SATU warna ke status bar
 * (window.statusBarColor), jadi triknya: status bar dibuat transparan, lalu
 * sebuah View tipis setinggi status bar dengan background GradientDrawable
 * ditempel di atas decorView. View itu dicari lewat tag supaya tidak dobel.
 *
 * Hook-nya di Activity.onCreate + onResume SEMUA activity (pola yang sama
 * dipakai WppCore buat Conversation), jadi ikut di semua layar termasuk
 * Conversation. WA kadang menimpa warna status bar sendiri, makanya
 * di-apply ulang tiap onResume.
 *
 * Warna ikon status bar (jam, baterai) ikut disesuaikan dari kecerahan warna
 * atas: atas gelap -> ikon putih, atas terang -> ikon hitam.
 *
 * Color picker-nya TIDAK dibuat baru: dua warna gradien pakai komponen picker
 * bawaan yang sama (FeatureColorPreferenceCompat, seperti warna bubble) —
 * satu picker buat warna atas, satu lagi buat warna bawah.
 */
class StatusBarGradient(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    override fun doHook() {
        if (!xprefs.getBoolean("statusbar_gradient", false)) return

        val topColor = xprefs.getInt("statusbar_gradient_top", DEFAULT_TOP_COLOR)
        val bottomColor = xprefs.getInt("statusbar_gradient_bottom", DEFAULT_BOTTOM_COLOR)

        Activity::class.java.resolve().method {
            name = "onCreate"
        }.hookAll {
            after {
                applyGradient(instance as Activity, topColor, bottomColor)
            }
        }

        Activity::class.java.resolve().method {
            name = "onResume"
        }.hookAll {
            after {
                applyGradient(instance as Activity, topColor, bottomColor)
            }
        }
    }

    override fun getPluginName(): String {
        return "Status Bar Gradient"
    }

    private fun applyGradient(activity: Activity, topColor: Int, bottomColor: Int) {
        try {
            val window = activity.window
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.statusBarColor = Color.TRANSPARENT

            val decorView = window.decorView as ViewGroup
            val statusBarHeight = getStatusBarHeight(activity)

            var gradientView = decorView.findViewWithTag<View>(TAG_GRADIENT_VIEW)
            if (gradientView == null) {
                gradientView = View(activity).apply {
                    tag = TAG_GRADIENT_VIEW
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        statusBarHeight,
                        Gravity.TOP
                    )
                }
                decorView.addView(gradientView, 0)
            }

            gradientView.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(topColor, bottomColor)
            )

            applyIconColor(window, topColor)
        } catch (_: Exception) {
            // Diam saja kalau ada activity yang menolak (mis. layar fullscreen
            // kamera): fitur tidak boleh bikin WA crash.
        }
    }

    private fun applyIconColor(window: Window, topColor: Int) {
        val lightTop = isLightColor(topColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            val appearance = if (lightTop) {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            } else {
                0
            }
            controller.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            val decorView = window.decorView
            decorView.systemUiVisibility = if (lightTop) {
                decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val luminance = 0.2126 * Color.red(color) +
            0.7152 * Color.green(color) +
            0.0722 * Color.blue(color)
        return luminance > 128
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun getStatusBarHeight(activity: Activity): Int {
        val resourceId = activity.resources.getIdentifier(
            "status_bar_height", "dimen", "android"
        )
        return if (resourceId > 0) {
            activity.resources.getDimensionPixelSize(resourceId)
        } else {
            Utils.dipToPixels(24f)
        }
    }

    companion object {
        private const val TAG_GRADIENT_VIEW = "waenhancer_statusbar_gradient"
        private const val DEFAULT_TOP_COLOR = 0xFF075E54.toInt() // teal gelap WA
        private const val DEFAULT_BOTTOM_COLOR = 0xFF128C7E.toInt() // teal WA
    }
}
