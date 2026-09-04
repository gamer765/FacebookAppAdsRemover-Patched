#!/usr/bin/env python3
from pathlib import Path

p = Path('app/src/main/java/tn/loukious/facebookappadsremover/Patches.kt')
s = p.read_text(encoding='utf-8')

bad = r'''private val facebook576FastPathHookedMethods: MutableSet<Method> =\n    Collections.synchronizedSet(HashSet())\n\nfun installFacebook576VideoInterruptionFastPaths(classLoader: ClassLoader) {'''
good = '''private val facebook576FastPathHookedMethods: MutableSet<Method> =
    Collections.synchronizedSet(HashSet())

fun installFacebook576VideoInterruptionFastPaths(classLoader: ClassLoader) {'''
if bad not in s:
    raise SystemExit('ERROR: escaped fast-path declaration not found')
s = s.replace(bad, good, 1)
p.write_text(s, encoding='utf-8')
print('Fixed Kotlin newline escaping in fast-path declaration')
