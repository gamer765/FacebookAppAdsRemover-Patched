from pathlib import Path

p = Path("app/src/main/java/tn/loukious/facebookappadsremover/Patches.kt")
s = p.read_text()

old = '''    // Item class -> candidate accessor chains (each chain is a sequence of
    // zero-arg getters from item to model). Null entries are cached misses.
    private val accessorChainsCache = ConcurrentHashMap<Class<*>, List<List<Method>>?>()
'''
new = '''    // Item class -> candidate accessor chains (each chain is a sequence of
    // zero-arg getters from item to model). ConcurrentHashMap rejects null values,
    // so misses are tracked separately instead of being written as null entries.
    private val accessorChainsCache = ConcurrentHashMap<Class<*>, List<List<Method>>>()
    private val accessorChainMissCache = ConcurrentHashMap<Class<*>, Boolean>()
'''
if old not in s:
    raise SystemExit("accessorChainsCache block not found")
s = s.replace(old, new, 1)

old = '''    private fun resolveAccessorChains(clazz: Class<*>): List<List<Method>>? {
        accessorChainsCache.get(clazz)?.let { return it }
        val chains = runCatching { findAccessorChains(clazz) }.getOrNull()
        accessorChainsCache[clazz] = chains
        return chains
    }
'''
new = '''    private fun resolveAccessorChains(clazz: Class<*>): List<List<Method>>? {
        accessorChainsCache[clazz]?.let { return it }
        if (accessorChainMissCache.containsKey(clazz)) return null

        val chains = runCatching { findAccessorChains(clazz) }.getOrNull()
        if (chains == null) {
            accessorChainMissCache[clazz] = true
        } else {
            accessorChainsCache[clazz] = chains
        }
        return chains
    }
'''
if old not in s:
    raise SystemExit("resolveAccessorChains block not found")
s = s.replace(old, new, 1)
p.write_text(s)

b = Path("app/build.gradle.kts")
s = b.read_text()
if 'versionCode = 10' not in s or 'versionName = "1.9"' not in s:
    raise SystemExit("expected v1.9 version fields not found")
s = s.replace("versionCode = 10", "versionCode = 11", 1)
s = s.replace('versionName = "1.9"', 'versionName = "1.9.1-patched"', 1)
b.write_text(s)

print("Patched ReelsAdClassifier null-cache bug")
print("Set version to 1.9.1-patched (11)")
