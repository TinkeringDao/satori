# libreader-onnx.so binds ReaderNative's external functions by their names (reader_jni.cpp).
-keep class com.satori.reader.ReaderNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
