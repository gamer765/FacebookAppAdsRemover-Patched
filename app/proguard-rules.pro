# Keep module entry class (extends XposedModule, needs public no-arg constructor).
-keep class * extends io.github.libxposed.api.XposedModule { public <init>(); }

# Rewrite java_init.list when entry classes are obfuscated.
-adaptresourcefilecontents META-INF/xposed/java_init.list

# dexplore relies heavily on reflection.
-keep class io.github.neonorbit.** { *; }

# OkHttp / Okio keep their own consumer rules; nothing extra needed.
