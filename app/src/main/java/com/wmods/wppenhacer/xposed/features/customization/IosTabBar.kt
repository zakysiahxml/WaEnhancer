package com.wmods.wppenhacer.xposed.features.customization

import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.view.MenuItem
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.FeatureLoader
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator

/**
 * Tab Bar iOS (tier 3, bagian 1): ganti ikon tab beranda WA dengan ikon iOS
 * dari paket komponen DELTA (delta_ios_tab_*.png + selector home_tab_*_selector_ios.xml).
 *
 * Cara kerja — meniru SeparateGroup.hookTabIcon():
 * - hook loadIconTabMethod (flag ON sebelum, OFF sesudah) + loadAddMenuAndroidX
 *   (setelah menu item tab dibuat, ganti ikonnya).
 * - Ikon diambil dari resource MODUL (FeatureLoader.moduleContext), bukan dari
 *   resource WA — jadi Utils.getID() (resource WA) TIDAK bisa dipakai di sini.
 *   MenuItem.setIcon(Drawable) dipakai, bukan setIcon(int).
 * - Selector XML (state_checked true/false) yang mengatur ikon abu-abu saat
 *   tab tidak aktif dan biru saat aktif — sama seperti selector bawaan WA.
 *
 * Pemetaan tab:
 * - CHATS (200), STATUS (300), GROUPS (500) pakai konstanta SeparateGroup.
 * - Komunitas & Panggilan tidak punya konstanta di fork ini, jadi dipetakan
 *   lewat POSISI di daftar tab (urutan bawaan WA: Chat, Pembaruan, Komunitas,
 *   Panggilan; kalau "Pisahkan Grup" aktif, Grup disisipkan di index 1).
 * - Tab yang tidak dikenal dibiarkan berikon bawaan + dicatat lewat logDebug,
 *   jadi tidak akan crash.
 *
 * Catatan: file XML layout & smali dari paket DELTA TIDAK dipakai di sini
 * (layout itu merujuk class id.deltalabs.* / com.whatsapp.yo.* yang tidak ada
 * di WA official — di-inflate di modul = crash). Yang dipakai ulang hanya
 * aset gambarnya (PNG + selector), logikanya ditulis ulang sebagai hook.
 */
class IosTabBar(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    private val useIosIcons = ThreadLocal.withInitial { false }

    @Volatile
    private var tabOrder: List<Int> = emptyList()

    override fun doHook() {
        if (!xprefs.getBoolean("ios_tab_bar", false)) return

        // Tangkap urutan tab apa adanya (dipakai untuk memetakan
        // Komunitas/Panggilan yang tidak punya konstanta).
        val tabListMethod = Unobfuscator.loadTabListMethod(classLoader)
        tabListMethod.hook {
            after {
                @Suppress("UNCHECKED_CAST")
                tabOrder = result as? List<Int> ?: emptyList()
            }
        }

        val iconTabMethod = Unobfuscator.loadIconTabMethod(classLoader)
        val menuAddAndroidX = Unobfuscator.loadAddMenuAndroidX(classLoader)

        menuAddAndroidX.hook {
            after {
                if (useIosIcons.get() != true) return@after
                if (args.size <= 2) return@after
                val menuItem = result as? MenuItem ?: return@after
                val tabId = args[1] as Int
                val selectorRes = selectorForTab(tabId) ?: return@after
                val drawable = loadModuleDrawable(selectorRes) ?: return@after
                menuItem.setIcon(drawable)
            }
        }

        iconTabMethod.hook {
            before {
                useIosIcons.set(true)
            }
            after {
                useIosIcons.remove()
            }
        }
    }

    private fun selectorForTab(tabId: Int): Int? {
        return when (tabId) {
            SeparateGroup.CHATS -> R.drawable.home_tab_chats_selector_ios
            SeparateGroup.STATUS -> R.drawable.home_tab_status_selector_ios
            SeparateGroup.GROUPS -> R.drawable.home_tab_communities_selector_ios
            else -> selectorForTabByPosition(tabId)
        }
    }

    private fun selectorForTabByPosition(tabId: Int): Int? {
        val order = tabOrder
        val index = order.indexOf(tabId)
        if (index == -1) {
            logDebug("IosTabBar: tab id $tabId tidak ada di daftar tab, ikon dibiarkan bawaan")
            return null
        }
        // Kalau "Pisahkan Grup" aktif, Grup (500) disisipkan di index 1,
        // sehingga Komunitas geser ke index 3 dan Panggilan ke index 4.
        // Kalau tidak aktif, Komunitas di index 2 dan Panggilan di index 3.
        val hasGroups = order.contains(SeparateGroup.GROUPS)
        val communitiesIndex = if (hasGroups) 3 else 2
        val callsIndex = if (hasGroups) 4 else 3
        return when (index) {
            communitiesIndex -> R.drawable.home_tab_communities_selector_ios
            callsIndex -> R.drawable.home_tab_calls_selector_ios
            else -> {
                logDebug("IosTabBar: tab id $tabId (index $index) tidak dikenal, ikon dibiarkan bawaan")
                null
            }
        }
    }

    private fun loadModuleDrawable(resId: Int): Drawable? {
        return try {
            FeatureLoader.moduleContext.getDrawable(resId)
        } catch (e: Throwable) {
            log(e)
            null
        }
    }

    override fun getPluginName(): String {
        return "iOS Tab Bar"
    }
}
