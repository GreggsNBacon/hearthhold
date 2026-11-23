# smbj and dependencies
-keep class com.hierynomus.** { *; }
-keep class com.rapid7.** { *; }
-keep class net.engio.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class javax.jmdns.** { *; }
-dontwarn com.hierynomus.**
-dontwarn com.rapid7.**
-dontwarn net.engio.**
-dontwarn org.bouncycastle.**
-dontwarn javax.jmdns.**
-dontwarn org.slf4j.**
-dontwarn javax.el.**
-dontwarn javax.security.**
-dontwarn org.ietf.jgss.**
-dontwarn java.lang.management.**

# media3 FFmpeg audio decoder: DefaultRenderersFactory finds it by name (reflection), so keep that name and
# constructor rather than relying on R8 rewriting the Class.forName string. Natives are kept by the AAR's own rules.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
