#!/usr/bin/env bash
# Exercise the real SDK's dealloc/invalidate path using the already-built source Pod.
set -euo pipefail
products=${1:?Usage: test-ios-module.sh absolute-Products-directory}
[[ "$products" = /* ]] || { echo 'Products directory must be absolute' >&2; exit 1; }
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/gyc-module-test.XXXXXX")
device=${2:-}
own_device=false
installed=false
bundle_id=com.gycrosskit.sound.module.lifecycle
cleanup() {
  if [[ -n "$device" && "$own_device" == true ]]; then xcrun simctl shutdown "$device" >/dev/null 2>&1 || true; xcrun simctl delete "$device" >/dev/null 2>&1 || true; fi
  if [[ "$installed" == true && "$own_device" == false ]]; then xcrun simctl uninstall "$device" "$bundle_id" >/dev/null 2>&1 || true; fi
  rm -rf "$work_dir"
}
trap cleanup EXIT
app="$work_dir/ModuleTest.app"
mkdir -p "$app/Frameworks"
for framework in OpenKuiklyIOSRender GYCSound; do
  test -d "$products/$framework.framework"
  cp -R "$products/$framework.framework" "$app/Frameworks/"
done
xcrun --sdk iphonesimulator swiftc -parse-as-library "$repo_dir/verification/KuiklyLifecycle.swift" \
  -target "$(uname -m)-apple-ios15.0-simulator" -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
  -F "$products" -framework OpenKuiklyIOSRender -framework GYCSound \
  -Xlinker -rpath -Xlinker '@executable_path/Frameworks' -o "$app/ModuleTest"
cat > "$app/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>com.gycrosskit.sound.module.lifecycle</string>
<key>CFBundleExecutable</key><string>ModuleTest</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleVersion</key><string>1</string>
<key>LSRequiresIPhoneOS</key><true/>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>MinimumOSVersion</key><string>15.0</string>
</dict></plist>
PLIST
codesign --force --sign - "$app/Frameworks/GYCSound.framework" "$app/Frameworks/OpenKuiklyIOSRender.framework" "$app" >/dev/null
if [[ -z "$device" ]]; then
  own_device=true
  # Global device types can include iPhones unsupported by the selected runtime.
  read -r runtime device_type < <(xcrun simctl list runtimes -j | python3 -c '
import json, sys
for runtime in json.load(sys.stdin)["runtimes"]:
    if not runtime.get("isAvailable") or not runtime["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS"):
        continue
    for device_type in runtime.get("supportedDeviceTypes", []):
        if device_type["name"].startswith("iPhone"):
            print(runtime["identifier"], device_type["identifier"])
            sys.exit(0)
sys.exit("No available iOS runtime with a supported iPhone device type")
')
  device=$(xcrun simctl create GYCModuleLifecycle "$device_type" "$runtime")
  xcrun simctl boot "$device"
  xcrun simctl bootstatus "$device" -b >/dev/null
fi
xcrun simctl install "$device" "$app"
installed=true
xcrun simctl launch --console --terminate-running-process "$device" "$bundle_id" | tee "$work_dir/result.log"
grep -q '^KUIKLY_MODULE_LIFECYCLE_PASS$' "$work_dir/result.log"
