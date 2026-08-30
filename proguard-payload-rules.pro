# The encrypted payload is obfuscated independently before it is put into
# cache.dat.  The parent JAR is a library here, so its Forge/JNI entry points
# retain their existing ABI while all module implementation symbols may change.

-dontshrink
-dontoptimize
-allowaccessmodification
-useuniqueclassmembernames

-classobfuscationdictionary proguard-dictionary.txt
-obfuscationdictionary proguard-dictionary.txt

# Do not retain the source package hierarchy in classes defined by the
# embedded loader.  This is intentionally a private namespace, never a Forge
# or Minecraft namespace.
-repackageclasses 'r'

# Class strings used for local type checks must track their renamed targets.
-adaptclassstrings

# Keep runtime metadata needed by Forge's event bridge and class verification.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod,StackMapTable

-dontwarn net.minecraft.**
-dontwarn net.minecraftforge.**
-dontwarn cpw.mods.**
-dontwarn org.lwjgl.**
-dontwarn com.mojang.**
-dontwarn sun.misc.**
-ignorewarnings

# The payload entry point is resolved from the generated mapping; its class
# name can change, but its constructor and public runtime contract cannot.
-keepclassmembers class perfectpathc.dev.modules.impl.PayloadRuntime {
    public <init>();
    public protected *;
}

# Forge locates these handlers through annotations rather than source names.
-keepclassmembers class * {
    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent <methods>;
}

-keepclassmembers class * {
    *** func_*(...);
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
