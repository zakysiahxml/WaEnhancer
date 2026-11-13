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
 * ikon gaya iOS (zky_ios_tab_*.png + selector zky_home_tab_*_selector_ios.xml).
 *
 * Cara kerja:
 * - hook loadAddMenuAndroidX (setelah menu item tab dibuat, ganti ikonnya).
 *   Setiap menu item yang id-nya dikenal sebagai tab langsung diganti
 *   ikonnya — tanpa flag ThreadLocal (mekanisme flag gagal diam-diam kalau
 *   pembuatan menu tidak terjadi di dalam pemanggilan loadIconTabMethod
 *   atau di thread yang sama, dan ikon tidak pernah berubah).
 * - Ikon diambil dari resource MODUL (FeatureLoader.moduleContext), bukan dari
 *   resource WA — jadi Utils.getID() (resource WA) TIDAK bisa dipakai di sini.
 *   MenuItem.setIcon(Drawable) dipakai, bukan setIcon(int).
 * - Selector XML (state_checked true/false) yang mengatur ikon outline saat
 *   tab tidak aktif dan ikon penuh saat aktif.
 *
 * Pemetaan tab:
 * - CHATS (200), STATUS (300), GROUPS (500) pakai konstanta SeparateGroup.
 * - Komunitas & Panggilan tidak punya konstanta di fork ini, jadi dipetakan
 *   lewat POSISI di daftar tab (urutan bawaan WA: Chat, Pembaruan, Komunitas,
 *   Panggilan; kalau "Pisahkan Grup" aktif, Grup disisipkan di index 1).
 *   Urutan dibaca dari loadTabListMethod; kalau belum tersedia, dipakai
 *   urutan kedatangan menu item (sama urutannya).
 * - Tab yang tidak dikenal dibiarkan berikon bawaan + dicatat lewat logDebug,
 *   jadi tidak akan crash.
 *
 * Catatan: file XML layout & smali dari paket komponen TIDAK dipakai di sini
 * (layout itu merujuk class yang tidak ada di WA resmi
 * di WA official — di-inflate di modul = crash). Yang dipakai ulang hanya
 * aset gambarnya (PNG + selector), logikanya ditulis ulang sebagai hook.
 */
class IosTabBar(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    @Volatile
    private var tabOrder: List<Int> = emptyList()

    /** Urutan tab sesuai kedatangan menu item (cadangan kalau tabOrder kosong). */
    @Volatile
    private var seenTabIds: List<Int> = emptyList()

    override fun doHook() {
        if (!xprefs.getBoolean("ios_tab_bar", false)) return

        // Tangkap urutan tab apa adanya (dipakai untuk memetakan
        // Komunitas/Panggilan yang tidak punya konstanta). Kegagalan di
        // sini tidak boleh mematikan hook ikon di bawah.
        try {
            val tabListMethod = Unobfuscator.loadTabListMethod(classLoader)
            tabListMethod.hook {
                after {
                    @Suppress("UNCHECKED_CAST")
                    tabOrder = result as? List<Int> ?: emptyList()
                }
            }
        } catch (e: Throwable) {
            log(e)
        }

        val menuAddAndroidX = try {
            Unobfuscator.loadAddMenuAndroidX(classLoader)
        } catch (e: Throwable) {
            log(e)
            return
        }

        menuAddAndroidX.hook {
            after {
                if (args.size <= 2) return@after
                val tabId = (args[1] as? Int)
                    ?: (args[1] as? Number)?.toInt()
                    ?: return@after
                // Catat urutan kedatangan tab (cadangan kalau tabOrder
                // kosong). Hanya id kecil seperti konstanta tab (200/300/
                // 500) — id resource R.id (angka besar) bukan id tab dan
                // tidak boleh menggeser urutan.
                if (tabId < 10000 && !seenTabIds.contains(tabId)) {
                    seenTabIds = seenTabIds + tabId
                }
                val menuItem = result as? MenuItem ?: return@after
                val selectorRes = selectorForTab(tabId) ?: return@after
                val drawable = loadModuleDrawable(selectorRes) ?: return@after
                menuItem.setIcon(drawable)
                logDebug("IosTabBar: ikon tab $tabId diganti")
            }
        }
    }

    private fun selectorForTab(tabId: Int): Int? {
        return when (tabId) {
            SeparateGroup.CHATS -> R.drawable.zky_home_tab_chats_selector_ios
            SeparateGroup.STATUS -> R.drawable.zky_home_tab_status_selector_ios
            SeparateGroup.GROUPS -> R.drawable.zky_home_tab_communities_selector_ios
            else -> selectorForTabByPosition(tabId)
        }
    }

    private fun selectorForTabByPosition(tabId: Int): Int? {
        val order = tabOrder.ifEmpty { seenTabIds }
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
            communitiesIndex -> R.drawable.zky_home_tab_communities_selector_ios
            callsIndex -> R.drawable.zky_home_tab_calls_selector_ios
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
