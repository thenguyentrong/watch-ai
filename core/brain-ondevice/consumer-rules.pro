# LiteRT-LM reaches Kotlin classes from native code; R8 must not rename or drop them.
-keep class com.google.ai.edge.litertlm.** { *; }
