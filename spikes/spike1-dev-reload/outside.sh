#!/usr/bin/env bash
# THROWAWAY: scenario 8, resource folder outside src/main/resources.
cd "$(dirname "$0")"
stop() { powershell -c "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object CommandLine -like '*quarkus*' | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1; sleep 3; }
mkdir -p outside/mq
rm -f app/src/main/resources/mq/*.xml   # only the outside folder may supply resources in these runs
export MQ_DIR_ARG=outside/mq
stop
# 1. own watcher on an arbitrary folder (-Dmq.dir): independent of Quarkus' resource roots
./run-mode.sh out-watch watcher -Dmq.watch=true "-Dmq.dir=$PWD/outside/mq";  stop
# 2. Quarkus scan (watcher calls doScan) on a folder that is NOT a resource root
./run-mode.sh out-doscan-notroot watcher -Dmq.watch=doscan "-Dmq.dir=$PWD/outside/mq";  stop
# 3. the folder made a Maven resource root (-Pextra-root): Quarkus watches it; watcher calls doScan
./run-mode.sh out-doscan-extraroot watcher -Pextra-root "-Dextra.root=$PWD/outside" -Dmq.watch=doscan;  stop
# 4. same extra root, request-triggered scan only
./run-mode.sh out-http-extraroot http -Pextra-root "-Dextra.root=$PWD/outside";  stop
echo OUTSIDE-DONE
