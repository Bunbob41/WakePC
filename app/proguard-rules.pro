# WakePC keep rules.
#
# Everything here exists because R8 broke this app at runtime once already
# (0.7.0 would not launch). Each rule names something that is looked up
# reflectively, which R8 cannot see from the call sites.

# Glance instantiates ActionCallbacks by class name when a widget is tapped,
# so nothing references RunAction/RefreshAction directly in the bytecode.
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
    *;
}

# The widget classes are named from the manifest and from Glance's own
# receivers; keep them and their no-arg constructors intact.
-keep class com.morgan.wakepc.WakeWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver {
    <init>();
    *;
}
-keep class * extends androidx.glance.appwidget.GlanceAppWidget {
    <init>();
    *;
}

# ViewModels are resolved by their class token through the factory.
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# The QS tile service is named from the manifest.
-keep class com.morgan.wakepc.WakeTileService { *; }

# Kotlin metadata is used by reflection in several AndroidX libraries.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# Glance depends on WorkManager, whose Room database is built by reflection
# from a generated *_Impl class. Without these, androidx.startup's
# InitializationProvider throws during app startup — before MainActivity runs
# — which is exactly how 0.7.0 became unlaunchable.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class androidx.work.impl.WorkDatabase { *; }

# Quieter, and harmless: these are optional deps referenced by OkHttp.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
