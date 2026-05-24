-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-dontwarn com.google.ai.edge.litertlm.**
