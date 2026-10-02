#!/usr/bin/env bash
# THROWAWAY spike code. Starts the sample app in Quarkus dev mode and runs measure.mjs against it.
#   usage: run-mode.sh <label> <TRIGGER> [extra -D args for dev mode]
#   TRIGGER: http    requests trigger Quarkus' (throttled) scan
#            scan    call the app's /scan (HotReplacementContext.doScan) right after each file operation
#            watcher the extension's own watcher (start with -Dmq.watch=doscan or -Dmq.watch=true)
# needs: JDK 17+ on PATH or JAVA_HOME, Maven 3.9+ as $MVN (default: mvn), node 18+
set -e
cd "$(dirname "$0")"
label=$1; trigger=$2; shift 2
MVN=${MVN:-mvn}
PORT=${PORT:-8090}
DIR=${MQ_DIR_ARG:-app/src/main/resources/mq}
mkdir -p "$DIR" results/raw
rm -f "$DIR"/*.xml
echo '<Resource v="1.0"><Desc>first-v1</Desc></Resource>' > "$DIR/first.xml"
( cd app && $MVN -B quarkus:dev -Dquarkus.http.port=$PORT -Dquarkus.console.enabled=false -Ddebug=false \
    -Dquarkus.analytics.disabled=true "$@" > "../results/raw/dev-$label.log" 2>&1 & )
for i in $(seq 1 90); do curl -s -o /dev/null "http://localhost:$PORT/status" && break; sleep 2; done
sleep 2
MQ_BASE=http://localhost:$PORT TRIGGER=$trigger node measure.mjs "$PWD/$DIR" "$label"
