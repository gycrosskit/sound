#!/usr/bin/env python3
import hashlib
import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('har', Path(__file__).with_name('ci-har-release.py'))
har = importlib.util.module_from_spec(spec)
spec.loader.exec_module(har)


class HarVerificationTest(unittest.TestCase):
    def test_frozen_bytes_and_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            package = root / 'ohos/sound-native'
            files = {'oh-package.json5': json.dumps({'name': '@gycrosskit/sound', 'version': '0.1.2', 'compatibleSdkVersion': 22}).encode(),
                     **{name: b'0.1.2' for name in ['Index.ets', 'README.md', 'CHANGELOG.md', 'LICENSE', 'src/main/ets/SoundPlayer.ets', 'src/main/ets/SoundModule.ets']}}
            archive = root / 'test.har'
            with tarfile.open(archive, 'w:gz') as bundle:
                for name, data in files.items():
                    target = package / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)
                    member = tarfile.TarInfo('package/' + name)
                    member.size = len(data)
                    bundle.addfile(member, io.BytesIO(data))
            (root / 'har-release-checksums.txt').write_text('har-0.1.2 ' + hashlib.sha256(archive.read_bytes()).hexdigest())
            self.assertEqual(har.verify(archive, 'har-0.1.2', root)['version'], '0.1.2')
            (package / 'Index.ets').write_text('modified')
            with self.assertRaisesRegex(ValueError, 'tagged source'):
                har.verify(archive, 'har-0.1.2', root)
            archive.write_bytes(b'corrupt')
            with self.assertRaisesRegex(ValueError, 'checksum'):
                har.verify(archive, 'har-0.1.2', root)


if __name__ == '__main__':
    unittest.main()
