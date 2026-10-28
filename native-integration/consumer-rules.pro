# Called by the shared audio engine's JNI input adapter.
-keep class com.twilightecho.android.nativeintegration.NativeAudioInput { public *; }
-keep class com.twilightecho.android.nativeintegration.NativeAudioInputProvider { public *; }
-keep class com.twilightecho.android.nativeintegration.TwilightAudioEngineJni { *; }
-keep interface com.twilightecho.android.nativeintegration.TwilightAudioEventListener { *; }
