package com.wmods.wppenhacer.xposed.features.customization

import android.content.SharedPreferences
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MenuItem
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.FeatureLoader
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * Tab Bar iOS: replace known WhatsApp home-tab icons with the module selectors.
 * The selected-state selector is reapplied only to the exact MenuItem object
 * registered for that tab, so WhatsApp's later active-icon update cannot
 * replace the customized icon or affect the You/profile image.
 */
class IosTabBar(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    private data class TrackedTabIcon(
        val item: WeakReference<MenuItem>,
        val drawable: Drawable
    )

    private val trackedTabIcons = mutableListOf<TrackedTabIcon>()
    private val trackedTabIconsLock = Any()
    private val hookedSetIconMethods = mutableSetOf<Method>()
    private val hookedSetIconMethodsLock = Any()
    private val enforcingIcon = ThreadLocal.withInitial { false }

    override fun doHook() {
        if (!xprefs.getBoolean("ios_tab_bar", false)) return

        val menuAddAndroidX = try {
            Unobfuscator.loadAddMenuAndroidX(classLoader)
        } catch (e: Throwable) {
            log(e)
            return
        }

        menuAddAndroidX.hook {
            after {
                if (args.size <= 2) return@after
                val tabId = (args[1] as? Number)?.toInt() ?: return@after
                val menuItem = result as? MenuItem ?: return@after
                val selectorRes = selectorForTab(tabId) ?: return@after
                val drawable = loadModuleDrawable(selectorRes) ?: return@after

                rememberTabIcon(menuItem, drawable)
                try {
                    hookSetIconFor(menuItem)
                } catch (e: Throwable) {
                    log(e)
                }
                try {
                    menuItem.setIcon(drawable)
                    logDebug("IosTabBar: ikon tab $tabId diganti")
                } catch (e: Throwable) {
                    // Keep the rest of the WhatsApp menu usable if an item
                    // implementation rejects the module drawable.
                    log(e)
                }
            }
        }
    }

    private fun selectorForTab(tabId: Int): Int? {
        return when (tabId) {
            SeparateGroup.CHATS -> R.drawable.zky_home_tab_chats_selector_ios
            SeparateGroup.STATUS -> R.drawable.zky_home_tab_status_selector_ios
            CALLS_TAB_ID -> R.drawable.zky_home_tab_calls_selector_ios
            SeparateGroup.GROUPS ->
                R.drawable.zky_home_tab_communities_selector_ios
            else -> {
                logDebug("IosTabBar: tab id $tabId tidak dikenal, ikon dibiarkan bawaan")
                null
            }
        }
    }

    private fun rememberTabIcon(menuItem: MenuItem, drawable: Drawable) {
        synchronized(trackedTabIconsLock) {
            trackedTabIcons.removeAll { entry ->
                val item = entry.item.get()
                item == null || item === menuItem
            }
            trackedTabIcons.add(TrackedTabIcon(WeakReference(menuItem), drawable))
        }
    }

    private fun trackedDrawableFor(menuItem: Any?): Drawable? {
        if (menuItem == null) return null
        synchronized(trackedTabIconsLock) {
            trackedTabIcons.removeAll { it.item.get() == null }
            return trackedTabIcons.firstOrNull { it.item.get() === menuItem }?.drawable
        }
    }

    /**
     * Hook both AndroidX MenuItemImpl and the actual returned implementation
     * when available. The second target is a fallback for wrappers or forks
     * whose concrete MenuItem class differs; failure leaves the initial v9c
     * menu-add icon swap intact.
    */
    private fun hookSetIconFor(menuItem: MenuItem) {
        val menuItemImpl = try {
            ReflectionUtils.findClassIfExists(
                "androidx.appcompat.view.menu.MenuItemImpl",
                classLoader
            )
        } catch (e: Throwable) {
            log(e)
            null
        }
        val candidates = listOfNotNull(menuItem.javaClass, menuItemImpl).distinct()

        for (candidate in candidates) {
            if (!candidate.isAssignableFrom(menuItem.javaClass)) continue
            try {
                val method = ReflectionUtils.findMethodExact(
                    candidate,
                    "setIcon",
                    Drawable::class.java
                )
                val shouldHook = synchronized(hookedSetIconMethodsLock) {
                    hookedSetIconMethods.add(method)
                }
                if (!shouldHook) continue

                method.hook {
                    after {
                        if (enforcingIcon.get()) return@after
                        val target = instance as? MenuItem ?: return@after
                        val expected = trackedDrawableFor(target) ?: return@after
                        val requested = args.firstOrNull() as? Drawable
                        // Do not replace profile/avatar bitmaps, and do not
                        // re-enter for the exact selector drawable we set.
                        if (requested is BitmapDrawable || requested === expected) return@after

                        enforcingIcon.set(true)
                        try {
                            target.setIcon(expected)
                        } catch (e: Throwable) {
                            log(e)
                        } finally {
                            enforcingIcon.remove()
                        }
                    }
                }
                logDebug("IosTabBar: hook setIcon terpasang pada ${candidate.name}")
            } catch (e: Throwable) {
                log(e)
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

    private companion object {
        // Stable WhatsApp tab IDs. These keep role selection independent of
        // the user's configured tab order and number of visible tabs. IDs not
        // recognized here (including the You/profile item) remain untouched.
        const val CALLS_TAB_ID = 400
    }
}
