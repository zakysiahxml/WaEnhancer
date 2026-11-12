package com.wmods.wppenhacer.xposed.features.customization

import android.content.SharedPreferences
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.ImageView
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.FeatureLoader
import com.wmods.wppenhacer.xposed.core.ViewHookDispatcher
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.Utils
import java.util.WeakHashMap

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
 * - Penegakan di level VIEW: WA/WDS menimpa ikon tab yang sedang dipilih
 *   dengan ikon bawaannya sendiri di level view (di HP Ajen, tab Chat aktif
 *   menampilkan gelembung bergaris, bukan selector) — swap di MenuItem saja
 *   tidak cukup. enforceTabIcons() memasang ulang drawable ikon langsung ke
 *   ImageView tiap item setiap kali layout bar berubah, dengan state
 *   checked/unchecked di-resolve eksplisit. Foto profil tab "Anda" tidak
 *   ditimpa (drawable bitmap WA dilewati).
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

    /** Bar yang sudah dipasangi listener penegakan ikon (hindari dobel). */
    private val iconGuardedBars = WeakHashMap<ViewGroup, Boolean>()

    /** Drawable ikon yang sudah ditegakkan per ImageView (cek identitas). */
    private val enforcedIcons = WeakHashMap<ImageView, Drawable>()

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

        // Penegakan ikon di level view (lihat catatan di doc class).
        try {
            val bottomNavId = Utils.getID("bottom_nav", "id")
            if (bottomNavId > 0) {
                ViewHookDispatcher.onAttachedToWindow(this) { view ->
                    if (view.id != bottomNavId) return@onAttachedToWindow
                    val bar = view as? ViewGroup ?: return@onAttachedToWindow
                    if (iconGuardedBars.put(bar, true) != null) {
                        bar.post { enforceTabIcons(bar) }
                        return@onAttachedToWindow
                    }
                    bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        enforceTabIcons(bar)
                    }
                    bar.post { enforceTabIcons(bar) }
                }
            }
        } catch (e: Throwable) {
            log(e)
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

    /**
     * Pasangan drawable (unchecked, checked) untuk sebuah tab — pasangan yang
     * sama dengan yang dipakai selector XML-nya, supaya hasil akhir jalur
     * view ini identik dengan jalur MenuItem.
     */
    private fun tabIconPair(tabId: Int): Pair<Int, Int>? {
        return when (selectorForTab(tabId)) {
            R.drawable.zky_home_tab_chats_selector_ios ->
                R.drawable.zky_ios_tab_chat to R.drawable.zky_ios_tab_selected_chat
            R.drawable.zky_home_tab_status_selector_ios ->
                R.drawable.zky_ios_tab_status to R.drawable.zky_ios_tab_selected_status
            R.drawable.zky_home_tab_communities_selector_ios ->
                R.drawable.zky_ios_tab_group to R.drawable.zky_ios_tab_selected_group
            R.drawable.zky_home_tab_calls_selector_ios ->
                R.drawable.zky_ios_tab_call to R.drawable.zky_ios_tab_selected_call
            else -> null
        }
    }

    /**
     * Tegakkan ikon tiap tab langsung di ImageView-nya. Dijalankan setiap
     * kali layout bar berubah (termasuk saat ganti tab), supaya ikon bawaan
     * WA yang ditimpa ke tab aktif selalu diganti kembali dengan ikon iOS.
     */
    private fun enforceTabIcons(bar: ViewGroup) {
        try {
            val menuView = findMenuView(bar) ?: return
            val order = tabOrder.ifEmpty { seenTabIds }
            for (i in 0 until menuView.childCount) {
                val item = menuView.getChildAt(i) as? ViewGroup ?: continue
                val tabId = order.getOrNull(i) ?: continue
                val (uncheckedRes, checkedRes) = tabIconPair(tabId) ?: continue
                val iconView = findIconView(item) ?: continue
                val current = iconView.drawable
                // Sudah drawable kita: jangan setel ulang (hindari loop layout).
                if (enforcedIcons[iconView] === current) continue
                // Foto profil tab "Anda" jangan ditimpa: fotonya BitmapDrawable
                // yang dipasang WA di level view, bukan dari MenuItem.
                if (current is BitmapDrawable) {
                    logDebug("IosTabBar: tab $tabId dilewati (drawable bitmap, kemungkinan foto profil)")
                    continue
                }
                val isChecked = (item as? Checkable)?.isChecked == true
                val res = if (isChecked) checkedRes else uncheckedRes
                val drawable = loadModuleDrawable(res) ?: continue
                iconView.setImageDrawable(drawable)
                enforcedIcons[iconView] = drawable
                logDebug("IosTabBar: ikon view tab $tabId ditegakkan (checked=$isChecked)")
            }
        } catch (e: Throwable) {
            log(e)
        }
    }

    private fun findMenuView(bar: ViewGroup): ViewGroup? {
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i) as? ViewGroup ?: continue
            if (child.javaClass.simpleName.contains("MenuView")) return child
        }
        return bar.getChildAt(0) as? ViewGroup
    }

    /**
     * Ikon tab = ImageView terbesar di dalam item (badge berupa TextView
     * angka, jadi tidak ikut kehitung). Dicari sampai kedalaman 3.
     */
    private fun findIconView(item: ViewGroup): ImageView? {
        var best: ImageView? = null
        var bestArea = 0
        val queue = ArrayDeque<Pair<View, Int>>()
        queue.add(item to 0)
        while (queue.isNotEmpty()) {
            val (view, depth) = queue.removeFirst()
            if (view is ImageView) {
                val area = view.width * view.height
                if (view.width > 0 && view.height > 0 && area > bestArea) {
                    best = view
                    bestArea = area
                }
            }
            if (view is ViewGroup && depth < 3) {
                for (i in 0 until view.childCount) queue.add(view.getChildAt(i) to depth + 1)
            }
        }
        return best
    }

    override fun getPluginName(): String {
        return "iOS Tab Bar"
    }
}
