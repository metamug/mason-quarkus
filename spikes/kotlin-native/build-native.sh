#!/usr/bin/env bash
# THROWAWAY spike: builds the compiled scripts into a native binary inside a Mandrel builder container, then smoke-tests the binary.
#   usage: build-native.sh <label> [extra native-image arguments]
#   needs: docker, and build/ from build-classes.sh. IMAGE can override the Mandrel builder image.
# The smoke test is part of the build: a binary whose required selftest cases fail is a failed build (exit 1).
set -euo pipefail
cd "$(dirname "$0")"
label=$1; shift
IMAGE=${IMAGE:-quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25}
mkdir -p results
CP="build/classes:build/template:resources:$(ls build/lib/*.jar | tr '\n' ':')"
CP=${CP%:}
start=$(date +%s)
docker run --rm -v "$PWD:/work" -w /work --entrypoint native-image "$IMAGE" \
  --no-fallback -cp "$CP" "$@" MainKt -o "build/native-$label" 2>&1 | tee "results/native-build-$label.log" | tail -n 25
end=$(date +%s)
sudo chown "$(id -u):$(id -g)" "build/native-$label" 2>/dev/null || true
size=$(stat -c %s "build/native-$label")
echo "build: $((end - start)) s, binary $((size / 1024 / 1024)) MB"
echo "{\"label\":\"$label\",\"buildSeconds\":$((end - start)),\"binaryBytes\":$size}" > "results/native-$label.json"
echo "== smoke test of build/native-$label =="
set +e
/usr/bin/time -v -o "results/native-$label-time.txt" "build/native-$label" selftest smoke.json | tee "results/native-$label-selftest.txt"
code=${PIPESTATUS[0]}
set -e
grep -E "Maximum resident|Elapsed" "results/native-$label-time.txt" || true
exit "$code"
