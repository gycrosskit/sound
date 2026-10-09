"""验证归档编排：只修改候选版本，坏 publication 不能替换已验证归档。"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
from tempfile import TemporaryDirectory
import unittest

SOURCE = Path(__file__).resolve().parents[1]
VERSION = "0.1.3"
GROUP = "com.github.gycrosskit.sound"
PUBLICATIONS = {
    "sound-core": None,
    "sound-core-android": None,
    "sound-core-iosarm64": "ios_arm64",
    "sound-core-iosx64": "ios_x64",
    "sound-core-iossimulatorarm64": "ios_simulator_arm64",
    "sound-core-ohosarm64": "ohos_arm64",
    "sound-kuikly": None,
    "sound-kuikly-android": None,
    "sound-kuikly-iosarm64": "ios_arm64",
    "sound-kuikly-iosx64": "ios_x64",
    "sound-kuikly-iossimulatorarm64": "ios_simulator_arm64",
    "sound-kuikly-ohosarm64": "ohos_arm64",
}


class ArchivePreparation(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        for name in ("prepare-maven.sh", "jitpack-metadata.py", "check-maven.py"):
            shutil.copyfile(SOURCE / "scripts" / name, self.root / "scripts" / name)
        shutil.copyfile(SOURCE / "release-pack.py", self.root / "release-pack.py")
        self.repository = self.root / "build/maven/com/github/gycrosskit/sound"
        for module, target in PUBLICATIONS.items():
            directory = self.repository / module / VERSION
            directory.mkdir(parents=True)
            pom = f'''<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>{GROUP}</groupId><artifactId>{module}</artifactId><version>{VERSION}</version><licenses><license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url><distribution>repo</distribution></license></licenses></project>'''
            self.write(directory / f"{module}-{VERSION}.pom", pom.encode())
            artifact = directory / ("fixture.aar" if module.endswith("-android") else "fixture.klib")
            self.write(artifact, b"fixture")
            variant = {"name": "api", "files": [{"url": artifact.name, "size": artifact.stat().st_size,
                **{algorithm: hashlib.new(algorithm, artifact.read_bytes()).hexdigest() for algorithm in ("md5", "sha1", "sha256", "sha512")}}]}
            if target:
                variant["attributes"] = {"org.jetbrains.kotlin.native.target": target}
            self.write(directory / f"{module}-{VERSION}.module", json.dumps({
                "component": {"group": GROUP, "module": module, "version": VERSION},
                "variants": [variant, {"name": "metadataSourcesElements"}]}).encode())
        self.old = self.repository / "sound-core/0.1.1/sound-core-0.1.1.module"
        self.old.parent.mkdir(parents=True)
        self.old.write_bytes(b"historical metadata must stay unchanged")
        self.archive = self.root / "build/sound-maven.tar.gz"

    def write(self, path, contents):
        path.write_bytes(contents)
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            path.with_name(path.name + "." + algorithm).write_text(hashlib.new(algorithm, contents).hexdigest())

    def prepare(self):
        return subprocess.run(["bash", "scripts/prepare-maven.sh"], cwd=self.root, capture_output=True, text=True, env={**os.environ, "VERSION": VERSION})

    def test_candidate_only_and_historical_bytes_preserved(self):
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Maven metadata: 12 modules", result.stdout)
        self.assertEqual(self.old.read_bytes(), b"historical metadata must stay unchanged")
        with tarfile.open(self.archive) as archive:
            names = archive.getnames()
            self.assertEqual(sum(name.endswith(".module") for name in names), 12)
            self.assertFalse(any("/0.1.1/" in name or Path(name).name.startswith("._") for name in names))
            for member in archive.getmembers():
                if member.name.endswith(".module"):
                    data = json.load(archive.extractfile(member))
                    self.assertEqual(len(data["variants"]), 1)
        self.assertFalse(list((self.root / "build").glob("maven-check.*")))

    def test_invalid_pom_preserves_verified_archive(self):
        self.archive.write_bytes(b"previous verified archive")
        pom = self.repository / "sound-core" / VERSION / f"sound-core-{VERSION}.pom"
        self.write(pom, pom.read_bytes().replace(b"<licenses>", b"<unused>").replace(b"</licenses>", b"</unused>"))
        result = self.prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing license", result.stderr)
        self.assertEqual(self.archive.read_bytes(), b"previous verified archive")
        self.assertFalse(list((self.root / "build").glob("maven-check.*")))

    def test_missing_publication_cannot_create_archive(self):
        shutil.rmtree(self.repository / "sound-kuikly-ohosarm64")
        result = self.prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing or unexpected publications", result.stderr)
        self.assertFalse(self.archive.exists())


if __name__ == "__main__":
    unittest.main()
