package com.wmods.wppenhacer.xposed.features.customization

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.FeatureLoader
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import java.util.WeakHashMap

/**
 * Header Chat Kaca v2 — gaya iOS, sama persis kayak screenshot referensi:
 * header BUKAN satu bar penuh, tapi TIGA pil kaca mengambang yang terpisah
 * (ada PEMISAH / gap di antaranya):
 *
 *   [ < 40 ]   [ (avatar) Nama / dilihat ... ]   [ [video] [telpon] ]
 *   pil back    pil nama                          pil aksi
 *
 * - tiap pil = Liquid Glass beneran (library QWEA0/Liquid-Glass-Android,
 *   MIT — refraksi SDF + dispersi + specular; bukan blur bitmap),
 *   warna blur abu-abu gelap kebiruan (tint #2E3B42 alpha 0.40),
 *   sudut membulat, tanpa outline putih yang keras; kalau library gagal
 *   (class/native tidak ke-load), fallback ke GlassBarBlur;
 * - avatar dikasih RING HIJAU (WhatsApp green #25D366);
 * - pil back berisi panah back + angka unread ("40" di referensi).
 *
 * Cara kerja:
 * - hook Conversation.onCreate; sesudah layout, toolbar dicari lewat id
 *   "toolbar", lalu anak-anaknya dipindah ke tiga pil baru (re-parenting).
 *   Click listener ikut pindah bareng view-nya, jadi tombol back / video /
 *   telpon tetap jalan.
 * - kalau restrukturisasi gagal di titik mana pun (struktur toolbar beda),
 *   fallback ke v1: toolbar utuh dijadikan satu pil kaca (cara lama).
 *
 * Angka unread di pil back ("40"): dicoba dibaca dari jumlah chat belum
 * dibaca. Kalau tidak ketemu sumbernya, pil back cuma berisi panah back
 * (tanpa angka) — struktur tiga pil + blur + ring hijau tetap jalan.
 *
 * Jujur: kode ini belum dikompilasi di sini; bukti pertama adalah build
 * GitHub Actions yang hijau + header berubah di HP. Tes hanya di WA
 * companion Redmi, jangan di nomor utama.
 */
