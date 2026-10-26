# Status HUD integration and deployment — 2026-10-19

## Result

DEPLOYED_VERIFIED. Baseline 6fe06f0 was confirmed against live server and personal client hashes before changes. Integrated feature aa785a5 (three commits from that exact baseline) using a merge commit, preserving operating Kyudichi, housing, shop, broom and alchemy patches.

## Build and fixes

Fabric IdentifiedLayer.BOSSBAR does not exist in installed fabric-rendering-v1 10.2.1; verified bundled API sources and corrected to BOSS_BAR. Added seven server behavior regression tests (boss control conversion/root immunity, disarm/stun/sleep casting and artifact guards, movement lock, wake on damage, recovery seal, magic weakness and mana-burn cooldown). Existing operating-format spell YAML changed in exactly three lines; the full generated file was not deployed.

| Module | Tests | Version / JAR | SHA-256 |
|---|---:|---|---|
| bridge | 347 | magic-codex-bridge-0.31.1-integrated-20261018.1-statushud.1.jar | d5a886384964c3b15d33a5f38156e8b01c0f6432beebd7e81de8494ea6f6113d |
| mythic | 58 | chacademia-magic-mythic-0.2.0-expansion71.1-reaction.2-vfx103134.1-effects.1-carrier.1-status.1.jar | bd566fcd35f7a6aac811ec997395b1d8495d141e8951c28196d6fb1c21275695 |
| ui | 376 | magic-codex-ui-0.40.1-integrated-20261018.1-statushud.1+mc1.21.4.jar | 8cec1365381adc9eef3e5a4410185a76e8a1f9952543a11de7b82261f6ab426c |

781 tests passed; zero failures/errors/skips. Client remapJar passed; 1,521 old asset entries outside approved element replacements are byte-identical; 16 new status icons packaged.

## Installed and verified

School, wild and arena each received Bridge and MagicMythic JARs plus minimal MythicMobs skill edits: pressure_press gravity:40, pressure_hammer stun:20, abyssal_pressure physical_weakness:30 replacing mining fatigue. Original file encoding/line endings preserved. All six installed JAR hashes and three skill hashes verified. Other 94 plugin JARs (including Kyudichi, housing and server broom plugins) and three ItemsAdder generated resource packs retained by SHA-256. No server configuration credentials or DB state changed.

All four network processes started with new PIDs. Three Paper servers show exact expected versions loaded/enabled, all four answer Minecraft status packets (protocol 769), and checked fatal startup markers are absent. Earlier probe during startup timed out; post-startup probe passed. Velocity binary was unchanged. Existing Control.ps1 Stop ignores the Server selection and stops the complete network; verified all four PIDs gone and ports 25565–25568 closed, then restored the full network. Initial restart auto-review rejection was resolved by that read-only evidence.

Personal launcher: replaced one MagicCodex UI JAR; SHA-256 equals the built UI above. Fourteen other mods and options.txt unchanged. No game launch, force-close or capture.

## Latest feature semantics

aa785a5 preserves the 6504a31 behavior: remove the entire vanilla BOSS_BAR layer, including plugin boss bars, and prioritize bosses within 64 blocks; second bar shows the looked-at normal monster. aa785a5 adds uniformly centered status icons and hover descriptions/time left. Buff HUD hiding remains as specified.

## Temporary launcher

Candidate: PUBLISHED. release/v0.1.13 based on current public main d3bd464. Only MagicCodex module changed; 84 other distribution modules and all resource URLs preserved. Installer EXE/ZIP, lint, 10 launcher tests, packaged ASAR byte checks and SHA512/size/updater/blockmap checks passed.

Public v0.1.13 release and main feed update completed with explicit user approval. Release ID 416813030, commit c940587d8fa9b699cc79ba9c209a8848e5764a06. Eight uploaded SHA-256 digests verified. Actual public downloads of all eight files were independently hashed and matched, raw main distribution/payload/package JSON matched, and retained Motion/resource URLs answered successfully. Release: https://github.com/zzunwoooo/chacademi-launcher/releases/tag/v0.1.13. No server redeployment or restart occurred during this follow-up.

## Remaining limits

No live multiplayer or visual acceptance was performed. Status effects are not newly connected to every spell; approved existing mappings only. Existing frozen reaction/AI overlap risk from Claude handoff remains outside this change. No backup created and no maps copied.

## Hosting evidence

C:/Chacademi/artifacts/status-hud-20261019: build-evidence.json, before-metadata.json (hash metadata only), server-installed.json, startup-verification.json, personal-installed.json, completion.json. Source/build/test logs: C:/Chacademi/staging/status-hud-20261019. Launcher: C:/Chacademi/staging/temp-launcher-status-hud-20261019 (candidate.json, preparation.json, release-candidate-hashes.json, installation/build/lint/test/ASAR/updater evidence).

Manual test instructions: [CODEX_STATUS_HUD_TESTS_20261019.md](CODEX_STATUS_HUD_TESTS_20261019.md). Public verification evidence: C:/Chacademi/staging/temp-launcher-status-hud-20261019/public-verification.json.

Read-only Korean status diagnostics through the existing supervisor console arrived as question marks and returned Unknown command; no status-command runtime verification is claimed. Plugin activation, startup logs, protocol response and unit tests are the completed checks. No console encoding/service-script change was made.
