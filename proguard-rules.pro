# PerfectPatch — reglas ProGuard posteriores a reobfJar (Forge 1.8.9 / Java 8)
#
# Forge y Minecraft utilizan reflexión y bytecode reobfuscado. Por estabilidad
# se conserva el flujo sin shrink/optimización agresiva; la reducción de huella
# se realiza eliminando atributos de depuración y ofuscando el resto del código.

-dontshrink
-dontoptimize
-allowaccessmodification

# Los nombres que no están protegidos por -keep se mueven a un paquete corto.
# Las clases alcanzadas por Forge/JNI/reflexión conservan su FQCN real.
-repackageclasses 'p'
-flattenpackagehierarchy 'p'

# El JAR raíz es el bootstrap del contenedor embebido. Sus clases públicas de
# infraestructura deben conservar nombres orgánicos para que los puntos de
# carga, diagnósticos y el runtime no terminen como p.a, p.b, etc. Las clases
# de implementación no llegan a ProGuard: packModulePlugins las coloca antes
# en assets/modules/core.bin.
-keepnames class perfectpathc.dev.**

# El bytecode de core.bin enlaza estas API del JAR padre por sus nombres de
# miembro originales. Además del FQCN, se conservan sus superficies públicas
# para que la carga aislada no rompa enlaces simbólicos después de ofuscar.
-keepclassmembers class perfectpathc.dev.PerfectPatch {
    public protected *;
}
-keepclassmembers class perfectpathc.dev.nativecore.NativeBridge {
    public protected *;
}
-keepclassmembers class perfectpathc.dev.modules.runtime.** {
    public protected *;
}

# Conserva lo que Forge/JVM necesita para anotaciones, genéricos, clases
# anidadas y verificación de bytecode. SourceFile, LineNumberTable y las
# tablas de variables locales se omiten intencionalmente.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod,StackMapTable

# Las dependencias de juego son bibliotecas externas y pueden contener
# referencias opcionales según el entorno de Forge 1.8.9.
-dontwarn net.minecraft.**
-dontwarn net.minecraftforge.**
-dontwarn cpw.mods.**
-dontwarn org.lwjgl.**
-dontwarn com.mojang.**
-dontwarn sun.misc.**
-ignorewarnings

# ---------------------------------------------------------------------------
# Descubrimiento y ciclo de vida de Forge/FML
# ---------------------------------------------------------------------------

# FML localiza esta clase por el escaneo de @Mod: su nombre y constructor deben
# permanecer exactamente como fueron compilados.
-keep class perfectpathc.dev.PerfectPatch {
    <init>();
    @net.minecraftforge.fml.common.Mod$EventHandler <methods>;
    @net.minecraftforge.fml.common.Mod$Instance <fields>;
}

# FML inyecta los campos @Mod.Instance y llama los handlers por reflexión.
-keepclassmembers class * {
    @net.minecraftforge.fml.common.Mod$EventHandler <methods>;
    @net.minecraftforge.fml.common.Mod$Instance <fields>;
}

# El EventBus descubre estos métodos de forma reflectiva después del registro.
-keepclassmembers class * {
    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent <methods>;
}

# Tras reobfJar, los overrides de Minecraft usan nombres SRG (func_*). Las
# librerías entregadas a ProGuard pueden estar MCP-named, por lo que se evita
# que ProGuard renombre estos puntos de extensión.
-keepclassmembers class * {
    *** func_*(...);
}

# ---------------------------------------------------------------------------
# JNI — los símbolos Rust se enlazan con este FQCN y estas firmas nativas.
# ---------------------------------------------------------------------------

-keep class perfectpathc.dev.nativecore.NativeBridge {
    native <methods>;
}

# ---------------------------------------------------------------------------
# API estable del sistema de módulos embebidos
# ---------------------------------------------------------------------------

# El bytecode de módulos empaquetados se compila por separado del JAR raíz;
# por eso estas clases, la interfaz y las firmas públicas no pueden cambiar.
-keep class perfectpathc.dev.modules.runtime.EmbeddedModuleClassLoader {
    public protected *;
}
-keep class perfectpathc.dev.modules.runtime.ModuleRegistry {
    public protected *;
}
-keep class perfectpathc.dev.modules.runtime.ModuleLauncher {
    public protected *;
}
-keep interface perfectpathc.dev.modules.runtime.IModuleEntry {
    public *;
}

# Previene que se eliminen constructores sin argumentos de entradas declaradas
# por plugins; ModuleLauncher los invoca mediante reflexión.
-keepclassmembers class * implements perfectpathc.dev.modules.runtime.IModuleEntry {
    public <init>();
    public void initialize();
}

# API de enums requerida por valueOf()/values() reflectivos.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