class GlassChatHeader(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        // Pil: radius + jarak antar pil (PEMISAH) + margin tepi.
        private const val PILL_RADIUS_DP = 28f
        private const val PILL_GAP_DP = 8f
        private const val SIDE_MARGIN_DP = 12f
        private const val TOP_MARGIN_DP = 8f
        // Pil back (lingkaran): diameter.
        private const val BACK_PILL_SIZE_DP = 52f
        // Ring hijau avatar.
        private const val AVATAR_RING_WIDTH_DP = 3f
        private const val AVATAR_RING_COLOR = 0xFF25D366.toInt()
        // Warna fallback kalau blur gagal (semi-transparan, bukan hitam polos).
        private const val FALLBACK_BG_COLOR = 0xB31E2A32.toInt()
        // v1 (fallback): konstanta lama.
        private const val V1_CORNER_RADIUS_DP = 28f
        private const val V1_SIDE_MARGIN_DP = 12f
        private const val V1_TOP_MARGIN_DP = 8f
        private const val V1_ELEVATION_DP = 4f
        private const val V1_BACKGROUND_ALPHA = 0.85f
        private const val V1_STROKE_ALPHA = 0.40f
    }

    private val styledToolbars = WeakHashMap<ViewGroup, Boolean>()
    private val styleAttempts = WeakHashMap<ViewGroup, Int>()
    private val v2Applied = WeakHashMap<ViewGroup, Boolean>()

    override fun doHook() {
        if (!xprefs.getBoolean("glass_chat_header", false)) return

        val conversationClass = try {
            ReflectionUtils.findClass("com.whatsapp.Conversation", classLoader)
        } catch (e: Throwable) {
            log(e)
            return
        }

        // onCreate: pasang sekali. onResume: pasang lagi kalau toolbar
        // belum siap waktu onCreate (view Fragment kadang telat nempel).
        // styledToolbars mencegah styling ganda.
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

        conversationClass.resolve().firstMethod {
            name = "onResume"
            superclass()
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
            if (styledToolbars[toolbar] == true) return
            // Toolbar belum punya anak (WA belum selesai menatanya):
            // JANGAN di-guard dan JANGAN fallback ke v1 — coba lagi
            // sebentar lagi. Kalau langsung fallback di sini, guard
            // menutup semua percobaan ulang dan header terjebak di v1.
            if (toolbar.childCount == 0) {
                val attempts = styleAttempts.getOrDefault(toolbar, 0)
                if (attempts < 3) {
                    styleAttempts[toolbar] = attempts + 1
                    toolbar.postDelayed({ styleHeader(activity) }, 400L)
                }
                return
            }
            // Coba v2 (tiga pil). Gagal -> v1 (satu pil).
            if (tryRestructureToPills(activity, toolbar)) {
                styledToolbars[toolbar] = true
                v2Applied[toolbar] = true
            } else {
                applyGlassV1(toolbar)
                styledToolbars[toolbar] = true
            }
        } catch (e: Throwable) {
            log(e)
        }
    }

    // ------------------------------------------------------------------
    // v2 — tiga pil terpisah (ada pemisah / gap di antaranya).
    // ------------------------------------------------------------------

    private fun tryRestructureToPills(activity: Activity, toolbar: ViewGroup): Boolean {
        return try {
            // Toolbar jadi wadah transparan (latar + elevation dimatikan).
            toolbar.setBackgroundColor(Color.TRANSPARENT)
            toolbar.elevation = 0f
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

            // Kumpulkan anak-anak toolbar sebelum dipindah.
            val children = ArrayList<View>()
            for (i in 0 until toolbar.childCount) children.add(toolbar.getChildAt(i))
            if (children.isEmpty()) return false

            val navButton = findNavButton(toolbar)
            val avatarView = findAvatarView(toolbar)
            val titleViews = findTitleViews(toolbar)
            val actionMenu = findActionMenu(toolbar)
            logDebug("GlassChatHeader: anak=${children.size} nav=${navButton != null} avatar=${avatarView != null} judul=${titleViews.size} menu=${actionMenu != null}")
            // Minimal harus ada tombol back + judul. Kalau tidak ada, v1 saja.
            if (navButton == null && titleViews.isEmpty()) {
                logDebug("GlassChatHeader: back + judul tidak ketemu, pakai v1")
                return false
            }

            // Backdrop buat kaca = RecyclerView chat di belakang header.
            val backdrop = activity.window?.decorView?.let { findRecyclerView(it) }

            // Tiap pil dibangun terpisah: satu pil gagal tidak boleh
            // menggagalkan pil lainnya.
            var backPill: ViewGroup? = null
            var namePill: ViewGroup? = null
            var actionPill: ViewGroup? = null
            try {
                // Pil 1 — back (lingkaran): panah back + angka unread.
                backPill = buildBackPill(activity, navButton, backdrop)
            } catch (e: Throwable) {
                log(e)
            }
            try {
                // Pil 2 — nama: avatar (ring hijau) + nama + last seen.
                namePill = buildNamePill(activity, avatarView, titleViews, backdrop)
            } catch (e: Throwable) {
                log(e)
            }
            try {
                // Pil 3 — aksi: tombol video + telpon (+ overflow kalau ada).
                actionPill = buildActionPill(activity, actionMenu, backdrop)
            } catch (e: Throwable) {
                log(e)
            }
            val pills = listOfNotNull(backPill, namePill, actionPill)
            if (pills.size < 2) {
                logDebug("GlassChatHeader: hanya ${pills.size} pil berhasil, pakai v1")
                return false
            }

            // Lepas semua anak lama dari toolbar, ganti dengan container
            // horizontal berisi tiga pil + pemisah (gap) di antaranya.
            toolbar.removeAllViews()
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setPadding(
                    Utils.dipToPixels(SIDE_MARGIN_DP),
                    Utils.dipToPixels(TOP_MARGIN_DP),
                    Utils.dipToPixels(SIDE_MARGIN_DP),
                    0
                )
            }
            val gapPx = Utils.dipToPixels(PILL_GAP_DP)
            for ((index, pill) in pills.withIndex()) {
                if (index > 0) addGap(container, gapPx)
                container.addView(pill)
            }
            // Sisa view yang tidak dikenal (kalau ada) jangan dibuang:
            // taruh di akhir container supaya tidak hilang.
            val used = HashSet<View>()
            if (navButton != null) used.add(navButton)
            if (avatarView != null) used.add(avatarView)
            titleViews.forEach { used.add(it) }
            if (actionMenu != null) used.add(actionMenu)
            for (child in children) {
                // Container yang sudah dikosongkan (isinya pindah ke pil,
                // mis. conversation_contact / contact_photo_view) jangan
                // ikut ditambahkan — cuma makan tempat.
                if (child is ViewGroup && child.childCount == 0) continue
                if (child !in used && child.parent == null) {
                    container.addView(child)
                }
            }
            toolbar.addView(container)

            // Pil yang BUKAN LiquidGlassView (fallback) dikasih blur
            // bitmap sendiri; pil Liquid Glass sudah ngegambar sendiri.
            for (pill in pills) {
                if (pill is LiquidGlassView) continue
                pill.post {
                    val ok = GlassBarBlur.applyBlur(pill, PILL_RADIUS_DP)
                    if (!ok) pill.background = buildFallbackPillBg()
                }
            }
            true
        } catch (e: Throwable) {
            log(e)
            false
        }
    }

    private fun addGap(container: LinearLayout, gapPx: Int) {
        container.addView(View(container.context).apply {
            layoutParams = LinearLayout.LayoutParams(gapPx, 1)
        })
    }

    /** Cari depth-first (maks 4 level): struktur toolbar WA bersarang. */
    private fun findFirstRecursive(root: ViewGroup, depth: Int, match: (View) -> Boolean): View? {
        if (depth > 4) return null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (match(child)) return child
            if (child is ViewGroup) {
                val found = findFirstRecursive(child, depth + 1, match)
                if (found != null) return found
            }
        }
        return null
    }

    /** Kumpulkan semua yang cocok, tanpa masuk ke dalam menu aksi. */
    private fun collectRecursive(root: ViewGroup, depth: Int, out: MutableList<View>, match: (View) -> Boolean) {
        if (depth > 4) return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (match(child)) out.add(child)
            if (child is ViewGroup && !isActionMenu(child)) {
                collectRecursive(child, depth + 1, out, match)
            }
        }
    }

    /**
     * Cari view dari [toolbar] berdasarkan NAMA id resource WA (rekursif,
     * pakai findViewById). Id dicari dulu di dalam toolbar, kalau tidak
     * ketemu dicari di parent-nya (holder) untuk jaga-jaga.
     * Id-id ini VERIFIED dari hierarchy dump di HP Ajen (WA 2.26.41.77).
     */
    private fun findByWaId(toolbar: ViewGroup, name: String): View? {
        val resId = Utils.getID(name, "id")
        if (resId == 0) return null
        toolbar.findViewById<View>(resId)?.let { return it }
        val parent = toolbar.parent as? ViewGroup
        return parent?.findViewById<View>(resId)
    }

    /**
     * Tombol back = id whatsapp_toolbar_home (ImageView di WA 2.26.41,
     * BUKAN ImageButton — itu sebabnya pencarian lama selalu gagal).
     * Cadangan: ImageButton pertama (rekursif, bukan di dalam menu aksi).
     */
    private fun findNavButton(toolbar: ViewGroup): View? {
        findByWaId(toolbar, "whatsapp_toolbar_home")?.let { return it }
        // Anak langsung dulu (tombol back biasanya anak pertama toolbar).
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is ImageButton) return child
        }
        // Lalu cari di dalam container lain (bukan menu aksi).
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is ViewGroup && !isActionMenu(child)) {
                val found = findFirstRecursive(child, 1) { it is ImageButton }
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Avatar = id conversation_contact_photo (di dalam FrameLayout
     * contact_photo_view). Cadangan: ImageView pertama yang BUKAN tombol
     * back (tombol back juga ImageView — jangan sampai ketukar).
     */
    private fun findAvatarView(toolbar: ViewGroup): ImageView? {
        (findByWaId(toolbar, "conversation_contact_photo") as? ImageView)?.let { return it }
        val navButton = findNavButton(toolbar)
        val out = ArrayList<View>()
        collectRecursive(toolbar, 0, out) { it is ImageView && it !is ImageButton }
        return out.firstOrNull { it !== navButton } as? ImageView
    }

    /**
     * Judul + subtitle = semua TextView di dalam id conversation_contact
     * (container nama + status). Cadangan: semua TextView di luar menu aksi.
     */
    private fun findTitleViews(toolbar: ViewGroup): List<TextView> {
        val contact = findByWaId(toolbar, "conversation_contact") as? ViewGroup
        if (contact != null) {
            val out = ArrayList<View>()
            collectRecursive(contact, 0, out) { it is TextView }
            val titles = out.filterIsInstance<TextView>()
            if (titles.isNotEmpty()) return titles
        }
        val out = ArrayList<View>()
        collectRecursive(toolbar, 0, out) { it is TextView }
        return out.filterIsInstance<TextView>()
    }

    /**
     * Menu aksi (video / telpon / overflow) = parent dari id
     * menuitem_overflow. Container-nya sendiri TIDAK punya id dan class-nya
     * obfuscated (bukan ActionMenuView) — jadi diambil lewat overflow.
     * Cadangan: ViewGroup bernama *ActionMenu*.
     */
    private fun findActionMenu(toolbar: ViewGroup): ViewGroup? {
        val overflow = findByWaId(toolbar, "menuitem_overflow")
        (overflow?.parent as? ViewGroup)?.let { return it }
        return findFirstRecursive(toolbar, 0) { isActionMenu(it) } as? ViewGroup
    }

    private fun isActionMenu(view: View): Boolean {
        val name = view.javaClass.simpleName
        return name.contains("ActionMenuView") || name.contains("ActionMenu")
    }

    /**
     * Bikin wadah pil kaca. Utamanya LiquidGlassView (library QWEA0,
     * refraksi + dispersi + specular beneran); kalau gagal (class tidak
     * ada / native lib gagal), fallback ke FrameLayout polos (nanti
     * dikasih GlassBarBlur oleh pemanggil).
     */
    private fun makeGlassPill(
        activity: Activity,
        backdrop: RecyclerView?,
        radiusDp: Float
    ): ViewGroup {
        try {
            return LiquidGlassView(activity).apply {
                cornerRadius = Utils.dipToPixels(radiusDp).toFloat()
                material = GlassMaterial.CLEAR
                enableDynamicBackground = true
                enableSensorHighlight = true
                enableAdaptiveTint = true
                // Kaca harus TEMBUS seperti referensi: tint terang tipis.
                setGlassTint(0xFF9AA7B2.toInt(), 0.15f)
                backdropSource = backdrop
                clipChildren = false
                clipToPadding = false
            }
        } catch (e: Throwable) {
            log(e)
            return FrameLayout(activity).apply {
                clipChildren = false
                clipToPadding = false
            }
        }
    }

    /**
     * Cari RecyclerView TERBAIK sebagai sumber backdrop kaca — BUKAN yang
     * pertama ketemu. First-match BFS pernah mengambil RecyclerView yang
     * salah (kosong/tersembunyi) sehingga ketiga pil cuma transparan tanpa
     * blur: batas antar pil tidak kelihatan, dan ikon VC/telpon seolah
     * menyatu dengan pil nama. Sama persis dengan cacat backdrop bar bawah.
     * Kriteria: harus VISIBLE + sudah ter-layout + punya anak; pemenang =
     * area terbesar (list pesan selalu yang terbesar di layar chat).
     */
    private fun findRecyclerView(root: View): RecyclerView? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var best: RecyclerView? = null
        var bestArea = 0
        var found = 0
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is RecyclerView) {
                found++
                val area = view.width * view.height
                val usable = view.visibility == View.VISIBLE &&
                        view.width > 0 && view.height > 0 &&
                        view.childCount > 0
                if (usable && area > bestArea) {
                    best = view
                    bestArea = area
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
            }
        }
        logDebug(
            "GlassChatHeader: backdrop scan: $found RecyclerView, " +
                    "pilih=${best?.javaClass?.simpleName} " +
                    "${best?.width}x${best?.height} anak=${best?.childCount}"
        )
        return best
    }

    /** Pil 1 — back: lingkaran kaca berisi panah back + angka unread. */
    /**
     * Kembalikan view yang dipindah ke kondisi tampil normal.
     *
     * WA menganimasikan avatar + nama/status (fade-in) saat chat dibuka.
     * removeView() membatalkan animator yang sedang berjalan sehingga
     * view-nya tertinggal dalam keadaan transparan / tidak terlihat
     * (alpha 0) di parent barunya — persis gejala di HP Ajen (pil nama
     * kosong, pil back + aksi normal karena tidak ikut animasi itu).
     * Setiap view yang dipindah harus dilewatkan sini.
     */
    private fun reviveView(view: View) {
        try {
            view.clearAnimation()
            view.alpha = 1f
            view.visibility = View.VISIBLE
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.rotation = 0f
        } catch (e: Throwable) {
            log(e)
        }
    }

    /** Muat drawable dari resource MODUL (bukan resource WA). */
    private fun loadModuleDrawable(resId: Int): Drawable? {
        return try {
            FeatureLoader.moduleContext.getDrawable(resId)
        } catch (e: Throwable) {
            log(e)
            null
        }
    }

    private fun buildBackPill(
        activity: Activity,
        navButton: View?,
        backdrop: RecyclerView?
    ): ViewGroup {
        val ctx = activity
        val size = Utils.dipToPixels(BACK_PILL_SIZE_DP)
        val pill = makeGlassPill(activity, backdrop, BACK_PILL_SIZE_DP / 2f).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        if (navButton != null) {
            (navButton.parent as? ViewGroup)?.removeView(navButton)
            reviveView(navButton)
            navButton.setBackgroundColor(Color.TRANSPARENT)
            val navSize = Utils.dipToPixels(24f)
            navButton.layoutParams = LinearLayout.LayoutParams(navSize, navSize)
            row.addView(navButton)
        }
        // Angka unread ("40" di referensi). Best-effort: kalau tidak
        // ketemu sumbernya, TextView dibiarkan kosong (hanya panah back).
        val countView = TextView(ctx).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Utils.dipToPixels(2f) }
        }
        row.addView(countView)
        pill.addView(row)
        // Isi angka unread belakangan (async, jangan blokir layout).
        pill.post { fillUnreadCount(activity, countView) }
        return pill
    }

    /** Pil 2 — nama: avatar (ring hijau) + nama + last seen. */
    private fun buildNamePill(
        activity: Activity,
        avatarView: ImageView?,
        titleViews: List<TextView>,
        backdrop: RecyclerView?
    ): ViewGroup {
        val ctx = activity
        // Wadah luar = kaca; isi horizontal di dalam LinearLayout.
        val pill = makeGlassPill(activity, backdrop, PILL_RADIUS_DP).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            )
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val padH = Utils.dipToPixels(10f)
            val padV = Utils.dipToPixels(6f)
            setPadding(padH, padV, padH, padV)
        }
        if (avatarView != null) {
            (avatarView.parent as? ViewGroup)?.removeView(avatarView)
            reviveView(avatarView)
            // Ring hijau di sekeliling avatar.
            avatarView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            avatarView.clipToOutline = true
            // Ring = background lingkaran hijau di belakang avatar,
            // avatar diberi padding selebar ring.
            val ringW = Utils.dipToPixels(AVATAR_RING_WIDTH_DP)
            val avatarHolder = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    Utils.dipToPixels(40f) + ringW * 2,
                    Utils.dipToPixels(40f) + ringW * 2
                )
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(AVATAR_RING_COLOR)
                }
            }
            avatarView.layoutParams = FrameLayout.LayoutParams(
                Utils.dipToPixels(40f), Utils.dipToPixels(40f),
                Gravity.CENTER
            )
            avatarHolder.addView(avatarView)
            row.addView(avatarHolder)
            val gap = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(Utils.dipToPixels(8f), 1)
            }
            row.addView(gap)
        }
        // Nama + last seen (vertikal).
        if (titleViews.isNotEmpty()) {
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            for (tv in titleViews) {
                (tv.parent as? ViewGroup)?.removeView(tv)
                reviveView(tv)
                tv.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                textCol.addView(tv)
            }
            row.addView(textCol)
        }
        // Jaring pengaman: kalau animasi WA yang datang terlambat
        // menyembunyikan lagi view yang sudah dipindah, paksa tampil.
        val movedNameViews = ArrayList<View>()
        if (avatarView != null) movedNameViews.add(avatarView)
        movedNameViews.addAll(titleViews)
        pill.postDelayed(Runnable {
            for (v in movedNameViews) {
                if (v.alpha < 0.99f || v.visibility != View.VISIBLE) reviveView(v)
            }
        }, 600)
        pill.addView(row)
        return pill
    }

    /** Pil 3 — aksi: tombol video + telpon (+ overflow kalau ada). */
    private fun buildActionPill(
        activity: Activity,
        actionMenu: ViewGroup?,
        backdrop: RecyclerView?
    ): ViewGroup? {
        if (actionMenu == null) return null
        val ctx = activity
        // Pindahkan tombol-tombol aksi dari ActionMenuView ke pil baru.
        val buttons = ArrayList<View>()
        for (i in 0 until actionMenu.childCount) buttons.add(actionMenu.getChildAt(i))
        if (buttons.isEmpty()) return null
        // Wadah luar = kaca; isi horizontal di dalam LinearLayout.
        val pill = makeGlassPill(activity, backdrop, PILL_RADIUS_DP).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val padH = Utils.dipToPixels(8f)
            val padV = Utils.dipToPixels(6f)
            setPadding(padH, padV, padH, padV)
        }
        // Ikon outline gaya referensi (video + telpon). Urutan bawaan WA:
        // ImageButton pertama = Video call, kedua = Voice call; overflow
        // adalah ImageView terpisah dan dibiarkan apa adanya supaya menu
        // chat tidak hilang.
        var imageButtonIndex = 0
        val actionBtnSize = Utils.dipToPixels(40f)
        for (btn in buttons) {
            (btn.parent as? ViewGroup)?.removeView(btn)
            reviveView(btn)
            if (btn is ImageButton) {
                btn.setBackgroundColor(Color.TRANSPARENT)
                val iconRes = when (imageButtonIndex) {
                    0 -> R.drawable.zky_action_video
                    1 -> R.drawable.zky_action_call
                    else -> 0
                }
                imageButtonIndex++
                if (iconRes != 0) {
                    loadModuleDrawable(iconRes)?.let { drawable ->
                        btn.scaleType = ImageView.ScaleType.FIT_CENTER
                        btn.setImageDrawable(drawable)
                    }
                }
                val p = LinearLayout.LayoutParams(actionBtnSize, actionBtnSize).apply {
                    leftMargin = Utils.dipToPixels(4f)
                    rightMargin = Utils.dipToPixels(4f)
                }
                btn.layoutParams = p
            } else {
                val p = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = Utils.dipToPixels(4f)
                    rightMargin = Utils.dipToPixels(4f)
                }
                btn.layoutParams = p
            }
            row.addView(btn)
        }
        pill.addView(row)
        return pill
    }

    /**
     * Isi angka unread di pil back. Best-effort: coba baca jumlah chat
     * belum dibaca lewat field yang umum dipakai WA. Kalau gagal, angka
     * dibiarkan kosong (pil back tetap berisi panah back saja).
     */
    private fun fillUnreadCount(activity: Activity, countView: TextView) {
        try {
            // Pendekatan generik: cari field int bernama unread / unreadCount
            // di activity / application. Kalau tidak ada, diam saja.
            val candidates = listOf("unreadCount", "mUnreadCount", "totalUnread")
            for (fieldName in candidates) {
                try {
                    val f = activity.javaClass.getDeclaredField(fieldName)
                    f.isAccessible = true
                    val v = f.getInt(activity)
                    if (v > 0) {
                        countView.text = v.toString()
                        return
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Background fallback pil (kalau blur gagal): warna semi-transparan. */
    private fun buildFallbackPillBg(): GradientDrawable {
        val radius = Utils.dipToPixels(PILL_RADIUS_DP).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                radius, radius, radius, radius,
                radius, radius, radius, radius
            )
            setColor(FALLBACK_BG_COLOR)
        }
    }

    // ------------------------------------------------------------------
    // v1 — fallback: toolbar utuh jadi satu pil kaca (cara lama).
    // ------------------------------------------------------------------

    private fun applyGlassV1(toolbar: ViewGroup) {
        try {
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
            val glass = buildGlassDrawableV1(toolbar)
            toolbar.background = glass
            toolbar.outlineProvider = ViewOutlineProvider.BACKGROUND
            toolbar.elevation = Utils.dipToPixels(V1_ELEVATION_DP).toFloat()
            val sideMargin = Utils.dipToPixels(V1_SIDE_MARGIN_DP)
            val topMargin = Utils.dipToPixels(V1_TOP_MARGIN_DP)
            val params = toolbar.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null) {
                params.setMargins(sideMargin, topMargin, sideMargin, 0)
                toolbar.layoutParams = params
            }
            toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                // Jangan melawan v2 kalau restrukturisasi berhasil belakangan.
                if (v2Applied[toolbar] != true && toolbar.background !== glass) {
                    toolbar.background = glass
                }
            }
        } catch (e: Throwable) {
            log(e)
        }
    }

    private fun buildGlassDrawableV1(toolbar: ViewGroup): GradientDrawable {
        val barColor = resolveBarColor(toolbar)
        val glassColor = (barColor and 0x00FFFFFF) or
            ((V1_BACKGROUND_ALPHA * 255).toInt() shl 24)
        val strokeColor = if (isLightColor(barColor)) {
            Color.argb((V1_STROKE_ALPHA * 255).toInt(), 0, 0, 0)
        } else {
            Color.argb((V1_STROKE_ALPHA * 255).toInt(), 255, 255, 255)
        }
        val radius = Utils.dipToPixels(V1_CORNER_RADIUS_DP).toFloat()
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

    private fun resolveBarColor(bar: ViewGroup): Int {
        val currentBg = bar.background
        if (currentBg is android.graphics.drawable.ColorDrawable) {
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
