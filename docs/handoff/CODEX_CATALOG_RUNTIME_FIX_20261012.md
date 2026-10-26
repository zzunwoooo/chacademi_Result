# Catalog gameplay startup fix with Story queue candidate - 2026-10-12

Status: deployed to school/wild after explicit user restart approval on 2026-10-12. Source/build revision 5f1ab33e84a7687cf41059c2c1cb51efcf2d2774. Hosting Java21 tests/build passed. Earlier candidate-only statements below describe preparation; see deployment verification at the end. Based on c52245f50a82122ae2c61a7de77eab836c22b764 (includes Story queue fix). No runtime YAML/JAR replacement, restart, forced client close, world effects, DB/key changes or paid calls.

## Proven common gameplay failure

Both running servers use catalog-mode:true. The deployed Bridge JAR b5d6addca2039bcd26d68c981867bdf17c1ea0079ffffb2215bf6a79bd28a30b had onEnable guarded by !mana.isCatalogMode() before new SpellRuntime. Confirmed with deployed javap bytecode: isCatalogMode then ifne skips runtime construction. Consequently spellRuntime was null. Normal /magic and slot casting entered runtimeCast, produced no Plan, and CatalogGameplayFlow with routed=false returned the VFX result before execute. This exactly explains working VFX with all routed gameplay absent, without needing an exception or a wrong WorldGuard flag. The user's Mythic test command was incidental, not blamed as the common cause.

Read-only runtime audit: each backend spell-runtime.yml has89 routes (74 nonutility including feather_step alias,15 Bridge utilities),88 unique CHA_ skill definitions with no duplicate CHA_ IDs and no missing nonutility route definitions. Mythic startup/reload logs report375 total skills. Current generated implementation table has72 addon gameplay entries,15 Bridge utilities,1 decorative entry and8 marked deferred in that table; wind_message is implemented separately in Bridge, so table-deferred is not a claim that every one is globally unavailable. No magic plugin enable/parse errors or explicit chacademi-magic deny evidence found. These counts are installation evidence, not proof every gameplay mechanic passed live acceptance.

## Change

Initialize real SpellRuntime whenever MythicMobs is enabled, irrespective of catalog mode. Keep catalog-mode true, spell permissions, mana/cooldown policy, discovery, VFX, target preparation, NPC/pet exclusions, WorldGuard/PvP and fallback behavior. Startup reports loaded route count and catalog mode. Missing dependency is diagnosed; unrouted IDs report once and retain prior VFX/command fallback. Malformed runtime configuration retains the existing startup failure behavior. No automatic runtime YAML overwrite and no protection changes.

## Regression evidence

CatalogStartupRuntimeTest invokes the actual MagicCodexBridge.onEnable(), real SpellRuntime constructor/config loader, actual runtimeCast/prepare/execute and CatalogGameplayFlow. It mocks unrelated startup services plus Bukkit/Mythic transport and the visual renderer. It is not a full live server or rendered game. Catalog=true and legacy=false cases verify ordered prepare -> VFX -> execute, actual Mythic call target/location lists, and magic_power42/damage0/magic_haste3 variable callback. Region/target preparation refusal never reaches VFX or execute. Missing Mythic dependency keeps the existing fallback and does not execute gameplay. Existing target/protection/mana/refund tests remain unchanged and passing.

Red/green proof on hosting: restoring the old !catalog startup condition caused this actual-startup test to fail an assertion (1 test,1 failure; not a compile/dependency failure). Restoring the fix passed the entire Bridge suite:300 tests,0 failures/errors/skips. Logs in catalog-runtime-fix-20261012/old-condition.log and final-bridge.log; red-green.json contains old_exit1/fixed_exit0.

Story queue candidate from c52245f remains included:64 server tests +45 mod integration tests =109, all passing. The prior missing test cutscene stayed active/parked and blocked direct/tutorial requests; user stop released it, with actual client tutorial loads at19:48:35 and19:49:13.0.1.3 cancels only failed requests without rewards and advances queue. Missing/broken/client load-timeout failures and exhausted interruption retries are covered by cancellation behavior; no new arbitrary wall-clock timeout is imposed on valid long or menu-suspended playback. Disconnect preserves legitimate resume and resets transient state. Existing same-ID cutscene wire messages have no per-play nonce; the patch only guarantees different-ID rejection and Request-identity matching, not a new protocol-level nonce. See CODEX_STORY_QUEUE_FIX_20261012.md for full lifecycle/109-test scope.

Combined checked suites:409 tests. No claim of live acceptance of the candidate JARs. Public Git sources/tests/docs only; private assets remain excluded.

## Candidate artifacts and minimum application

Directory on hosting: C:/Chacademi/staging/magic-protection-20261011-task5/catalog-runtime-fix-20261012

- `magic-codex-bridge-0.30.0-catalog.alpha.1-chat.3-discovery.2-holytaming.1-partyhud.3-taming.1-runtime.1.jar` SHA256 `a239d0f29b8be21e2742a56923eafa755acf29cb4b6cfe94bb83ad5e753c1ef7`
- `chacademy-story-plugin-0.1.3.jar` SHA256 `870d48d3227ef655e0c2e62db78712c36a4ad9a45df972d431c70c2ac7c6e7e6`

On a separately authorized normal maintenance restart with fresh zero-player checks, replace only these two server JARs on school/wild after verified backup. Preserve current server_command.yml, spell-runtime.yml, mana-spells.yml and all progress/reward/user data. No client UI/Story replacement, launcher/feed, MagicMythic or model pack replacement is required. Config reload alone cannot install these Java changes. Runtime remains the previous Bridge + Story0.1.2 until that operation is authorized.

## Deployment verification (2026-10-12, host time20:20-20:23)

User explicitly approved school/wild restart. School had1 player, wild0; a30-second game notice preceded exact Stop-Network.ps1 -Server school and -Server wild calls. Both completed normal save/I/O shutdown. Control -Action Stop/all was not used. Backup: C:/Chacademi/backups/catalog-story-fix-20261012-task5 (verified original twoJARs, runtime/cost/Story command YAML; post-stop latest Story progress copied). Replaced only the two candidate JARs listed above; SHA256 matches on both backends. User YAML files matched backup hashes exactly. No client/launcher/Velocity replacement.

Both servers logged at20:21:46: Spell gameplay runtime ready:89 routes; catalog=true. Story0.1.3 enabled at20:21:48, commands loaded successfully. Mythic loaded375 skills again at20:21:54 after addon enable. Done:school20:21:51,wild20:21:50. SchoolPID7600/25566,wildPID7224/25567; unchanged VelocityPID7552/25565. No Bridge/Story/MagicMythic enable error observed. Existing wild ItemsAdder output/generated.zip missing error remains unrelated.

Cutscene failed-request cancellation code is now installed. No deliberate missing-cutscene or damage cast was run against a live player/NPC/pet; runtime init and skill loading are live verification, while gameplay/queue regression evidence remains the409 tests and prior user's stop-recovery observation. No claim of full live mechanics acceptance.
