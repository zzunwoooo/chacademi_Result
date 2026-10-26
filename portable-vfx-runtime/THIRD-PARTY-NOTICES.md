# Third-party notices — current Claude-only runtime

The runtime no longer distributes Effekseer, AAA Particles JNI/SWIG bindings, their native libraries, official Effekseer sample effects, or the previous Unity-scene test assets. Their historical provenance remains in archived source only; it does not describe the current client payload.

## Author-provided VFX packs

Claude-schema JSON, textures and models are supplied separately by the pack author. Inclusion in an install bundle does not grant a new license to those assets. Preserve the author's pack-specific license and provenance when redistributing a commercial configuration pack.

## Fabric/Paper/LWJGL/JOML

Fabric Loader/API, Minecraft mappings, LWJGL/JOML and Paper API are Gradle dependencies under their respective licenses. Minecraft is not redistributed. Fabric API is separately installed by the player. This mod nests only its own common protocol module; no external platform API is shaded into the server artifact. The Gradle wrapper comes from Gradle (Apache 2.0). Optional build-only Loom capability source retains its FabricMC MIT notice and is excluded from game artifacts.

No endorsement by Mojang, Fabric or Paper is claimed.
