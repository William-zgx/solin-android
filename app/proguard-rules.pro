-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.localagents.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.ai.edge.localagents.**
