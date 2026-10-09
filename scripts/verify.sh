#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash gradlew :sound-core:compileDebugKotlinAndroid :sound-core:testDebugUnitTest :sound-core:compileKotlinIosArm64 :sound-core:compileKotlinIosX64 :sound-core:iosSimulatorArm64Test :sound-core:compileKotlinOhosArm64 :sound-kuikly:compileKotlinOhosArm64 :sound-kuikly:testDebugUnitTest :sound-kuikly:compileKotlinIosArm64 :sound-kuikly:compileKotlinIosSimulatorArm64 --max-workers=1 --no-parallel
node verification/ohos-behavior.cjs
bash gradlew publishAllPublicationsToStagingRepository --max-workers=1 --no-parallel
bash scripts/prepare-maven.sh
render_dir=$(bash scripts/ci-build-kuikly-render.sh "$PWD/build/kuikly-native-pod" GYCSound/Kuikly)
bash scripts/test-ios-module.sh "$render_dir"
bash gradlew -p verification-consumer -PsoundMavenRepo="$PWD/build/maven" -PkuiklyRenderFrameworkDir="$render_dir" compileDebugKotlinAndroid compileKotlinIosArm64 compileKotlinIosX64 linkDebugFrameworkIosSimulatorArm64 compileKotlinOhosArm64 --max-workers=1 --no-parallel
xcrun swiftc -typecheck -target arm64-apple-ios14.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -F verification-consumer/build/bin/iosSimulatorArm64/debugFramework verification/SwiftConsumer.swift
xcrun swiftc -typecheck -target arm64-apple-ios15.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" -F verification-consumer/build/bin/iosSimulatorArm64/debugFramework -F "$render_dir" verification/KuiklyAssembly.swift
