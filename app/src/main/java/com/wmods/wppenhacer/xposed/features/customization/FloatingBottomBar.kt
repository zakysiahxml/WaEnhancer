package com.wmods.wppenhacer.xposed.features.customization

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Checkable
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.ViewHookDispatcher
import com.wmods.wppenhacer.xposed.utils.Utils
import java.lang.ref.WeakReference
import java.util.WeakHashMap

class FloatingBottomBar(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    companion object {
        private const val CORNER_RADIUS_DP = 24f
        private const val SIDE_MARGIN_DP = 12f
        private const val BOTTOM_MARGIN_DP = 16f
        private const val ELEVATION_DP = 4f
        // Nilai bawaan Floating Bottom Bar (sebelum ada kaca).
        private const val BUILTIN_BACKGROUND_ALPHA = 0.90f
        private const val BUILTIN_STROKE_ALPHA = 0.40f
        private const val BUILTIN_STROKE_WIDTH_DP = 1.5f
        // Nilai kaca iOS (toggle "Bar Kaca iOS"): bar lebih transparan,
        // outline lebih tipis & halus.
        private const val GLASS_BACKGROUND_ALPHA = 0.45f
        private const val GLASS_STROKE_ALPHA = 0.15f
        private const val GLASS_STROKE_WIDTH_DP = 1f
        private const val FAB_GAP_DP = 8f
        // Pil tab aktif (gaya iOS, lihat screenshot referensi): pil gelap di
        // belakang ikon+label tab yang sedang dipilih.
        private const val PILL_RADIUS_DP = 20f
        // Pil tab aktif: bawaan (toggle pil) vs kaca (lebih gelap & pekat).
        private const val BUILTIN_PILL_ALPHA = 0.92f
        private const val BUILTIN_PILL_DARKEN = 0.65f
        private const val GLASS_PILL_ALPHA = 0.95f
        private const val GLASS_PILL_DARKEN = 0.50f
        private const val PILL_INSET_H_DP = 4f
        private const val PILL_INSET_V_DP = 6f
        private val FAB_RESOURCE_NAMES = arrayOf("fab", "fab_second", "extended_mini_fab")
    }

    private val processedBars = WeakHashMap<ViewGroup, Boolean>()
    private val setupAttempts = WeakHashMap<ViewGroup, Int>()
    private val overlayByRoot = WeakHashMap<FrameLayout, WeakReference<ViewGroup>>()
    private val baseBottomPadding = WeakHashMap<ViewGroup, Int>()
    private val paddingGuarded = WeakHashMap<ViewGroup, Boolean>()
    private val pillInstalled = WeakHashMap<ViewGroup, Boolean>()
    private val originalItemBackgrounds = WeakHashMap<View, Drawable?>()
    private val pillDrawables = WeakHashMap<View, Drawable>()

    // Toggle "Bar Kaca iOS" (menu baru, beda dari Floating Bottom Bar bawaan):
    // kalau nyala, bar pakai tampilan kaca; kalau mati, bar kembali ke
    // tampilan bawaan Floating Bottom Bar.
    private fun isGlass(): Boolean = xprefs.getBoolean("glass_tab_bar", false)

    override fun doHook() {
        if (!xprefs.getBoolean("floating_bottom_bar", false)) return

        val bottomNavId = Utils.getID("bottom_nav", "id")
        if (bottomNavId <= 0) return
        val fabIds = FAB_RESOURCE_NAMES.mapNotNull { name ->
            Utils.getID(name, "id").takeIf { id -> id > 0 }
        }.toSet()

        ViewHookDispatcher.onAttachedToWindow(this) { view ->
            if (view.id == bottomNavId) {
                val bar = view as? ViewGroup ?: return@onAttachedToWindow
                scheduleSetup(bar)
                return@onAttachedToWindow
            }
            if (view.id in fabIds) {
                view.post { positionFabAboveCurrentBar(view, bottomNavId) }
            }
        }

        ViewHookDispatcher.onDetachedFromWindow(this) { view ->
            if (view.id != bottomNavId) return@onDetachedFromWindow
            val bar = view as? ViewGroup ?: return@onDetachedFromWindow
            setupAttempts.remove(bar)
            processedBars.remove(bar)
            val container = bar.parent as? ViewGroup ?: return@onDetachedFromWindow
            val root = container.parent as? FrameLayout ?: return@onDetachedFromWindow
            if (overlayByRoot[root]?.get() !== container) return@onDetachedFromWindow
            root.post {
                if (!bar.isAttachedToWindow && overlayByRoot[root]?.get() === container) {
                    if (container.parent === root) root.removeView(container)
                    overlayByRoot.remove(root)
                }
            }
        }
    }

    private fun scheduleSetup(bar: ViewGroup) {
        if (processedBars.containsKey(bar)) {
            ensureBarOverlay(bar)
            return
        }

        bar.post {
            if (setupFloatingBar(bar)) {
                processedBars[bar] = true
                setupAttempts.remove(bar)
                return@post
            }
            retrySetup(bar)
        }
    }

    private fun retrySetup(bar: ViewGroup) {
        val attempt = setupAttempts[bar] ?: 0
        if (attempt >= 3) return
        setupAttempts[bar] = attempt + 1
        bar.postDelayed({
            if (processedBars.containsKey(bar)) return@postDelayed
            if (setupFloatingBar(bar)) {
                processedBars[bar] = true
                setupAttempts.remove(bar)
            } else {
                retrySetup(bar)
            }
        }, 100L)
    }

    private fun ensureBarOverlay(bar: ViewGroup) {
        val container = bar.parent as? ViewGroup ?: return
        if (container.parent !is FrameLayout) {
            bar.post { setupFloatingBar(bar) }
        }
    }

    private fun setupFloatingBar(bar: ViewGroup): Boolean {
        try {
            val container = bar.parent as? ViewGroup ?: return false
            val rootView = findRootView(bar) ?: return false
            if (container.parent === rootView) {
                removePreviousOverlay(rootView, container)
                overlayByRoot[rootView] = WeakReference(container)
                updateOverlayLayout(rootView, container, bar)
                applyTransparentShadowStyle(container, bar)
                installActivePill(bar)
                positionFabsAboveBar(rootView, container)
                return true
            }

            (container.parent as? ViewGroup)?.removeView(container)

            val rootParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = navigationBarInset(rootView) + Utils.dipToPixels(BOTTOM_MARGIN_DP)
            }
            rootView.addView(container, rootParams)
            removePreviousOverlay(rootView, container)
            overlayByRoot[rootView] = WeakReference(container)

            applyTransparentShadowStyle(container, bar)
            installActivePill(bar)
            positionFabsAboveBar(rootView, container)

            logDebug("FloatingBottomBar applied as overlay")
            return true
        } catch (e: Throwable) {
            log(e)
            return false
        }
    }

    private fun removePreviousOverlay(rootView: FrameLayout, currentContainer: ViewGroup) {
        val previous = overlayByRoot[rootView]?.get()
        if (previous !== currentContainer) {
            if (previous?.parent === rootView) {
                rootView.removeView(previous)
                logDebug("FloatingBottomBar: stale overlay dilepas sebelum bar baru dipasang")
            }
            overlayByRoot.remove(rootView)
        }

        val bottomNavId = Utils.getID("bottom_nav", "id")
        if (bottomNavId <= 0) return
        for (index in rootView.childCount - 1 downTo 0) {
            val candidate = rootView.getChildAt(index)
            if (candidate === currentContainer) continue
            if (candidate.id != bottomNavId && candidate.findViewById<View>(bottomNavId) == null) continue
            rootView.removeViewAt(index)
            logDebug("FloatingBottomBar: duplikat navbar tak terlacak dilepas dari root")
        }
    }

    private fun updateOverlayLayout(rootView: FrameLayout, container: ViewGroup, bar: ViewGroup) {
        val params = container.layoutParams as? FrameLayout.LayoutParams ?: return
        val bottomMargin = navigationBarInset(rootView) + Utils.dipToPixels(BOTTOM_MARGIN_DP)
        if (params.gravity != Gravity.BOTTOM
            || params.width != ViewGroup.LayoutParams.MATCH_PARENT
            || params.height != ViewGroup.LayoutParams.WRAP_CONTENT
            || params.bottomMargin != bottomMargin
        ) {
            params.gravity = Gravity.BOTTOM
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.bottomMargin = bottomMargin
            container.layoutParams = params
        }
        val barParams = bar.layoutParams
        if (barParams.width != ViewGroup.LayoutParams.MATCH_PARENT
            || barParams.height != ViewGroup.LayoutParams.WRAP_CONTENT
        ) {
            barParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            barParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            bar.layoutParams = barParams
        }
    }

    private fun navigationBarInset(view: View): Int {
        return ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
            ?.bottom ?: 0
    }

    private fun findRootView(startView: View): FrameLayout? {
        var current: View? = startView
        var lastFrameLayout: FrameLayout? = null
        while (current != null) {
            if (current is FrameLayout) {
                lastFrameLayout = current
            }
            current = current.parent as? View
        }
        return lastFrameLayout
    }


    private fun applyTransparentShadowStyle(container: ViewGroup, bar: ViewGroup) {
        container.setBackgroundColor(Color.TRANSPARENT)

        val parent = container.parent as? ViewGroup
        parent?.clipChildren = false
        parent?.clipToPadding = false
        container.clipChildren = false
        container.clipToPadding = false

        val dividerId = Utils.getID("bottom_nav_divider", "id")
        if (dividerId > 0) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child.id == dividerId) {
                    child.visibility = View.GONE
                    break
                }
            }
        }

        val barColor = resolveBarColor(bar)
        val bgAlpha = if (isGlass()) GLASS_BACKGROUND_ALPHA else BUILTIN_BACKGROUND_ALPHA
        val strokeAlpha = if (isGlass()) GLASS_STROKE_ALPHA else BUILTIN_STROKE_ALPHA
        val strokeWidthDp = if (isGlass()) GLASS_STROKE_WIDTH_DP else BUILTIN_STROKE_WIDTH_DP
        val transparentColor = (barColor and 0x00FFFFFF) or
                ((bgAlpha * 255).toInt() shl 24)
        val strokeColor = if (isLightColor(barColor)) {
            Color.argb((strokeAlpha * 255).toInt(), 0, 0, 0)
        } else {
            Color.argb((strokeAlpha * 255).toInt(), 255, 255, 255)
        }
        val radiusDp =
            xprefs.getInt("floating_bottom_bar_radius", CORNER_RADIUS_DP.toInt()).toFloat()
        val radius = Utils.dipToPixels(radiusDp).toFloat()

        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                radius, radius, radius, radius,
                radius, radius, radius, radius
            )
            setColor(transparentColor)
            setStroke(Utils.dipToPixels(strokeWidthDp), strokeColor)
        }
        bar.background = background
        bar.outlineProvider = ViewOutlineProvider.BACKGROUND
        bar.elevation = Utils.dipToPixels(ELEVATION_DP).toFloat()

        val sideMargin = Utils.dipToPixels(SIDE_MARGIN_DP)
        val params = bar.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null && (params.width != ViewGroup.LayoutParams.MATCH_PARENT
                    || params.height != ViewGroup.LayoutParams.WRAP_CONTENT
                    || params.leftMargin != sideMargin || params.topMargin != 0
                    || params.rightMargin != sideMargin || params.bottomMargin != 0)
        ) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.setMargins(sideMargin, 0, sideMargin, 0)
            bar.layoutParams = params
        }

        stripBottomInset(bar)
        installPaddingGuard(bar)

        // Use the Activity content tree as the backdrop. It includes the
        // visible screen content without assuming the list is a RecyclerView.
        if (isGlass()) {
            bar.post { tryInstallLiquidGlass(bar, 0) }
        }
        // Jarak antar ikon (setting "Icon Spacing").
        bar.post { applyIconSpacing(bar) }
    }

    private fun tryInstallLiquidGlass(bar: ViewGroup, attempt: Int) {
        if (bar.getChildAt(0) is LiquidGlassView) return
        if (bar.width <= 0 || bar.height <= 0) {
            if (attempt < 10) {
                bar.postDelayed({ tryInstallLiquidGlass(bar, attempt + 1) }, 300L)
            }
            return
        }
        val backdrop = findContentBackdrop(bar)
        if (backdrop == null) {
            if (attempt < 10) {
                bar.postDelayed({ tryInstallLiquidGlass(bar, attempt + 1) }, 300L)
            } else {
                logDebug("FloatingBottomBar: android.R.id.content backdrop tidak tersedia")
            }
            return
        }
        if (!installLiquidGlass(bar, backdrop)) {
            logDebug("FloatingBottomBar: LiquidGlassView tidak terpasang; mempertahankan background bar")
        }
    }

    private fun installLiquidGlass(bar: ViewGroup, backdrop: View): Boolean {
        val radiusDp = xprefs.getInt(
            "floating_bottom_bar_radius", CORNER_RADIUS_DP.toInt()
        ).toFloat()
        try {
            if (bar !is FrameLayout) return false
            if (bar.getChildAt(0) is LiquidGlassView) return true
            val glass = LiquidGlassView(bar.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                cornerRadius = Utils.dipToPixels(radiusDp).toFloat()
                material = GlassMaterial.CLEAR
                enableDynamicBackground = true
                enableSensorHighlight = true
                enableAdaptiveTint = true
                // Kaca harus TEMBUS seperti referensi: tint terang tipis
                // supaya teks chat di belakang bar tetap kelihatan nge-blur.
                setGlassTint(0xFF9AA7B2.toInt(), 0.15f)
                // Backdrop is the Activity content view, not a specific list class.
                backdropSource = backdrop
            }
            // Background GradientDrawable JANGAN dihapus: kalau kaca
            // library merender transparan (terbukti di v9c/v9d), bar
            // tetap kelihatan sebagai pil tembus, bukan hilang total.
            // Kaca digambar DI ATAS background sebagai anak pertama.
            bar.addView(glass, 0)
            logDebug(
                "FloatingBottomBar: kaca terpasang, backdrop=android.R.id.content " +
                        "${backdrop.javaClass.simpleName} " +
                        "${backdrop.width}x${backdrop.height} shown=${backdrop.isShown}"
            )
            return true
        } catch (e: Throwable) {
            log(e)
            return false
        }
    }

    private fun findContentBackdrop(bar: ViewGroup): View? {
        val content = bar.rootView.findViewById<View>(android.R.id.content) ?: return null
        if (content === bar || isDescendantOf(content, bar)) return null
        if (content.visibility != View.VISIBLE || !content.isShown ||
            content.width <= 0 || content.height <= 0
        ) {
            return null
        }
        return content
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current = view.parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    /**
     * Jarak antar ikon tab: kasih margin kiri-kanan ke MenuView supaya
     * ikon lebih mepet ke tengah. Nilai dari setting "Icon Spacing" (dp).
     */
    private fun applyIconSpacing(bar: ViewGroup) {
        try {
            val menuView = findMenuView(bar) ?: return
            val spacingPx = Utils.dipToPixels(
                xprefs.getInt("floating_bottom_bar_icon_spacing", 16).toFloat()
            )
            val params = menuView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (params.leftMargin != spacingPx || params.rightMargin != spacingPx) {
                params.leftMargin = spacingPx
                params.rightMargin = spacingPx
                menuView.layoutParams = params
            }
        } catch (e: Throwable) {
            log(e)
        }
    }

    /**
     * Bikin tombol aksi mengambang (Meta AI + chat baru) jadi BULET:
     * potong tampilan view jadi oval. Background asli (termasuk ripple)
     * tidak diganti, cuma bentuk luarnya yang dipotong.
     */
    private fun roundFab(fab: View) {
        try {
            fab.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            fab.clipToOutline = true
        } catch (e: Throwable) {
            log(e)
        }
    }

    private fun stripBottomInset(bar: ViewGroup) {
        val basePadding = baseBottomPadding.getOrPut(bar) {
            (bar.paddingBottom - navigationBarInset(bar)).coerceAtLeast(0)
        }
        if (bar.paddingBottom != basePadding) {
            bar.setPaddingRelative(bar.paddingStart, bar.paddingTop, bar.paddingEnd, basePadding)
        }
    }

    // WDSBottomBar (Material BottomNavigationView) re-applies the navigation bar inset as
    // bottom padding on every insets dispatch (e.g. when switching tabs), which made the
    // floating bar grow taller. The bar already sits above the navigation bar via its margin,
    // so the bottom inset is ignored and the base padding is enforced on every layout.
    private fun installPaddingGuard(bar: ViewGroup) {
        if (paddingGuarded.put(bar, true) != null) return
        ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
            stripBottomInset(view as ViewGroup)
            insets
        }
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            stripBottomInset(bar)
            applyIconSpacing(bar)
            val params = bar.layoutParams
            if (params != null && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                bar.layoutParams = params
            }
        }
    }

    // Pil tab aktif (gaya iOS): tab yang sedang dipilih dapat background pil
    // gelap membulat di belakang ikon+labelnya, seperti tab "Chat" di
    // screenshot referensi. Tab lain dibiarkan apa adanya (ripple bawaan).
    // Dipasang hanya kalau toggle "floating_bottom_bar_active_pill" nyala.
    private fun installActivePill(bar: ViewGroup) {
        if (!xprefs.getBoolean("floating_bottom_bar_active_pill", false) && !isGlass()) return
        if (pillInstalled.put(bar, true) != null) {
            applyActivePill(bar)
            return
        }
        val menuView = findMenuView(bar) ?: run {
            logDebug("FloatingBottomBar: menu tab tidak ketemu, pil aktif dilewati")
            return
        }
        // Simpan background asli tiap item supaya bisa dikembalikan saat
        // tab itu tidak aktif lagi (ripple bawaan jangan hilang permanen).
        for (i in 0 until menuView.childCount) {
            val item = menuView.getChildAt(i) ?: continue
            if (!originalItemBackgrounds.containsKey(item)) {
                originalItemBackgrounds[item] = item.background
            }
        }
        menuView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyActivePill(bar)
        }
        bar.post { applyActivePill(bar) }
    }

    private fun applyActivePill(bar: ViewGroup) {
        try {
            val menuView = findMenuView(bar) ?: return
            for (i in 0 until menuView.childCount) {
                val item = menuView.getChildAt(i) ?: continue
                val isChecked = (item as? Checkable)?.isChecked == true
                if (isChecked) {
                    val pill = pillDrawables[item] ?: buildPillDrawable(bar).also {
                        pillDrawables[item] = it
                    }
                    if (item.background !== pill) {
                        item.background = pill
                    }
                } else {
                    // Kembalikan background asli (ripple bawaan). Kalau item
                    // ini baru muncul setelah install (tidak ada di peta),
                    // jangan disentuh supaya ripple-nya tidak hilang.
                    if (!originalItemBackgrounds.containsKey(item)) continue
                    val original = originalItemBackgrounds[item]
                    if (item.background !== original) {
                        item.background = original
                    }
                }
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
        // Fallback: anak ViewGroup pertama (BottomNavigationView umumnya
        // cuma punya satu anak, yaitu menu-nya).
        return bar.getChildAt(0) as? ViewGroup
    }

    private fun buildPillDrawable(bar: ViewGroup): Drawable {
        val baseColor = resolveBarColor(bar)
        val pillDarken = if (isGlass()) GLASS_PILL_DARKEN else BUILTIN_PILL_DARKEN
        val pillAlpha = if (isGlass()) GLASS_PILL_ALPHA else BUILTIN_PILL_ALPHA
        val pillColor = darkenColor(baseColor, pillDarken) and 0x00FFFFFF or
            ((pillAlpha * 255).toInt() shl 24)
        val radius = Utils.dipToPixels(PILL_RADIUS_DP).toFloat()
        val pill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                radius, radius, radius, radius,
                radius, radius, radius, radius
            )
            setColor(pillColor)
        }
        return InsetDrawable(
            pill,
            Utils.dipToPixels(PILL_INSET_H_DP),
            Utils.dipToPixels(PILL_INSET_V_DP),
            Utils.dipToPixels(PILL_INSET_H_DP),
            Utils.dipToPixels(PILL_INSET_V_DP)
        )
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val red = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val green = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val blue = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(red, green, blue)
    }

    private fun positionFabsAboveBar(rootView: ViewGroup, container: ViewGroup) {
        val barHeight = container.height
        if (barHeight <= 0) {
            container.postDelayed({ positionFabsAboveBar(rootView, container) }, 100L)
            return
        }

        val offset = -(barHeight + Utils.dipToPixels(FAB_GAP_DP)).toFloat()
        for (name in FAB_RESOURCE_NAMES) {
            val id = Utils.getID(name, "id")
            if (id <= 0) continue
            rootView.findViewById<View>(id)?.let { fab ->
                fab.translationY = offset
                bringToFrontIfNeeded(fab)
                roundFab(fab)
            }
        }
    }

    private fun positionFabAboveCurrentBar(fab: View, bottomNavId: Int) {
        val bottomNav = fab.rootView.findViewById<View>(bottomNavId) ?: return
        val container = bottomNav.parent as? ViewGroup ?: return
        if (container.parent !is FrameLayout) return
        positionFab(fab, container)
    }

    private fun positionFab(fab: View, container: ViewGroup) {
        val barHeight = container.height
        if (barHeight <= 0) {
            container.postDelayed({ positionFab(fab, container) }, 100L)
            return
        }

        fab.translationY = -(barHeight + Utils.dipToPixels(FAB_GAP_DP)).toFloat()
        bringToFrontIfNeeded(fab)
        roundFab(fab)
    }

    private fun bringToFrontIfNeeded(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        if (parent.indexOfChild(view) != parent.childCount - 1) view.bringToFront()
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

    override fun getPluginName(): String {
        return "Floating Bottom Bar"
    }
}
