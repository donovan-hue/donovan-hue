# ============================ HiFi Player – R8 rules ============================
# Entry points named from the manifest (the Application, the activity and the playback service) are
# kept because Android instantiates them by name at runtime.
-keep class com.hifiplayer.data.audio.service.PlaybackService { *; }
-keep class * extends androidx.media3.session.MediaSessionService { *; }
-keep class com.hifiplayer.HiFiPlayerApp { *; }
-keep class com.hifiplayer.ui.MainActivity { *; }
-keep class * extends androidx.media3.session.MediaLibraryService { *; }

# Media3 / ExoPlayer
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.audio.** { *; }
-keepclassmembers class androidx.media3.common.Player { *; }

# jaudiotagger (metadata + ReplayGain tags)
-dontwarn org.jaudiotagger.**
-keep class org.jaudiotagger.** { *; }
-keep class org.apache.commons.** { *; }
-dontwarn org.apache.commons.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Our pure-Kotlin audio DSP / capability model (used from service + UI, keep names readable in crash reports)
-keepnames class com.hifiplayer.core.model.** { *; }
-keepnames class com.hifiplayer.core.audio.** { *; }

# Timber
-dontwarn org.jetbrains.annotations.**

# Keep annotated @JvmStatic / Reflect annotations if ever used
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
