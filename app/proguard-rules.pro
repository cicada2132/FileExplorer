# Optional codecs referenced by Apache Commons Compress that this app does not ship.
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn javax.annotation.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**

# Keep exception class names readable in error reports.
-keepnames class * extends java.lang.Throwable
