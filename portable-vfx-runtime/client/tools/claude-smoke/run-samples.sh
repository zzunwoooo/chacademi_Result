#!/usr/bin/env bash
set -euo pipefail
CLIENT="$(cd "$(dirname "$0")/../.." && pwd)"
GSON="${1:?Gson jar required}"
SAMPLES="${2:-$CLIENT/src/test/resources/claude/samples}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -proc:none -source 21 -target 21 -cp "$GSON" -d "$OUT" "$CLIENT/src/main/java/dev/portablevfx/client/claude/ClaudeEffect.java" \
 "$CLIENT/src/main/java/dev/portablevfx/client/claude/ClaudeLifecycle.java" "$CLIENT/src/main/java/dev/portablevfx/client/claude/ClaudeSimulation.java" "$CLIENT/src/main/java/dev/portablevfx/client/claude/ClaudeGeometry.java" "$CLIENT/tools/claude-smoke/ClaudeSchemaSamplesTest.java"
"$JAVA" -cp "$OUT:$GSON" ClaudeSchemaSamplesTest "$SAMPLES"
