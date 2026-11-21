package com.wmods.wppenhacer.xposed.features.general

import android.content.SharedPreferences
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator

class AlwaysPlayStatusWithSound(
    loader: ClassLoader,
    preferences: SharedPreferences
) : Feature(loader, preferences) {

    override fun doHook() {
        if (!xprefs.getBoolean("always_play_status_with_sound", false)) return

        // Status content's setMuted(boolean) sets the muted flag, the player volume
        // and the audio-focus handshake in one place. Forcing the argument to false
        // makes every status (video or doodle/music video) start with sound, without
        // having to raise the volume or tap the mute button first. While this is on,
        // the on-screen mute button becomes a no-op.
        Unobfuscator.loadStatusContentSetMutedMethod(classLoader).hook {
            before {
                args[0] = false
            }
        }
    }

    override fun getPluginName(): String {
        return "Always Play Status With Sound"
    }
}
