#!/usr/bin/env bash
# THROWAWAY (Spike 3, question A option 1): start N copies of the hosting app, one backend each, report start time and memory.
# usage (from spikes/spike3-hosting): ci/processes.sh jvm|native N
set -e
MODE=$1; N=$2
BIN=./target/spike3-hosting-1.0.0-SNAPSHOT-runner
PIDS=()
START=$(date +%s%N)
for i in $(seq 1 "$N"); do
  d=/tmp/proc-backends-$MODE-$i; mkdir -p "$d/hsql"; cp sample-backends/hsql/backend.yaml "$d/hsql/"
  if [ "$MODE" = native ]; then
    env MQ_BACKENDS_DIR="$d" PORT=$((9100+i)) "$BIN" > "/tmp/proc-$MODE-$i.log" 2>&1 &
  else
    env MQ_BACKENDS_DIR="$d" PORT=$((9100+i)) java -jar target/quarkus-app/quarkus-run.jar > "/tmp/proc-$MODE-$i.log" 2>&1 &
  fi
  PIDS+=($!)
done
for i in $(seq 1 "$N"); do
  for t in $(seq 1 900); do curl -sf "localhost:$((9100+i))/mem" > /dev/null && break; sleep 0.1; done
done
END=$(date +%s%N)
sleep 3 # idle
TOTAL=0
for p in "${PIDS[@]}"; do r=$(awk '/VmRSS/{print $2}' "/proc/$p/status"); TOTAL=$((TOTAL+r)); done
echo "$MODE processes=$N all_ready_after_ms=$(( (END-START)/1000000 )) total_rss_kb=$TOTAL avg_rss_kb=$((TOTAL/N))"
for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done
wait 2>/dev/null || true
