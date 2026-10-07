# UniFFI uses JNA reflection/callbacks to access libfluxdown_mobile.so.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.fluxdown.bridge.** { *; }
-dontwarn java.awt.**
