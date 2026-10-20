package com.example.service

import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import com.example.util.PlatformCompat

/**
 * Summons Google's Circle to Search (`Omni` / `VoiceInteractionSession`) directly via Binder
 * without starting an Activity or interrupting foreground video playback.
 */
object HiddenApiHelper {

    @Volatile
    private var exempted = false

    @Volatile
    var testOmniInvoker: ((sdkInt: Int) -> Boolean)? = null

    fun exemptAll() {
        if (exempted) return
        runCatching {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime").apply {
                isAccessible = true
            }
            val vmRuntime = getRuntime.invoke(null)
            val setExemptions = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            ).apply {
                isAccessible = true
            }
            setExemptions.invoke(vmRuntime, arrayOf("L"))
            exempted = true
        }.onFailure {
            runCatching {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply {
                    isAccessible = true
                }
                val unsafe = theUnsafeField.get(null)
                if (unsafe != null) {
                    val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
                    val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime").apply {
                        isAccessible = true
                    }
                    val vmRuntime = getRuntime.invoke(null)
                    val setExemptions = vmRuntimeClass.getDeclaredMethod(
                        "setHiddenApiExemptions",
                        Array<String>::class.java
                    ).apply {
                        isAccessible = true
                    }
                    setExemptions.invoke(vmRuntime, arrayOf("L"))
                    exempted = true
                }
            }
        }
    }

    /**
     * Invokes `IVoiceInteractionManagerService.showSessionFromSession` on the `"voiceinteraction"`
     * system service.
     * - API 34..36: invokes the 4-argument overload across attribution tags `"hyperOS_home"`,
     *   `"circle_to_search"`, and `null`.
     * - API 28..33: invokes the 3-argument overload.
     */
    fun triggerCircleToSearchOmni(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        testOmniInvoker?.let { hook ->
            return runCatching { hook.invoke(sdkInt) }.getOrDefault(false)
        }

        return runCatching {
            exemptAll()
            val bundle = Bundle().apply {
                putLong("invocation_time_ms", SystemClock.elapsedRealtime())
                putInt("omni.entry_point", 1)
                putInt("omni.invocation_type", 1)
                putBoolean("micts_trigger", true)
            }
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java)
                .invoke(null, "voiceinteraction") as? IBinder ?: return false
            val stubClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
            val vims = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val iVimsClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService")

            if (PlatformCompat.voiceInteractionParameterCount(sdkInt) == 4) {
                val method = iVimsClass.getDeclaredMethod(
                    "showSessionFromSession",
                    IBinder::class.java,
                    Bundle::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java
                ).apply {
                    isAccessible = true
                }
                PlatformCompat.voiceInteractionAttributionTags.any { tag ->
                    (method.invoke(vims, null, bundle, 7, tag) as? Boolean) == true
                }
            } else {
                val method = iVimsClass.getDeclaredMethod(
                    "showSessionFromSession",
                    IBinder::class.java,
                    Bundle::class.java,
                    Int::class.javaPrimitiveType
                ).apply {
                    isAccessible = true
                }
                (method.invoke(vims, null, bundle, 7) as? Boolean) == true
            }
        }.getOrDefault(false)
    }
}
