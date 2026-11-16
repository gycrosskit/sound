#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION:?Pass the immutable candidate version}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] || { echo 'Invalid version' >&2; exit 2; }
[[ ! -e build/maven && ! -e build/sound-maven.tar.gz ]] || { echo "Candidate outputs already exist" >&2; exit 2; }
bash gradlew --init-script "$PWD/scripts/ci-repositories.gradle" publishAllPublicationsToStagingRepository --no-daemon --max-workers=1 -Dorg.gradle.parallel=false "$@"
exec bash scripts/prepare-maven.sh
