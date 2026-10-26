# Claude animated GLB models

The model path renders the authored glTF mesh hierarchy. It does not replace models with billboards or Minecraft cube models.

## CPU API

- `ClaudeModelLoader.load(byte[])` returns immutable `ClaudeModel` data. The overload taking `Limits` restricts encoded bytes, retained allocation, nodes, primitives, accessors, vertices, indices and animation keys.
- `ClaudeModelLoader.load(relativePath, dependencies)` accepts only a bounded safe `models/.../*.glb` path. GLB files may not load external buffers or images.
- `ClaudeModel` exposes parent-first `sceneOrder()`, nodes, meshes, read-only primitive buffers, material alpha flags, embedded PNG/RGBA data, texture samplers and named clips.
- `ClaudeModelAnimator.sample(model, clip, seconds, loop)` returns per-node JOML global transforms. A null clip is the rest pose. Seconds are `particle.age * animation.speed`. Looping wraps; non-looping clamps. Translation/scale interpolate linearly and rotations use quaternion SLERP.

The GLB profile accepts one embedded binary buffer, one complete node-TRS scene, triangle primitives, POSITION/NORMAL/COLOR_0/TEXCOORD_0, embedded validated PNG base-color images, material alpha modes and LINEAR node-TRS animation. External URIs, skins, morphs, matrix-authored nodes, non-triangle modes, unsupported attributes/extensions and non-LINEAR clips are rejected explicitly. Bounds are checked before accessor and image allocation. Strict JSON rejects duplicate keys, comments, excess nesting, huge strings and non-finite/unbounded values.

Conversion is applied exactly once: X reflection for positions/normals/translations, reversed triangle winding and quaternion `(x, -y, -z, w)`. UVs become bottom-left to match the existing bounded PNG decoder's bottom-to-top RGBA storage. Public buffers are read-only.

## Rendering

`ClaudeModelRenderer` uploads static vertex/index buffers, embedded images with mipmaps and authored sampler settings. `ClaudeBackend` shares model data/GPU handles by digest across system leases, preserving preparation-cache and resident byte limits. Reload/close releases these leases; stopping instances preserves warm resources.

Model placement is:

`translation(particle.worldPosition - worldOrigin) * basis * widthX * Ry * Rx * Rz * uniform(particle.size) * nodeGlobal`

The simulation has already applied instance scale to particle size/position. The renderer does not scale it again. Normals use the inverse-transpose matrix, including non-uniform width and node scaling.

The dedicated shader implements schema Lambert/toon, wrapped diffuse, shadow tint, rim, unlit materials, base-color images, and the translucent Fresnel alpha rule. Opaque primitives draw before inverted-hull outlines and BLEND primitives. Fading opaque models first populate depth with a color-disabled pass to prevent inner surfaces showing through. Model depth writes go into the effect's private retained-world-depth copy; the host depth attachment is not changed.

Loading-screen GPU preparation is split into one image, sampler or primitive upload per step. Private depth preparation uses a valid bound target when possible. `ClaudeBackend.prewarmWorldDepth(framebuffer, width, height)` is called before the renderer's empty-instance early return, so the first valid world frame can prepare the real depth format. `worldDepthPending()` remains true when no suitable depth target is available; there is no claim that an unavailable world depth target was prepared.

## Verification

- `ClaudeModelLoaderTest`: synthetic container/accessor/path/hierarchy/animation/image failures, conversion, sampler pixels, read-only buffers and authentic body/dolphin inputs.
- `ClaudeModelBackendCacheTest`: six-system shared model leases and failed clip validation without GPU context.
- Authentic fixture tests use `CLAUDE_MODEL_FIXTURE_DIR`; they do not duplicate the large original GLBs in source control.
- `client/tools/claude-smoke/run-model-renderer.sh`: real Mesa OpenGL 3.3 shader/render checks, six phases, bloom on/off, retained-depth occlusion, GL state restoration, first-cast cache-work counters and cleanup. Set `CLAUDE_MODEL_DIR`, `CLAUDE_RENDER_CP`, `JAVA_HOME`, and optionally `CLAUDE_SMOKE_OUTPUT`.

The original six-pack girl/dolphin asset is retained only as an additional verification input. The release asset is the separately confirmed all97 bird revision, with its matching JSON/GLB/textures. Standalone CPU/GL tests do not certify Minecraft/Iris in-game appearance or multiplayer gameplay.

Reference: [Khronos glTF 2.0 specification](https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html), plus the supplied WaterElemental SCHEMA.md sections 2 and 4.10.
