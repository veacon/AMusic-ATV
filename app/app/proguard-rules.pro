# Moshi reflective models persisted in existing queues and content caches.
# Generated DTO adapters and Retrofit use the libraries' bundled consumer rules.
-keep class com.veacon.veilmusic.data.model.** { *; }
-keep class com.veacon.veilmusic.ui.viewmodel.HomeSection { *; }
-keep class com.veacon.veilmusic.ui.screens.BrowseShelf { *; }

# Local FFmpeg jar has no bundled consumer rules; its JNI library resolves these names.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
