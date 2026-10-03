#!/usr/bin/env bash
# Starts the example app in Quarkus dev mode and runs measure.mjs against it.
#   usage: run.sh <label> [extra -D args for dev mode]     (results/<label>.json, results/raw/dev-<label>.log)
# needs: JDK 17+, Maven 3.9+ (MVN, default mvn), node 18+; the modules must be installed: mvn install -DskipTests at the repo root
set -e
cd "$(dirname "$0")"
label=$1; shift
MVN=${MVN:-mvn}
PORT=${PORT:-8090}
mkdir -p mq results/raw
rm -f mq/*.xml
cp seed/first.xml mq/first.xml
$MVN -B quarkus:dev -Dquarkus.http.port=$PORT -Dquarkus.console.enabled=false -Ddebug=false \
    -Dquarkus.analytics.disabled=true "$@" > "results/raw/dev-$label.log" 2>&1 &
DEV_PID=$!
trap 'kill $DEV_PID 2>/dev/null || true; sleep 2; pkill -P $DEV_PID 2>/dev/null || true' EXIT
for i in $(seq 1 90); do curl -s -o /dev/null "http://localhost:$PORT/status" && break; sleep 2; done
sleep 2
MQ_BASE=http://localhost:$PORT node measure.mjs "$PWD/mq" "$label"
