"""核对本地 Gradle Module Metadata 引用的文件、变体与校验值。"""
import hashlib
import json
from pathlib import Path

repo = Path(__file__).resolve().parents[1] / "build/maven"
modules = list(repo.rglob("*.module"))
assert modules, "Maven repository is empty"
platforms = set()
for module in modules:
    data = json.loads(module.read_text())
    assert data["component"]["group"] == "com.github.gycrosskit.sound"
    if "url" in data["component"]:
        assert (module.parent / data["component"]["url"]).resolve().is_file()
    for variant in data["variants"]:
        native = variant.get("attributes", {}).get("org.jetbrains.kotlin.native.target")
        if native:
            platforms.add(native)
        redirect = variant.get("available-at")
        if redirect:
            assert (module.parent / redirect["url"]).resolve().is_file(), redirect
        for entry in variant.get("files", []):
            file = (module.parent / entry["url"]).resolve()
            assert file.is_file(), file
            assert file.stat().st_size == entry["size"], file
            assert hashlib.sha256(file.read_bytes()).hexdigest() == entry["sha256"], file
assert {"ios_arm64", "ios_x64", "ios_simulator_arm64", "ohos_arm64"} <= platforms, platforms
assert list(repo.rglob("*.aar")), "Android AAR is missing"
print(f"Maven metadata: {len(modules)} modules, Android AAR and iOS/OHOS targets passed")
