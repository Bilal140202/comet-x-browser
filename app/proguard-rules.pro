# Comet-X keeps minification off for v1 (correctness over size); rules kept for future enablement.
-keep class com.cometx.browser.** { *; }
-keepattributes JavascriptInterface
-keep class org.json.** { *; }
-dontwarn kotlinx.coroutines.**
