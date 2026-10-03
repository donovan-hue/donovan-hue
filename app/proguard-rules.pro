# ============================ HiFi Player – R8 rules ============================
# Keep the media session service entry point discovered via manifest reflection.
-keep class com.hifiplayer.core.audio.service.PlaybackService { *; }
-keep class * extends androidx.media3.session.MediaSessionService { *; }
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
