#!/usr/bin/env bash
set -euo pipefail
CLIENT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${CLAUDE_EFFECT_DIR:?Point CLAUDE_EFFECT_DIR to the real Fireball folder containing vfx.json and textures}"
: "${CLAUDE_RENDER_CP:?Classpath needs official Gson, JOML, LWJGL core/opengl/egl and Linux native jars}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
SOURCES=("$CLIENT/src/main/java/dev/portablevfx/client/render/EffectBackend.java"
 "$CLIENT/src/main/java/dev/portablevfx/client/render/gl/GlStateSnapshot.java"
 "$CLIENT/src/main/java/dev/portablevfx/client/render/gl/WorldTargetPass.java")
for NAME in ClaudeEffect ClaudeLifecycle ClaudeSimulation ClaudeTexture ClaudeGeometry ClaudeBackend ClaudePostPass ClaudeBloomConfig ClaudeModel ClaudeModelLoader ClaudeModelAnimator ClaudeModelRenderer; do
 SOURCES+=("$CLIENT/src/main/java/dev/portablevfx/client/claude/$NAME.java")
done
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -source 21 -target 21 -cp "$CLAUDE_RENDER_CP" -d "$OUT" \
 "${SOURCES[@]}" "$CLIENT/tools/claude-smoke/ClaudeRendererTest.java" "$CLIENT/tools/claude-smoke/ClaudeFlipbookGlTest.java" "$CLIENT/tools/claude-smoke/ClaudeCoverageGlTest.java" "$CLIENT/tools/claude-smoke/ClaudeDepthGlTest.java" "$CLIENT/tools/claude-smoke/ClaudeGlSmoke.java"
"$JAVA" -cp "$OUT:$CLAUDE_RENDER_CP" dev.portablevfx.client.claude.ClaudeRendererTest "$CLAUDE_EFFECT_DIR"
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=true MESA_SHADER_CACHE_DISABLE=true \
 MESA_GL_VERSION_OVERRIDE=3.3 MESA_GLSL_VERSION_OVERRIDE=330 \
 "$JAVA" -cp "$OUT:$CLAUDE_RENDER_CP" ClaudeGlSmoke "$CLAUDE_EFFECT_DIR" "${CLAUDE_SMOKE_OUTPUT:-/tmp/claude-vfx-render-smoke}"
