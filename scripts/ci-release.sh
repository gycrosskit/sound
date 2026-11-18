#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION:?Provide an immutable release tag}"
[[ "$VERSION" =~ ^[0-9][0-9A-Za-z._-]*$ ]] || { echo 'Invalid release tag' >&2; exit 1; }
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT
# 从不可变版本 Tag 取得提交和 tree，沿用 JitPack 的回执与归档校验。
repo_root="$PWD"
git init -q "$staging/tag"
git -C "$staging/tag" fetch -q --no-tags --depth=1 https://github.com/gycrosskit/sound.git "refs/tags/$VERSION"
git -C "$staging/tag" checkout -q --detach FETCH_HEAD
commit="$(git -C "$staging/tag" rev-parse --verify HEAD)"
(cd "$staging/tag" && bash "$repo_root/jitpack-install.sh" sound "$staging/maven")
python3 scripts/check-maven.py "$staging/maven" com.github.gycrosskit.sound "$VERSION" sound-core,sound-kuikly ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64
# 标签必须解析为不可变发布提交；不把当前 PR 的 SHA 当成已发布版本。
case "$VERSION" in
  0.1.1|0.1.2|0.1.3|0.1.4|0.1.5) expected_publications="sound-core,sound-core-android,sound-core-iosarm64,sound-core-iosx64,sound-core-iossimulatorarm64,sound-core-ohosarm64,sound-kuikly,sound-kuikly-ohosarm64" ;;
  *) expected_publications="sound-core,sound-core-android,sound-core-iosarm64,sound-core-iosx64,sound-core-iossimulatorarm64,sound-core-ohosarm64,sound-kuikly,sound-kuikly-android,sound-kuikly-iosarm64,sound-kuikly-iosx64,sound-kuikly-iossimulatorarm64,sound-kuikly-ohosarm64" ;;
esac
python3 scripts/check-public-maven.py --repo sound --version "$VERSION" --commit "$commit" \
  --expected-publications "$expected_publications" --output-dir "${CI_DIAGNOSTICS_DIR:-ci-diagnostics}/public"
