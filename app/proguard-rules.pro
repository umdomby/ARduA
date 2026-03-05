# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Сохраняем MainActivity полностью (там JNI и reflection)
-keep class com.example.ardua.MainActivity { *; }

# Обязательно сохраняем все native-методы (JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Подавляем предупреждения от Okio и WebRTC (они часто ругаются при обфускации)
-dontwarn okio.**
-dontwarn org.webrtc.**

# Дополнительно (рекомендую добавить сразу):
# Не трогаем классы, которые используются через reflection или JNI
-keep class * extends android.app.Service { *; }
-keep class * extends android.app.Activity { *; }

# Если используешь Gson/JSON — сохрани модели
-keep class org.json.** { *; }

# Не оптимизируем слишком агрессивно (на случай проблем)
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*