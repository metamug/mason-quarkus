#!/usr/bin/env bash
# THROWAWAY spike: compiles the scripts ahead of time with the Kotlin compiler (what the CLI would do), into build/classes.
#   needs: JDK 17+, Maven, node, the Kotlin compiler distribution in KOTLINC_HOME
#   (an absolute path; on Windows write the drive letter with forward slashes, D:/tools/kotlinc)
set -euo pipefail
cd "$(dirname "$0")"
KC=${KOTLINC_HOME:?set KOTLINC_HOME to the unpacked kotlinc directory}
SEP=:
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';';; esac
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
rm -rf build/template build/classes
mkdir -p build/lib build/template build/classes
mvn -q -B -f deps-pom.xml dependency:copy-dependencies -DoutputDirectory=build/lib
cp "$KC"/lib/kotlin-scripting-common.jar "$KC"/lib/kotlin-scripting-jvm.jar build/lib/

# The compiler is started directly: the kotlinc wrapper script does not pass the options the script plugin needs.
KCP="$KC/lib/kotlin-compiler.jar${SEP}$KC/lib/kotlin-stdlib.jar${SEP}$KC/lib/kotlin-script-runtime.jar${SEP}$KC/lib/kotlin-reflect.jar"
KCP="$KCP${SEP}$KC/lib/trove4j.jar${SEP}$KC/lib/annotations-13.0.jar${SEP}$KC/lib/kotlinx-coroutines-core-jvm.jar"
KCP="$KCP${SEP}$KC/lib/kotlin-scripting-compiler.jar${SEP}$KC/lib/kotlin-scripting-compiler-impl.jar"
KCP="$KCP${SEP}$KC/lib/kotlin-scripting-common.jar${SEP}$KC/lib/kotlin-scripting-jvm.jar"
kotlinc() { "$JAVA" -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn "$@"; }

LIBS=$(ls build/lib/*.jar | tr '\n' "$SEP")

# 1. the script definition (the contract: params, steps, response, request)
kotlinc -no-stdlib -cp "$LIBS" src/Template.kt -d build/template

# 2. the scripts, compiled to classes. What they import must be on this class path: that is the declaration of what a script may use.
node gen.mjs scripts build/gen/ScriptRegistry.java
kotlinc -Xuse-fir-lt=false -Xallow-any-scripts-in-source-roots \
  -Xplugin="$KC/lib/kotlin-scripting-compiler.jar" -P plugin:kotlin.scripting:script-templates=io.mq.script.MqScript \
  -Xplugin="$KC/lib/kotlinx-serialization-compiler-plugin.jar" \
  -cp "$LIBS${SEP}build/template" scripts/*.kts -d build/classes

# 3. the generated registry (Java) and the runner, with the compiled scripts on the class path
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -nowarn -cp "$LIBS${SEP}build/template${SEP}build/classes" -d build/classes build/gen/ScriptRegistry.java
kotlinc -no-stdlib -cp "$LIBS${SEP}build/template${SEP}build/classes" src/Main.kt -d build/classes
echo "compiled: $(find build/classes -name '*.class' | wc -l) classes"
