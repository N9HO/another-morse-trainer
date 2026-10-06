# R8 rules for the release build.
#
# Deliberately near-empty. Compose, OkHttp, and desugar_jdk_libs all ship
# consumer rules, and this app does no reflection: persistence is hand-written
# org.json in Settings/Stats/EngineStore/JourneyStore, not a reflective mapper,
# so there is nothing R8 can strip out from under a Class.forName.
#
# The one thing worth keeping is the vendored CW decoder port. It is kept
# byte-identical to a firmware copy (see PROVENANCE.md next to it); its
# entry points are called from CwDecoderEngine on the audio capture thread,
# and keeping it un-renamed makes a stack trace from that path readable
# against the firmware source it was ported from.
-keep class app.anothermorsetrainer.morsekit.cw.** { *; }

# Line numbers in Play Console crash reports. Requires uploading the mapping
# file (build/outputs/mapping/release/mapping.txt) with each release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Tink (pulled by androidx.security:security-crypto for AccountClient's token
# store) references compile-only annotation classes that are not on the
# runtime classpath; R8 fails the release build on them as missing classes.
# They are annotations only, so nothing at runtime needs them.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
