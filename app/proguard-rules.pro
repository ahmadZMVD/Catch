# Add project specific ProGuard rules here.
# Keep Room database entities, DAOs, and generated implementations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Keep AccessibilityService, TileService, BroadcastReceivers, and Trampoline Activity
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.service.quicksettings.TileService
-keep public class * extends android.content.BroadcastReceiver

# Keep Kotlin Coroutines & StateFlow internals
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.flow.**
