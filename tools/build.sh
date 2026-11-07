#!/usr/bin/env bash
# Builds Car Dashboard in Docker (nothing else to install). Usage:
#   tools/build.sh                                         # release APK
#   tools/build.sh testReleaseUnitTest lintRelease assembleRelease
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${IMAGE:-car-dashboard-build}"
GRADLE_VOLUME="${GRADLE_VOLUME:-car-dashboard-gradle}"
TASKS=("$@")
[ ${#TASKS[@]} -gt 0 ] || TASKS=(assembleRelease)

command -v docker >/dev/null || { echo "ERROR: Docker is needed." >&2; exit 1; }
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Building the $IMAGE image once (a few minutes)..."
  docker build -t "$IMAGE" "$ROOT/tools/docker"
fi
docker run --rm --user "$(id -u):$(id -g)" -v "$ROOT:/workspace" -v "$GRADLE_VOLUME:/gradle" \
  "$IMAGE" sh ./gradlew --no-daemon --console=plain "${TASKS[@]}"
for apk in "$ROOT"/app/build/outputs/apk/*/*.apk; do
  [ -f "$apk" ] && echo "APK: ${apk#"$ROOT"/} ($(wc -c < "$apk" | tr -d ' ') bytes)"
done
exit 0
