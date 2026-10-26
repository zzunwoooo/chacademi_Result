# Journal, shared notices, mouse keys and target/wildlife release - 2026-10-12

Status: deployed together to school/wild and personal UI/Story on 2026-10-12. Source release938256b57cd542890009336fa11c54d6e83b8c6f. Built on c3736109c8d4456e8562e83d470401df571d1e69 without discarding its working changes. This supersedes CODEX_UI_PREPARATION_20261012.md and its missing-asset blocker.

## Behavior

Completed and active quest tabs use the same main-quest / journal accordion and selectable rows with title, actual description, status and detail. The available board retains its existing cards. Six rows per category/page, empty state and long-description scrolling are bounded. /메인퀘스트 opens the unified active journal on updated clients; old clients retain the legacy reader.

QuestStore, DialogueStore and the current Story local ledger remain their respective owners. No new operational DB table, schema migration, imported copy of progress or reward system was added. Quest history uses saved accepted definitions, collapses cycles by ID and preserves reward completions. Legacy dialogue stages are not guessed to mean completed. Story reads current dialogue.yml title and optional top-level description, retaining recorded metadata if content disappears. The six personal ch1-1..ch1-6 files now contain descriptions grounded in their actual dialogue. See [description list and Claude editor contract](STORY_DESCRIPTION_CONTRACT_20261012.md); the earlier missing-description state is superseded.

Admin commands (online target; Korean nickname or account; source:id or unambiguous title):

```
/유저관리 퀘스트기록편집 <target> 조회
/유저관리 퀘스트기록편집 <target> 부여 story:ch1-1
/유저관리 퀘스트기록편집 <target> 진행 story:ch1-1
/유저관리 퀘스트기록편집 <target> 완료 story:ch1-1
/유저관리 퀘스트기록편집 <target> 초기화 story:ch1-1
```

The older target-first form also works. Permission: magiccodex.quests.admin. Prefix, operation, Korean name, ID and title completions are connected. Story IDs appear after the target's updated Story/UI publish their metadata (join/15-second refresh or opening journal). Story edit tickets bind UUID, ID, operation, random token, timeout and single consumption. Responses explicitly identify a client acknowledgement, not server-authoritative completion or proof of reward. Missing Story provider reports ERROR.

Grant/progress mark active; complete marks display completion without issuing rewards; reset hides/resets display progress while preserving lifetime receipts/completion counters. Quest paying records refuse edits and require existing recovery workflow. Local Story done/event/gain/affinity values and legacy dialogue stages/effect receipts are never reset by this command. Ledger writers refresh before mutation so an already-running dialogue does not overwrite a later admin edit. Natural Story completion can mark its own current record completed again. Admin completion of a QuestStore record consumes its completion allowance without paying it; reset does not reopen that payout.

SharedNotifications.offer(id, kind, title, body) is reusable for quest/friend/system. Quest acceptance, dialogue quest grant, admin grant and first Story start use it. Queue bounded at16, at most3 visible, newest enters lower right, older cards move up smoothly, hidden-screen time pauses lifetime, no focus/input capture. Existing interactive friend request controls and discovery ACK remain separate and unchanged. Other systems can use the same public API for informational notices.

Key settings keep existing keyboard/slot geometry and transactional save/discard. The approved mouse sits at145,562 with190x285 full-image coordinates. Thumb polygons use the same transform for hover, outline and click; physical mouse3/4 open the picker. Right-click a thumb clears it. Left Shift/Ctrl are assignable; both Alt and left/right/middle mouse buttons remain unavailable. Existing movement/equipment reservations, gameplay permission/mode/GUI/repeat gates and max6 bindings remain. Hover a picker item for its short actual description. Version1 integer storage remains compatible (thumb codes1003/1004).

Attack targets / grounded wildlife are the already committed CODEX_TARGETS_WILDLIFE_20261012.md changes, released together. WorldGuard and invincibility still apply. Pet Invincible:true remains; accepting a pet as a target does not force damage. Riding pets retain flight. Existing wild entities need natural replacement before their new base type applies; no bulk kill.

## Assets and preservation

The user-downloaded approved ZIP supplied the three PNGs; bytes were installed unchanged. Meaningful-alpha bounds crop only at rendering. Existing UI assets, Portrait Sunburst/Flare/Luna/transparency/UI size, taming, pets/shiny, party HUD, discovery, gimmicks and block/AI recovery remain. Private Story release preserves all8 assets from the prior personal JAR; public Story has no TTF. Artifacts and hashes: CODEX_JOURNAL_ARTIFACTS_20261012.json. Host release: C:/Chacademi/staging/magic-protection-20261011-task5/journal-integrated-release-20261012.

## Verification

Hosting Java21.0.10 only; offline Gradle. Bridge306 + UI254 + Story mod50 + MagicMythic20 =630 tests, zero failures/errors/skips. Relevant cases exercise actual journal SQL stores, completed/paying reward boundaries, legacy receipts, separate live ledger instances, actual JournalBridge permission/player/operation/token/replay checks, protocol bounds including long Korean payloads, row categories/pages, notification overflow/expiry/hidden pause, thumb PNG/hit geometry, persistence and gameplay input gates.

