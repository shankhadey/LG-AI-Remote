# Keep line numbers so crash reports point at real code.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# org.json is part of Android; nothing to keep. OkHttp, Compose, Firebase and ML Kit ship their own rules.
# The on-device AI library is beta; keep it whole so reflection inside it keeps working.
-keep class com.google.mlkit.genai.** { *; }
-dontwarn com.google.mlkit.genai.**
