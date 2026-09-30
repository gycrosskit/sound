#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test -f ohos/sound-native/build/default/outputs/default/SoundNative.har || { echo 'Build SoundNative.har first' >&2; exit 1; }
SOUND_DEVECO_HOME="${SOUND_DEVECO_HOME:-/Applications/DevEco-Studio.app/Contents}"
export DEVECO_SDK_HOME="${DEVECO_SDK_HOME:-$SOUND_DEVECO_HOME/sdk}"
export PATH="$SOUND_DEVECO_HOME/tools/node/bin:$SOUND_DEVECO_HOME/tools/ohpm/bin:$PATH"
cd verification-har
ohpm install --all
"$SOUND_DEVECO_HOME/tools/hvigor/bin/hvigorw" --mode module -p module=SoundConsumerNative@default -p product=default assembleHar --no-daemon
