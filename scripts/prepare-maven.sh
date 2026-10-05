#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version="${VERSION:-0.1.3}"
# 只规范化当前候选版本，保留 staging 中历史版本的原字节。
publications=(build/maven/com/github/gycrosskit/sound/*/"$version")
python3 scripts/jitpack-metadata.py "${publications[@]}"
checked_repo=$(mktemp -d "$PWD/build/maven-check.XXXXXX")
trap 'rm -rf "$checked_repo"' EXIT
python3 release-pack.py build/maven "$checked_repo/sound-maven.tar.gz" "$version"
tar -xzf "$checked_repo/sound-maven.tar.gz" -C "$checked_repo"
python3 scripts/check-maven.py "$checked_repo" com.github.gycrosskit.sound "$version" \
  sound-core,sound-kuikly ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64 \
  sound-core,sound-core-android,sound-core-iosarm64,sound-core-iosx64,sound-core-iossimulatorarm64,sound-core-ohosarm64,sound-kuikly,sound-kuikly-ohosarm64
mv "$checked_repo/sound-maven.tar.gz" build/sound-maven.tar.gz
shasum -a 256 build/sound-maven.tar.gz
