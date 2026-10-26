# Taming, single-file Story commands and gimmick integration - 2026-10-12

Status: deployed to school/wild and personal UI/Story on 2026-10-12. This supersedes the per-ending server_commands.yml design in a5bfabd and all older staging-only statuses. Runtime base before this operation: c4545ea23ef4b85f8f6192718e6c9aa19f6ce660.

## Story 0.1.2: one server file

Authoritative path on EACH backend: `plugins/ChacademyStory/dialogues/server_command.yml`.

```yaml
commands:
  ch1-3:
    - "컷신 재생 {player} test"
    - "say hello"
  ch1-1:
    - "say test"
```

The exact user-approved example is installed. The personal client has `ch1_ashen_night` and `magic_tutorial`, but no `test` cutscene. Thus the configured test cutscene cannot actually play until the content exists or the administrator changes that ID. Installation did not execute either say command or any cutscene.

Lists belong to dialogue IDs, not ending IDs and not per-dialogue files. Every normal server-authorized playback completion consumes its player-bound random session token before dispatching the whole registered list in order as console. Commands use the authenticated player for {player}/{uuid}; existing sanitized nickname substitution remains. Ready acknowledgement, one-hour expiry, player/dialogue/token matching and removal on cancel/disconnect/reload reject forged or duplicate completion. Client packets never supply console command text. As before, the server cannot prove the client read every dialogue line; no durable retry after a process crash is promised.

The lifetime client completion ledger gates affinity and first rewards only; ordinary server completion commands run on later authorized plays too. Existing done.ch1-3=true, reward and affinity data were not reset. Original client dialogue.yml and _progress files were not changed. Legacy client commands.s20/s21 now produce an explicit warning directing administrators to the server file. Local preview does not grant console authorization.

`/스토리 리로드 [player]` reloads server config and the requesting/explicit player's dialogue AND cutscene files, with separate server/client results and a bound response ticket. A console sender without a player target gets server results and a target-required notice; this never means all clients reloaded. Malformed command YAML retains the previous valid registry. `/cutscene reload` is the server-only English alias. Permission remains chacademy.story.admin; receiving players need no admin permission.

Live server-only reload succeeded on both backends (2 dialogue IDs). The management console corrupts Korean input before Bukkit, so the Korean no-target path and client ACK flow were not verified live; codec/session/reload ticket unit tests passed. Existing missing-server-dialogue and finish-if-missing-mod warnings refer to the legacy server graph path, not a requirement to upload client dialogue files for this new local-session route.

## Taming

Existing world chance label/channel panel reused, all 927 UI assets unchanged. ModelEngine 4.0.8 removes its hidden living base from clients; an entity-ID-only response could therefore never render the label. Optional magiccodex:taming_anchored supplies bounded server UUID/position/height with the state atomically. Matching tracked entities retain smooth positioning; absent bases use the anchor. Stale/reused/dead IDs are rejected. V1 protocol remains compatible. NPC exclusion strengthened. Mana150, cooldown60, probabilities, ownership, pets and existing terrain/range checks remain.

## Gimmick 0.1.1 and MagicMythic 0.1.2-recovery.1-gimmick.1

Selectively integrated claude/gimmick-plugin-20261012-rebase at 5a8a8d4ca6ca87c17c8975c07c8e3992910ae69b. Existing TempBlocks.java and Marks.java recovery/AI fixes were not replaced. Added protected MagicImpactEvent dispatch and element tags; deployed skill file differs from the prior runtime only by element parameters.

Built against actual Paper1.21.4, MythicMobs5.9.0, ModelEngine4.0.8, ItemsAdder4.0.9, WorldGuard7.0.13 and FAWE2.13.1-SNAPSHOT-1119. Checked actual API signatures. WorldGuard checks fail closed, cover caster/target, magic/build/interact and do not allow OP bypass; NPC/tamed/MCPets/players excluded from attachment. Rays stop at walls; portal endpoints are protected/passable. Barrier ownership is persisted before placement, unloaded recovery entries are retained, non-owned blocks preserved. Strict YAML and atomic writes protect malformed/current state. Puzzle effects wait for durable state and item collection failure does not consume the item. These are scoped unit/source safeguards, not a promise of transactional recovery across arbitrary process failures.

Both live backends: plugin enabled, ModelEngine selected, magic integration enabled, 0 placed gimmicks and 5 example puzzle definitions. No gimmicks/items were spawned and no world placement commands ran. The 63-model catalog is metadata only: no gm_*.bbmodel assets are installed. The deferred dungeon model pack remains excluded, so corresponding visuals require a separately approved content install. `/gimmick tool` gives the administrative BLAZE_ROD debug tool, not a new ItemsAdder reward item; not executed here. Admin permission: chacademia.gimmick.admin. Inspect with `/gimmick list`, `/gimmick puzzle`, `/gimmick models`.

## Evidence and boundaries

Hosting Java21 only. Tests: Bridge297, UI245, Story plugin59, Story mod43, MagicMythic15, Gimmick14 = 673, zero failures/errors. Private Story preserves all8 prior asset entries including3 fonts; public Story contains noTTF. Personal Chat Layout1.6.0, ChatPlus, portrait/pet/shiny files untouched. Runtime JAR SHA256 values: CODEX_INTEGRATED_ARTIFACTS_20261012.json.

Fresh list results showed zero players on both servers at15:25:29/30 host log time. Console stop saved all dimensions, then replacements and one normal restart each. Done at15:27:27 school and15:27:26 wild. PIDs school8380/wild10508; Velocity7552 unchanged. Gimmick read-only list and puzzle commands worked. Existing wild ItemsAdder generated.zip missing error and prior model warnings remain unrelated. No new integrated plugin enable errors observed.

Host backup: C:/Chacademi/backups/integrated-taming-story-gimmick-20261012-task5 (oldJARs, exact skill file, existence manifest). Final host artifacts: C:/Chacademi/staging/magic-protection-20261011-task5/integrated-release-20261012. Personal backup: task-5/integrated-personal-backup-20261012. Personal Minecraft was not running at replacement; existing server.jar processes were left alive. Rollback requires another fresh zero-player check and approved normal stop; restore backed-up three plugins/skill file, remove only this added gimmick JAR, and preserve any subsequent runtime progress rather than restoring old player records.

No launcher/feed/Release change, DB administration, key access, paid API invocation, player-data reset, user world placement or forced process kill. Installed zzunwoo0.0.4 still reads the old mchdistro/zzunwoo feed, so FullRepair may overwrite manually installed mods; launcher work remains excluded.

Remaining live acceptance: actual hidden-model/vanilla taming label and NPC/pet refusal; non-OP player completes twice and sees two ordinary command dispatches with one first reward/affinity; combined client reload ACK and warning display; gimmick model visuals after approved assets. Do not describe these as tested in a running game.
