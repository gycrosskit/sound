#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash gradlew :sound-core:compileDebugKotlinAndroid :sound-core:testDebugUnitTest :sound-core:compileKotlinIosArm64 :sound-core:compileKotlinIosX64 :sound-core:iosSimulatorArm64Test :sound-core:compileKotlinOhosArm64 :sound-kuikly:compileKotlinOhosArm64 --max-workers=1 --no-parallel
node verification/ohos-behavior.cjs
bash gradlew publishAllPublicationsToStagingRepository --max-workers=1 --no-parallel
bash scripts/prepare-maven.sh
bash gradlew -p verification-consumer -PsoundMavenRepo="$PWD/build/maven" compileDebugKotlinAndroid compileKotlinIosArm64 compileKotlinIosX64 linkDebugFrameworkIosSimulatorArm64 compileKotlinOhosArm64 --max-workers=1 --no-parallel
xcrun swiftc -typecheck -target arm64-apple-ios14.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -F verification-consumer/build/bin/iosSimulatorArm64/debugFramework verification/SwiftConsumer.swift
