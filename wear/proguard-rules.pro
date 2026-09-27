# sherpa-onnx (the "Hey Buddy" wake word) reads its config classes' fields from native code and
# ships no rules of its own; renamed or removed fields would crash the watch app on load.
-keep class com.k2fsa.sherpa.onnx.** { *; }
