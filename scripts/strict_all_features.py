#!/usr/bin/env python3
from pathlib import Path

p = Path('source/app/src/main/java/tn/loukious/facebookappadsremover/Patches.kt')
s = p.read_text(encoding='utf-8')

old = '''        runCatching { installFacebook576VideoInterruptionFastPaths(classLoader) }
            .onFailure { AndroidLog.e(TAG, "Failed to install Facebook 576 video interruption fast paths", it) }
'''
new = '''        AndroidLog.i(TAG, "Strict obfuscation experiment: exact Facebook 576/577 X.* fast paths disabled")
'''

if old not in s:
    raise SystemExit('ERROR: exact fast-path call anchor not found')

s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('Disabled exact Facebook 576/577 fast-path fallback for strict all-feature experiment')
