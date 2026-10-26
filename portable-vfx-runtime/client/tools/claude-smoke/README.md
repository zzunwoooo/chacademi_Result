# Claude VFX CPU smoke tests

These tests compile the real parser, particle simulator, config loader and attachment-frame code against Gson and run against the original Fireball handoff folder. They do not launch Minecraft or require a graphics device.

Requirements: Java 21 with the compiler module and an official Gson JAR (tested with 2.11.0).

```sh
bash client/tools/claude-smoke/run.sh /path/to/gson-2.11.0.jar /path/to/effects/Fireball /path/to/test-output
```

The script makes temporary classes, writes logs and source hashes to the selected output directory, and cleans up the temporary classes. The test copies fixtures into a temporary directory and removes them afterward; original handoff files are never changed.

Coverage:
- Real Fireball: projectile and impact definitions, all ten material textures, system selection, bounded lifetimes.
- Relative path traversal, absolute paths, URL and Windows-style paths, empty components and byte-limit boundaries.
- Symlink root, effect directory, nested asset directory and file rejection.
- Missing files, oversized PNG/config JSON and case-insensitive folder/texture collisions.
- Valid PNGs exceeding the 2048-pixel dimension limit or aggregate decoded-texture budget. The original Fireball is admitted with its 17.375 MiB of decoded RGBA textures.
- Unsupported schema major, malformed JSON and atomic per-folder admission.
- Deterministic emission counts, fixed-step partitioning, transform/velocity semantics, seeded ranges, impact residue, curve evaluation, noise, flipbooks and trails.
- Ground, wall and vertical orientation, parallel incoming-direction fallback, orthonormality, handedness and invalid vector rejection.

Passing this suite establishes CPU-side behavior only. Minecraft integration, shader execution, depth composition and visual fidelity require separate checks.

## Renderer verification

The separate `run-renderer.sh` compiles the real renderer with Gson, JOML and LWJGL dependencies, runs backend lifecycle/geometry tests, and performs Mesa/EGL OpenGL 3.3 smoke renders. See `docs/CLAUDE-RENDER-VERIFICATION.md` for exact inputs, dependency requirements, results and limitations. This remains distinct from an in-game Minecraft test.
