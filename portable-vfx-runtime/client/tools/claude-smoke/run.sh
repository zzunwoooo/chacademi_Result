#!/usr/bin/env bash
# Real loader/model CPU checks. No Minecraft, OpenGL, Gradle, or network required.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CLIENT="$(cd "$HERE/../.." && pwd)"
REPO="$(cd "$CLIENT/.." && pwd)"
GSON="${1:-${GSON_JAR:-}}"
FIXTURE="${2:-${CLAUDE_FIREBALL_FOLDER:-}}"
OUTPUT="${3:-$HERE/validated}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
[[ -f "$GSON" && -f "$FIXTURE/vfx.json" ]] || { echo "Usage: $0 GSON_JAR FIREBALL_FOLDER [OUTPUT_DIRECTORY]" >&2; exit 2; }
CLASSES="$(mktemp -d /tmp/portablevfx-claude-smoke.XXXXXX)"
trap 'rm -rf "$CLASSES"' EXIT
mkdir -p "$OUTPUT"
# The Java 21 compiler module also works in slim runtimes without a javac launcher.
SOURCES=(
  "$CLIENT/src/main/java/dev/portablevfx/client/definition/TextureBudget.java"
  "$CLIENT/src/main/java/dev/portablevfx/client/claude/ClaudeEffect.java"
 "$CLIENT/src/main/java/dev/portablevfx/client/claude/ClaudeLifecycle.java"
  "$CLIENT/src/main/java/dev/portablevfx/client/claude/ClaudeSimulation.java"
  "$CLIENT/src/main/java/dev/portablevfx/client/claude/ClaudeFrames.java"
  "$CLIENT/src/main/java/dev/portablevfx/client/claude/ClaudeConfigLoader.java"
  "$HERE/ClaudeLoaderTest.java"
  "$HERE/ClaudeSimulationTest.java"
)
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -proc:none -source 21 -target 21 -cp "$GSON" -d "$CLASSES" "${SOURCES[@]}"
"$JAVA" -Djava.awt.headless=true -cp "$CLASSES:$GSON" ClaudeLoaderTest "$FIXTURE" 2>&1 | tee "$OUTPUT/loader-test.log"
"$JAVA" -Djava.awt.headless=true -cp "$CLASSES:$GSON" ClaudeSimulationTest "$FIXTURE/vfx.json" 2>&1 | tee "$OUTPUT/simulation-test.log"
sha256sum "${SOURCES[@]}" "$HERE/run.sh" "$GSON" "$FIXTURE/vfx.json" > "$OUTPUT/source-sha256.txt"
