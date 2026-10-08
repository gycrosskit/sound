#!/usr/bin/env python3
"""Verify frozen HAR bytes and public package contents against the tagged checkout."""
import argparse
import hashlib
import io
import json
import re
import tarfile
from pathlib import Path


def verify(archive, tag, root):
    if not re.fullmatch(r'har-\d+\.\d+\.\d+', tag):
        raise ValueError('Expected an immutable har-x.y.z tag')
    checksums = dict(line.split() for line in (root / 'har-release-checksums.txt').read_text().splitlines()
                     if line and not line.startswith('#'))
    payload = archive.read_bytes()
    if hashlib.sha256(payload).hexdigest() != checksums[tag]:
        raise ValueError('HAR checksum differs from frozen manifest')
    package = root / 'ohos/sound-native'
    with tarfile.open(fileobj=io.BytesIO(payload), mode='r:gz') as har:
        names = har.getnames()
        if len(names) != len(set(names)) or any(member.issym() or member.islnk() for member in har):
            raise ValueError('Ambiguous HAR members')
        def contents(name):
            return har.extractfile('package/' + name).read()
        metadata = json.loads(contents('oh-package.json5'))
        if (metadata['name'], metadata['version'], metadata['compatibleSdkVersion']) != ('@gycrosskit/sound', tag[4:], 22):
            raise ValueError('HAR name/version/API compatibility mismatch')
        for name in ['Index.ets', 'README.md', 'CHANGELOG.md', 'LICENSE',
                     'src/main/ets/SoundPlayer.ets', 'src/main/ets/SoundModule.ets']:
            if contents(name) != (package / name).read_bytes():
                raise ValueError('HAR differs from tagged source: ' + name)
        if tag[4:].encode() not in contents('CHANGELOG.md'):
            raise ValueError('Current HAR version missing from changelog')
    return metadata


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--tag', required=True)
    args = parser.parse_args()
    metadata = verify(args.archive, args.tag, Path(__file__).resolve().parent.parent)
    print(f"Verified {metadata['name']}@{metadata['version']}: frozen SHA-256, API22, API/source/README/CHANGELOG bytes")