Commands: root gradlew.bat :magic-codex-paper:test :magic-codex-paper:build :magic-mythic-paper:test :magic-mythic-paper:build --offline --no-daemon; root wrapper -p magic-codex-fabric test remapJar; root wrapper -p chacademy-story/mod test remapJar. JAVA_HOME=C:/Program Files/Java/jdk-21.0.10; GRADLE_USER_HOME=C:/Chacademi/tools/gradle-cache. Logs: journal-20261012/server-release.log, ui-release.log, story-final2.log.

IMPORTANT visual limitation: the isolated hosting Minecraft run compiled, but exited during LWJGL graphics initialization with Windows exit -532265403, before a frame. No game screenshots or actual UI clicks were obtained. The nine-case JournalVisualCheck fixture is available through -PvisualCheck -PjournalCheck runVisualTest for a graphics-capable hosting session. Independent Java2D static previews using real PNGs/fonts/coordinates were inspected for geometry; they are not game-render evidence. Do not claim live visual acceptance from those previews. No local laptop build or game launch was used.

## Live acceptance checklist (not yet performed by a player)

- /메인퀘스트 and quest menu: active/completed both show main/journal accordion, current ch1 titles, empty and paged categories; long description wheel; available cards and claim/abandon still function.
- Each admin command above: correct target and state; no reward/affinity/event replay after reset; paying QuestStore refusal. Try a non-admin and verify refusal. Check multiword Korean nickname and source:id autocomplete.
- Grant several fresh records: lower-right cards slide in and push upward; menu/cutscene pauses them; no overflow at GUI2/3/4 and resized window; existing friend acceptance and discovery still work.
- Key settings: click both pictured thumbs and physical buttons; hover spell description; assign left Shift/Ctrl; reject Alt and primary mouse bindings; save, reopen and verify. Cancel an unsaved edit. Verify magic mode off, chat/menu open, unknown spell and held-repeat gates.
- Use existing permitted combat spells against animal/custom mob/survival player, and NPC/protected-region refusal. Existing pet damage protection is still expected. Compare newly spawned ground wildlife and rare stats; riding pets still fly. Do not bulk-kill existing creatures to validate.

No launcher application/feed/release, inventory UI, broom code, dungeon models, paid API calls or key changes are included. Server and personal mod replacements require fresh process checks and normal targeted maintenance; no forced stop. Runtime progress and all existing config/DB files must be preserved during rollback.

## Deployment verification

User-authorized combined maintenance completed on2026-10-12. Fresh list results at host21:53:43 showed0 players on both backends. Exact Stop-Network.ps1 -Server school/wild completed normal shutdown; no forced kill, all-network Stop or Velocity restart. Verified backups: C:/Chacademi/backups/journal-input-targets-20261012-task5 (old Bridge/MagicMythic JARs and wild YAML for each backend; backup-manifest.json). Replaced only those two JARs and mobs/chacademia_custom_wild.yml per backend. skills, pets, player/progress data and other runtime settings were not replaced. Pet YAML SHA256 before/after is identical: fe2c89ad2f0074baf6ab0e124761941f74d7acd8403611c2a884351f54a7dc22.

Both backends logged new Bridge runtime.1-journal.1 and MagicMythic targets.1 enable, plus Spell gameplay runtime ready:89 routes; catalog=true at21:59:50. Story server0.1.3 remained unchanged and enabled at21:59:52. Done: school21:59:56, wild21:59:54. SchoolPID10504/listening127.0.0.1:25566; wildPID12740/listening127.0.0.1:25567. VelocityPID7552/listening25565 unchanged. No new related plugin-enable error observed. Startup is not full player gameplay validation.

Personal Minecraft/Java and launcher window/process checks were empty immediately before replacement. Installed exactly the UI input.1-journal.1 and personal Story0.1.3-journal.1 JARs listed in the artifact manifest; installed hashes match. Old two JARs preserved at task-5/journal-integrated-personal-backup-20261012 with manifest.json. Client game and launcher were not started or closed. Existing private Story source hash256b703b3ec5293f701c901ab9ebde8e6eb1970dfb3fffce60c12617fbb4b6e8 matched the prior host private artifact; all8 assets were preserved byte-for-byte. No launcher/feed change: future launcher FullRepair may restore its older distributed mods.

Rollback: after fresh process/player checks and authorized normal maintenance, restore the two backed-up backend JARs and exact wild YAML, remove only this release's new JAR filenames, and restore the two personal JARs while the game is closed. Do not restore old player/DB/progress files or erase new journal values. Keep runtime-generated progress when reverting binaries. The new local journal.* metadata requires a journal-aware Story build: reverting to the pre-journal mod blindly can reject the extended progress file; export/preserve current records and prepare compatible code before such a downgrade. No automatic rollback of user records is authorized.
