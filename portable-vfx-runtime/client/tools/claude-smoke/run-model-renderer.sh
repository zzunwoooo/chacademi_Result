#!/usr/bin/env bash
set -euo pipefail
CLIENT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${CLAUDE_MODEL_DIR:?Path to original WaterElemental folder with vfx.json models and textures}"
: "${CLAUDE_RENDER_CP:?Official Gson/JOML/LWJGL core opengl egl and native classpath}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
SOURCES=("$CLIENT/src/main/java/dev/portablevfx/client/render/EffectBackend.java" "$CLIENT/src/main/java/dev/portablevfx/client/render/gl/GlStateSnapshot.java" "$CLIENT/src/main/java/dev/portablevfx/client/render/gl/WorldTargetPass.java")
for NAME in ClaudeEffect ClaudeLifecycle ClaudeSimulation ClaudeTexture ClaudeGeometry ClaudeBackend ClaudePostPass ClaudeBloomConfig ClaudeModel ClaudeModelLoader ClaudeModelAnimator ClaudeModelRenderer; do SOURCES+=("$CLIENT/src/main/java/dev/portablevfx/client/claude/$NAME.java"); done
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 21 -target 21 -cp "$CLAUDE_RENDER_CP" -d "$OUT" "${SOURCES[@]}" "$CLIENT/tools/claude-smoke/ClaudeModelGlSmoke.java"
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=true MESA_SHADER_CACHE_DISABLE=true MESA_GL_VERSION_OVERRIDE=3.3 MESA_GLSL_VERSION_OVERRIDE=330 "$JAVA" -cp "$OUT:$CLAUDE_RENDER_CP" ClaudeModelGlSmoke "$CLAUDE_MODEL_DIR" "${CLAUDE_SMOKE_OUTPUT:-/tmp/claude-vfx-model-smoke}"
