#!/usr/bin/env bash
# The CLI's script step, prototype: compiles <scripts dir>/*.kts ahead of time into one jar.
#   usage: compile-scripts.sh <scripts dir> <output jar>
#   env:   KOTLINC_HOME   unpacked Kotlin compiler distribution (2.2.20)
#          MQ_JARS        jars every script may use: mq-script, mq-engine, kotlin-stdlib (path-separated)
#          LIBS           the libraries this project declares for its scripts (path-separated, optional)
# A script that imports anything that is not on MQ_JARS or LIBS does not compile: that list is the declaration of what scripts may use.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
scripts=$1; jar=$2
KC=${KOTLINC_HOME:?set KOTLINC_HOME}
SEP=:
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';';; esac
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
JAR=${JAVA_HOME:+$JAVA_HOME/bin/}jar
work=$(mktemp -d)
command -v cygpath >/dev/null && work=$(cygpath -m "$work")
CP="${MQ_JARS:?set MQ_JARS}${LIBS:+$SEP$LIBS}"
KCP="$KC/lib/kotlin-compiler.jar${SEP}$KC/lib/kotlin-stdlib.jar${SEP}$KC/lib/kotlin-script-runtime.jar${SEP}$KC/lib/kotlin-reflect.jar"
KCP="$KCP${SEP}$KC/lib/trove4j.jar${SEP}$KC/lib/annotations-13.0.jar${SEP}$KC/lib/kotlinx-coroutines-core-jvm.jar"
KCP="$KCP${SEP}$KC/lib/kotlin-scripting-compiler.jar${SEP}$KC/lib/kotlin-scripting-compiler-impl.jar"
KCP="$KCP${SEP}$KC/lib/kotlin-scripting-common.jar${SEP}$KC/lib/kotlin-scripting-jvm.jar"
mkdir -p "$work/classes"
files=$(ls "$scripts"/*.kts)
"$JAVA" -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -Xuse-fir-lt=false -Xallow-any-scripts-in-source-roots \
  -Xplugin="$KC/lib/kotlin-scripting-compiler.jar" -P plugin:kotlin.scripting:script-templates=io.mq.script.MqScript \
  -Xplugin="$KC/lib/kotlinx-serialization-compiler-plugin.jar" \
  -cp "$CP" $files -d "$work/classes"
node "$here/gen-registry.mjs" "$scripts" "$work/gen/MqCompiledScripts.java"
"$JAVAC" -nowarn -cp "$CP${SEP}$work/classes" -d "$work/classes" "$work/gen/MqCompiledScripts.java"
mkdir -p "$work/classes/META-INF/services"
printf 'MqCompiledScripts\n' > "$work/classes/META-INF/services/io.mq.script.ScriptLoader"
rm -f "$jar"
"$JAR" cf "$jar" -C "$work/classes" .
echo "wrote $jar ($(find "$work/classes" -name '*.class' | wc -l) classes)"
