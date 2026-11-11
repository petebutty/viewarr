# NewPipe Extractor (YouTube trailers): Rhino runs YouTube's player JavaScript.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
-dontwarn jdk.dynalink.**

# A11yTrim (ui/common/A11yTrim.kt): skips Compose's screen-reader work while no screen reader is on (a launcher's
# button-catching accessibility service turned it on for everything). Reflection needs these two names.
-keepclassmembers class androidx.compose.ui.platform.AndroidComposeView { androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat composeAccessibilityDelegate; }
-keepclassmembers class androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat { java.util.List enabledServices; }
