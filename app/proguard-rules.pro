-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.localagents.** { *; }
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar {
    public <init>();
}
-keep class com.google.mlkit.vision.common.internal.VisionCommonRegistrar {
    public <init>();
}
-keep class com.google.mlkit.vision.text.internal.TextRegistrar {
    public <init>();
}
-keep class androidx.tracing.** { *; }
-keep class com.bytedance.zgx.solin.runtime.TfliteTextEmbeddingRuntimeFactory { *; }
-keep interface com.bytedance.zgx.solin.memory.EmbeddingRuntime { *; }
-keepclassmembers class * {
    native <methods>;
}
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.ai.edge.localagents.**
