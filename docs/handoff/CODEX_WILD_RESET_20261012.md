# Wild overworld reset and server deployment - 2026-10-12

## Completed scope and authorization

User explicitly approved installing project-owned ChAcademiaWildlife, then requested resetting the existing wild world and installing the datapack. Only `C:/Chacademi/network/wild/world` was reset. School world, wild nether/end, player-property databases, personal launcher and public launcher release/feed were excluded. Fresh player queries confirmed zero players before each normal shutdown. No forced shutdown or permanent world deletion occurred.

## Backup and player-data preservation

Complete old world moved to `C:/Chacademi/backups/wild-reset-20261012-task5/world`. All 29 files / 17,385,857 bytes matched SHA256 before and after the move, before the replacement world directory was created. Original server properties, Multiverse world registry and replaced school/wild Bridge JARs are alongside this backup. Existing backups were not overwritten. Private manifests: `world-manifest.json`, `BACKUP_VERIFIED.json`, `player-preservation.json`, `worldgen-verification.json`, `installed-files.json`.

Copied existing world UUID, playerdata, statistics, advancements, relevant scoreboard/map data and Paper world settings. No old chunks/entities/POI were copied into the reset world. HuskSync safe-key inspection confirmed inventory/ender-chest synchronization enabled and location synchronization disabled. Its credentials and database contents were not read. One primary player NBT file used the overworld: only Pos, Motion, FallDistance and obsolete overworld respawn-location fields were changed. Every other original NBT field was verified byte-identical, including inventory, ender items, XP, health, food and abilities. Original player files including prior backup remain intact in the archived whole world. No inventory, money, ownership or synchronization DB was reset or directly modified.

World UUID retained to avoid breaking UUID references. Nether/end UUIDs AND original world-generation seeds were verified unchanged. Main world seed changed. Old coordinate-based warps/regions can still point at different terrain despite stable UUID; none were mass-rewritten. No Essentials warp directory or WorldGuard world regions file was found in the inspected paths. Custom/database-backed coordinates were not exhaustively audited. Multiverse's overworld seed/spawn were updated to the generated world; its other dimensions were retained.

## Actual worldgen and spawn evidence

Before first generation, installed `Chacademia-Terralith-1.21.4-v3.zip`, SHA256 `680033737bd06a5287f5f3f6a13b426d62d474539bd2637a6d2897141ab4a78e`. This SINGLE integrated pack includes Terralith2.5.8, 585 structure NBT assets and the Chacademia natural/landmark structure definitions. No duplicate standalone Terralith or extra structure pack is needed. Pack format61 / MC1.21.4. Original generation densities and exclusions were preserved; see preceding HUD handoff for exact v3 values.

Final `datapack list enabled`: vanilla, file/Chacademia-Terralith-1.21.4-v3.zip, file/bukkit, paper. Inspected 954 saved generated/proto chunks; palettes contain actual `terralith:cave/mantle_caves` and `terralith:cave/granite_caves`. Vanilla namespaced locate found `chacademia:ruined_gate` at x=-1808,z=80, 1809 blocks away (554ms). This confirms the registered natural structure lookup, not a visual inspection of its blocks. The first unqualified locate was intercepted by another plugin; namespaced Minecraft locate succeeded.

Safe spawn x=0.5,y=68,z=0.5: placed a 5x5 smooth-stone platform over new terrain, cleared three blocks of headroom and set spawnRadius0. Console block checks verified solid floor plus air at feet/head, including after final restart. Existing local overworld player position and Multiverse spawn now use this point. The temporary maintenance whitelist setting was restored to its original value before public restart.

## Wildlife and server artifacts

Installed audited current-source `chacademia-wildlife-0.3.0.jar` on WILD ONLY, SHA256 `dde86b1c8c08e59d1a49e0530fac16cfd5a8c25f25c56d9e97420c02948d33bb`. Original 138 habitats/random-spawn rules copied without tuning; all 276 normal/shiny mob IDs exist. No duplicate CA_Natural rule IDs. Mythic reports 276 random-spawn entries after reload. Wildlife enables as0.3.0; no unknown cahabitat condition or Wildlife load failure observed. First natural attempts remain Mythic-controlled; Wildlife performs habitat/population/season/structure gating, groups/shiny and cleanup. Chance/Cooldown pairs remain0.045/20,0.018/60,0.004/240,0.0006/1800; shiny4095:1 and group denominator4096. Actual player-driven natural spawns, seasonal transitions and all138 species were NOT exercised with real players; do not call this full gameplay acceptance.

School and wild Bridge both upgraded to `0.30.0-catalog.alpha.1-chat.3-discovery.2-holytaming.1-partyhud.3`, SHA256 `61f29ee3f779f6b204edac62481ba3d09cdb20e104db81fb855ef45fb3ce0a8f`. Discovery, Velocity, VFX/Mythic addon, Story, portrait/Luna/transparency and other plugins were not replaced. Normal startup completed on school/wild/Velocity; ports25565/25566/25567 listen. Mythic375 skills; PortableVFX96 visual bindings/0 skipped. Existing unrelated warnings remain: SkeletalKnight load warning and missing wild ItemsAdder output/generated.zip. These were not introduced or fixed here.

## Retired temporary dialogue editor

Removed Fabric DialogueAdminScreen and DialogueAdminClient plus initialization and parent-screen coupling. Server no longer emits admin editor payloads; old editor requests receive help and cannot mutate data. Bare admin command shows text help. Ordinary dialogue/preview, quest journal, YAML reload/pull, audit/recovery commands and all dialogue storage remain. Story/NpcTalk sources are untouched. Older installed clients remain compatible; they receive no retired editor-opening response.

Hosting Java21 builds/tests: Paper292, Fabric240, chat136 (668 total, zero failures). Current Wildlife builds, with no test sources. Changed dialogue visual fixtures compile in isolation; the broad optional visual suite has pre-existing missing VFX fixture classes, so it is not claimed passing. No live visual game test was run.

## Launcher explicitly held

Final client staging: UI `0.39.0-catalog.alpha.2-chat.3-holytaming.1-partyhud.4+mc1.21.4` SHA256 `15c87c55845c33ff5ee78839aa6d1f4aaa72c0e50c6cb54a4f1c3442d9197b95`, chat-layout1.6.0 SHA256 `b9d614a5026127cbf1d08444e478458b3e696121eacf48edf9032fc5665b8b5a`. Folder: `C:/Chacademi/staging/magic-protection-20261011-task5/hudfix-validation-20261012/artifacts/`. Personal mods/resources, public installer/Release/distribution/feed and launcher Git were not modified. Client-only icon/search/layout/tab improvements await the user-authorized later client replacement; current wire versions remain compatible.

## Rollback boundary

Stop normally after confirming zero players. Preserve the newly generated world under a NEW backup name; never delete it. Restore the archived world, original properties/Multiverse registration and prior Bridge JARs; move newly installed Wildlife/config/randomspawns to a separate backup. Do not blindly overwrite post-deployment player data or property databases during a later rollback: retain/reconcile current player progress first. No rollback was performed.
