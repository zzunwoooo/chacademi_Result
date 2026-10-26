#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

JAVA_COMMAND="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVAC_COMMAND="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
if command -v "$JAVAC_COMMAND" >/dev/null 2>&1; then
    compiler=("$JAVAC_COMMAND")
    target=(--release 21)
else
    # Some slim Java installations keep jdk.compiler without the javac launcher.
    compiler=("$JAVA_COMMAND" -m jdk.compiler/com.sun.tools.javac.Main)
    # These may omit ct.sym, which --release needs. The Java 21 compiler still
    # compiles against its own Java 21 runtime with an explicit source/target.
    target=(-source 21 -target 21)
fi

mkdir -p build/manual-test-classes
find src/main/java src/test/java -name '*.java' -print | LC_ALL=C sort > build/manual-test-sources.txt
"${compiler[@]}" "${target[@]}" -Xlint:all -Werror -d build/manual-test-classes @build/manual-test-sources.txt
"$JAVA_COMMAND" -cp build/manual-test-classes dev.portablevfx.protocol.ProtocolTests
