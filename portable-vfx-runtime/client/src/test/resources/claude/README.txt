fireball-vfx.json is the authentic Fireball vfx.json received with the Claude
Fireball schema/texture package, minified without semantic changes.
Original JSON SHA-256: 559437ebcb62712255b9d5b097d52d9aecb6372b4d29b0ba363c759229a492bc

EffectLibraryConfigIntegrationTest generates valid PNGs at the original ten
texture dimensions. Their combined decoded footprint is exactly 4,554,752
texels. Texture artwork is not required for resource admission testing.

The test loads all production assets from src/main/resources through the test
runtime classpath. No external fixture directory, running Minecraft client,
FabricLoader config directory, GPU context, or network access is required.
