#!/usr/bin/env python3
"""Fail CI if credential containers or private-key blocks enter the source tree."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
blocked_suffixes = {'.pk8', '.p7b', '.pem', '.key', '.p12', '.pfx', '.jks', '.keystore', '.apk', '.aab'}
private_block = re.compile(rb'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{40,}')
# The one private key that belongs in the tree: the Android Auto head-unit development key pair
# (subject "Google-Android-Reference") that headunit, Open Headunit, DiAuto and every other
# open-source head unit ship, which the phone accepts as the head unit's identity. It is public
# and secret to no one; see docs/androidauto/THIRD_PARTY_NOTICES.md and SECURITY.md.
allowed_private_blocks = {'androidauto/src/main/res/raw/privkey'}
failures = []
for name in filter(None, names):
    path = root / name
    if not path.is_file():
        continue
    if '.private' in path.relative_to(root).parts or path.suffix.lower() in blocked_suffixes:
        failures.append(name)
    elif name not in allowed_private_blocks and private_block.search(path.read_bytes()):
        failures.append(name)
if failures:
    raise SystemExit('Credential or distribution files are forbidden in the public tree:\n' + '\n'.join(failures))
print('Public tree check passed: no credential containers or private-key blocks.')
