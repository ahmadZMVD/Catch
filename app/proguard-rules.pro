# Keep Room database entities, DAOs, and generated implementations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Keep AccessibilityService, TileService, BroadcastReceivers, Application, and Activities
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.service.quicksettings.TileService
-keep public class * extends android.content.BroadcastReceiver

# Keep application domain, service, and utility classes intact for deterministic runtime behavior
-keep class com.example.data.** { *; }
-keep class com.example.domain.** { *; }
-keep class com.example.service.** { *; }
-keep class com.example.notification.** { *; }
-keep class com.example.util.** { *; }

# Keep Kotlin Coroutines & StateFlow internals
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.flow.**
-dontwarn sun.misc.Unsafe
