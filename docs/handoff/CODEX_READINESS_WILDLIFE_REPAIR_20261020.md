# Readiness transport and wildlife compatibility repair - 2026-10-20

Operating baseline eede1bc (fish shop/tank v0.1.18). No mechanics, icons, VFX art, maps, existing shop/NPC bindings or spawn caps replaced.

## Readiness
Actual1245-ID old payload38190 bytes exceeded the operating Purpur1.21.4 C2S32767-byte decoder. Paper Messenger.MAX_MESSAGE_SIZE=1048576 is a different later API boundary. Legacy readiness remains bounded and unchanged; new peers use catalog_ready_v2 and server-issued catalog_epoch.

New protocol: max4096 effects,512KiB total,19 chunks,28072 bytes max packet; complete SHA256/count/sorted unique validated inventory only. Server nonce rotates on disconnect/catalog reload; transfer generations prevent old/replayed chunks. Fixed200tick timeout, identical duplicates accepted, conflicting duplicates rejected, one transfer per player, global64 transfers,32-packet burst and bounded refill. New partial generations withdraw readiness immediately. No readiness or permission bypass. Client sends one chunk per tick and invalidates on resource/connection changes. Timeout-before-tick slot-release race fixed and regression-tested. Actual fixture canonical38188 bytes =>28072+10260 packets. Future4096 boundary and malformed/ordering/nonce/timeout/fuzz tests pass.

Paper143 tests and client114 tests pass; common protocol harness passes including2125 new v2 assertions. Version3.2.2-integrated-20261020.1-readychunks.1. Managed preload system limits raised1237->4096 to include all1245 current effects; encoded512MiB and GPU1024MiB budgets retained.

## Wildlife
Actual earlier player location was vanilla plains; all138 configured habitats allowed Terralith-only biomes. New explicit54-biome ecological compatibility map preserves existing habitat/time/season/terrain/chance/24-80distance/chunk6/area24 policy. Vanilla caves recognized. Wildlife version0.4.1-integrated-20261020.2-biomes.1,30 tests pass. New read-only `/wildlife diagnose <player>` and bounded `Natural wildlife confirmed` logs support real spawn verification. This compatibility repair alone is NOT proof of complete natural spawn recovery: user reported other biomes too, shared blockers remain under investigation; online wild player and actual naturally generated valid entity evidence still required.

## Applied server files
Three PortableVFX JARs, wild Wildlife JAR and new biome-compatibility.yml applied. Protected114 file hashes match, all three servers ready, Velocity PID10260 unchanged. No config/habitats/RandomSpawns cap rewrite, backup, map copy, forced client closure or gameplay capture. Detailed artifacts C:/Chacademi/artifacts/readiness-wildlife-fix-20261020.

## F3+B correction
During active Kyudichi/duel matches, including tests and spectators, hitboxes cannot be enabled and an existing enabled state is turned off. No admin/test exemption. Outside a match they remain available.

## Common spawn gate follow-up

Actual Mythic5.9 RandomSpawnGenerator/ClusterGenerator evaluates ILocationCondition on its async scheduler before synchronous spawn. SYNC_ONLY does not move this path to the main thread; old check rejected every such call. Added bounded main-thread bridge16pending/250ms, deadline/timeout/cancel and reload/disable generation checks. BukkitAdapter/world reads stay on main; main never waits. Wildlife43 tests and independent review pass. Version0.4.1-integrated-20261020.3-spawngate.1 deployed to wild only, SHA2568685fb9205dce6eff7f8c31789b367c1bc89c49b81fa8acc3bf022e0e7550da1, PID13988,118 protected hashes match. Other servers unchanged. No online wild players at verification, so actual naturally generated entity evidence remains pending.

VFX repair also deployed to personal client and public launcher v0.1.19,79854212eb98c393e11cbcb13081da18ae2627da;9 release digests, public latest feed/updater verified. Personal VFX hash2c4a83f13cdb716660824315b1da795c9c539ed3c274a87b2f3921d9e30bc969;14 other mods/options preserved, only two preload count fields1237->4096. Client reconnect QA pending.
