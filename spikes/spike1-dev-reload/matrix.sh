#!/usr/bin/env bash
# THROWAWAY: runs the in-resource-root matrix (three ways of noticing a file change), one dev-mode start each.
cd "$(dirname "$0")"
stop() { powershell -c "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object CommandLine -like '*quarkus*' | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1; sleep 3; }
stop; ./run-mode.sh http http;                      stop
./run-mode.sh doscan watcher -Dmq.watch=doscan;      stop
./run-mode.sh watch watcher -Dmq.watch=true;         stop
echo MATRIX-DONE
