# Comet-X keeps minification off for v1 (correctness over size); rules kept for future enablement.
-keep class com.cometx.browser.** { *; }
-keepattributes JavascriptInterface
-keep class org.json.** { *; }
-dontwarn kotlinx.coroutines.**

# v1.6.0 on-device AI: the JNI boundary is resolved BY NAME from native code.
# The blanket keep above already covers it; these rules add defense-in-depth
# for the R8 day minification is enabled — the JARVIS v1.5.0 bug (R8 renamed
# the progress callback; native GetMethodID("onProgress") failed; every
# generation was discarded) must never reproduce here.
-keep class com.cometx.browser.ai.local.** { *; }
-keepclassmembers class com.cometx.browser.ai.local.** {
    void onProgress(int,int,int,int,byte[]);
}
-keepattributes Exceptions
