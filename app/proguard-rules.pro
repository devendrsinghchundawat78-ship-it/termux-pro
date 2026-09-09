# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# ---- Termux Pro additions (classes referenced by name from XML/preferences) ----
-keep class com.termux.pro.app.editor.CodeEditText { *; }
-keep class com.termux.pro.app.fragments.settings.** { *; }
-keep class com.termux.pro.app.AiAgentActivity { *; }
-keep class com.termux.pro.app.files.** { *; }
-keep class com.termux.pro.app.editor.** { *; }
