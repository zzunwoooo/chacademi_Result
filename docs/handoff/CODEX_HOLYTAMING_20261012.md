# HolyTaming and recovery integration - 2026-10-12

Current branch: codex/chat-discovery-magic-protection-20261011, based on e11af867c88a86e82b7cad7e6b2216ef0f826d44.

## Scope and provenance

Selectively incorporates TempBlocks journal recovery and Marks original-AI recovery from Claude gimmick rebase 5a8a8d4ca6ca87c17c8975c07c8e3992910ae69b, with write-before-place, failed-write refusal, unloaded-world retry and regression tests. The gimmick module, event callbacks and dungeon 63-model package are excluded by request.

HolyTaming preserved package SHA256: 1b0c01324f67ab8daf83ed9fca6a277dcd1c953365a0e2defbc48d04b70a5ba3, originally based on aa4f63b68e96fb092d4621a6b407062006d7229e. It is merged with current admission limits, owner-scoped sessions, diagnostics and protection checks. Internal ID rainbow_collar; mana 150; cooldown 60 seconds. Acquisition uses the existing administrator manual-condition command, not quest 2-2. Existing target registration, chance calculation, boss behavior and pet persistence remain authoritative. No new boss capture feature is added. VFX admission failure refunds; failures after VFX starts consume the transaction once.

Existing portrait Sunburst/Flare, Luna analysis/transparency, personal UI sizing, pets/shiny and Story features remain preserved. Reviewed discovery mapping is now 98 entries: 96 connected (including HolyTaming) and two pending (crimson_heart, bouncing_droplet). Generated CHA routes remain 88; these counts describe different catalogs.

## Verification

Hosting Java 21.0.10 only. 823 tests: 820 passed, 3 skipped, zero failures/errors. Module counts: mythic 15, bridge 289, discovery 34, Velocity party 30, Fabric UI 230, VFX Paper 128, VFX client 97 (3 skipped). HolyTaming fixture validates 8 systems, 146 layers and 14 textures. Nine missing pre-existing public sample textures were restored to enable the VFX client suite.

School and wild normal restart completed with no players; Velocity restarted normally. New bridge, discovery, mythic addon and VFX Paper plugins enabled; 96 VFX bindings, zero skipped; Mythic reload reports 375 skills. Existing SkeletalKnight warning remains; wild ItemsAdder output/generated.zip is missing. These are recorded separately from integration tests. Live player taming, WorldGuard boundary/OP/projectile scenarios and crash recovery remain acceptance tests to perform.

## Operations and limits

Deployment JAR hashes are in CODEX_HOLYTAMING_ARTIFACTS_20261012.json. Hosting backups are retained outside this repository. Config changes merge only relevant IDs and retain unrelated settings. Both discovery donation stores remain existing SQLite databases: no shared MariaDB migration or credential/grant changes. Cross-server donation consistency is not established by this deployment. No real paid API calls were made.

Public launcher v0.1.3 is published and its main distribution feed is updated. Launcher commit: 03480ef13153e01a015016075d4ef5301e1e7518; game build source: 4ad93132baebaf77441845449635b5dabb4b836f. All 11 GitHub asset digests match local files; public distribution/latest.yml/checksum downloads and main feed match. Release: https://github.com/zzunwoooo/chacademi-launcher/releases/tag/v0.1.3 . Only authorized client artifacts, public effects and Chat Layout corresponding GPL source belong in that release. Private fonts/images, server JARs, runtime databases and secrets are excluded. Personal client update completed after verifying no java/javaw process was running. Final UI and all 15 HolyTaming files match; the previous UI/catalog were backed up. Private Story font, ChatPlus and Chat Layout hashes remain unchanged.

## Manual acceptance

Administrator: /유저관리 <닉네임> 마법 조건지급 rainbow_collar. Verify one learned entry, mana 150, cooldown 60, successful pet persistence and failure behavior. Do not grant to a real account automatically.

Verify protected-region refusal before effects, target protection during channel, normal success/failure VFX, owner disconnect cleanup, and original mob awareness/block state recovery. No quest 2-2 grant should occur.

Client upgrade: only the exact previous bundled 149 default (normalized line endings) is backed up and migrated; customized catalog files remain untouched. Fresh installations still install the complete catalog. Two regression tests cover these paths.

Console status commands in Korean could not be verified through the current supervisor input encoding; startup logs and English Mythic reload are verified instead.

Release installer SHA256: 10b9a517dfee3293fd496127827a77761fcfe50fbe0c22a7b1ff25ab3d5d75fb. Final UI SHA256: b32f9d2d99091639adf1ff06a6b69b37dac64ced5af4ba8095aedd88db5035ee. Client assets SHA256: e3d68c65a58cebf8e967a38bd840a4e72173248e955b9acfdd96d2410b74c12e. Installed server JARs rechecked against deployment manifest; TCP 25565/25566/25567 respond.
