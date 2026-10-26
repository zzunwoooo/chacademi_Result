# Inventory, broom equipment and journal regression release

## Scope and provenance

Integration continues `d2885eeb04ff830246a77c017877491196551188`, retaining the journal, Story descriptions, shared notices, mouse keys, attack-target and grounded-wildlife release. Inventory source: `868cc12bc7088b526fcbd37398ca00a7198f140e`; Motion/equipment source: `e0d542651b9aa25c8f4561e38ef3fdc2be084156`. Public remote is named `public` in this hosting checkout; `origin` is another hosting checkout. No reset was used. Existing staged, unstaged and untracked changes were copied to hosting `C:/Chacademi/staging/survival-inventory-20261012-task5/preserved-20261013-resume` before follow-up edits.

The reusable feature is **SurvivalInventoryUI inside magic-codex-fabric**, not another inventory mod. Existing floating four crafting orbs, crystal result, 27 inventory slots, nine hotbar slots and recipe book are retained. The real offhand slot is visible and F still swaps it. Armor quick-move that would auto-equip an empty hidden armor slot is rejected; normal quick-move and crafting result collection remain vanilla server-validated actions. Effects show icon/name/level/duration, bounded scrolling and truncated names with a full tooltip; an empty effect list draws nothing.

Broom equipment uses Motion 0.3.0's server-owned snapshot and Object-parameter render API. Five vertical cards per page, server-confirmed equipped badge, selection/equip/back controls, mastery and comparison gauge tooltips use the existing font/cache. The equipment panel retains all four armor plus four accessory slots and five stats. Model scissor and matrix/lighting cleanup are explicit; model z+200 and tooltip z+600 are separate. Compilation/remapping is not proof of actual GPU appearance.

`gale_dash` and `light_leap` are rejected before mana consumption or cooldown changes while riding. Recasting flight while riding is also rejected: actual rebound sneak key (default Shift), once, owns free dismount. No new shortcut is imposed. Held mount-key state must first release before becoming a dismount edge; the dismount press/repeat/release is reserved from magic casting. Shift descent/double-press escape is removed. Flight still costs 80 mana with a base 30-second cooldown (existing haste applies); dismount does not reset that cooldown. Owned broom is unlimited, unowned practice broom is 200 ticks. Boost has no new mana charge.

Ownership is `chacademy.broom.own.<ID>` or wildcard; OP owns all. Invalid equipment requests do not grant ownership. Revoked selection falls back to another owned broom or practice; revocation during unlimited flight ends that ride. Recatch checks current permissions/world/player state. Normal dismount and plugin shutdown restore captured allowFlight; fall damage is suppressed only while riding, with existing slow falling on practice timeout. Other plugins changing flight permission during the same ride remain an integration QA case.

## Approved assets and source preservation

Downloaded `Chacademi_inventory_alert_UI.zip`: 1,385,692 bytes. Extracted actual pixels were viewed successfully without Library helper or file_id lookup. All five approved PNGs are included unchanged:

| PNG | SHA256 |
| --- | --- |
| status_effect_row.png | 323686a102fc66564c3c3d99c2c22b1b35572861f5c71f7619107b64cb7abf7b |
| spell_unavailable.png | ca7e82606c43a83411dfb038df394e65e22b27683dd473f398252ae3a5cd8eb0 |
| broom_equip_slot.png | 52b344008bb2e56154f25769cfd6438f25c5ff06acc8273ab1c88af81750c65e |
| broom_selection_card.png | a09427b49e16bcefef5ef62e61fceb43f558aaa243c3c677c2a83eaf8e775ca7 |
| broom_selection_panel.png | bc35b5721e78fe75e8b4d033c7fad79f2d4a3b119ec025f7a625e5af36787dc2 |

Desktop original `차카데미model_claude/setting/chacademy-motion` reports 0.3.0 and playerAnimator 2.0.5+1.21.4. The integrated copy is 0.3.0-codex.1 with approved dismount/safety changes. Never replace it with the older 0.2.0 checklist artifacts. Personal playerAnimator is 2.0.5; Fabric API 0.119.4+1.21.4 resides in the launcher's common/generated fabric-mod directory. No duplicate API installation is needed.

No Story private content/description, other personal mods, keys, paid APIs, DB schema, dungeon models, public launcher feed or Velocity binary is changed.

## Reproducible hosting verification

Only hosting Java 21.0.10 builds were used. Working directory: `C:/Chacademi/staging/survival-inventory-20261012-task5/source`.

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21.0.10'
$env:GRADLE_USER_HOME='C:/Chacademi/tools/gradle-cache'
.\gradlew.bat :magic-codex-paper:test :magic-codex-paper:build :magic-mythic-paper:test :magic-mythic-paper:build --offline --no-daemon
.\gradlew.bat -p magic-codex-fabric test remapJar --offline --no-daemon
.\gradlew.bat -p chacademy-motion/chacademy-motion-plugin test build --no-daemon
.\gradlew.bat -p chacademy-motion/chacademy-motion-mod test remapJar -PplayerAnimatorJar=C:/Chacademi/staging/survival-inventory-20261012-task5/dependencies/player-animation-lib-fabric-2.0.5+1.21.4.jar --offline --no-daemon
```

Expected: exit 0, BUILD SUCCESSFUL, zero JUnit failures/errors/skips. Final measured test counts, binary hashes and deployment receipts are recorded in the companion release evidence JSON. Build-attempt issues were a Windows PowerShell stderr-warning wrapper, missing offline Mockito cache, and an incorrect shadowJar task name; these are not passed tests. Mockito was downloaded on hosting; corrected commands use the actual build task. Protocol is a shared source set, not a standalone Gradle project.

## Single combined manual acceptance list

These checks are **pending in-game QA**, not claimed as automated/live passes. Use a test account and preserve existing progress/reward receipts. Commands below run in game unless explicitly PowerShell. Replace `<대상>` with the intended online test account.

| Check / actual command or input | Expected result |
| --- | --- |
| `/메인퀘스트` | Unified active/completed journal, six rows per category/page, title/description and bounded long-description scrolling; available-board cards still work. |
| `/유저관리 <대상> 퀘스트기록편집 조회` | Correct target's records; Story metadata appears after client sync; source:id completion works. |
| `/유저관리 <대상> 퀘스트기록편집 부여 story:ch1-1` | Active record and one shared notice. |
| `/유저관리 <대상> 퀘스트기록편집 진행 story:ch1-1` | Active progress display; no reward replay. |
| `/유저관리 <대상> 퀘스트기록편집 완료 story:ch1-1` | Completed display without rewards or affinity grants. |
| `/유저관리 <대상> 퀘스트기록편집 초기화 story:ch1-1` | Progress display resets while lifetime reward/affinity/event receipts remain; paying records refuse unsafe edits. |
| Repeat admin commands as non-admin | Permission refusal; no mutation. Alternate operation-first syntax remains supported. |
| Trigger quest/friend/system notices; open/close menus | Lower-right shared bounded queue, paused lifetime while hidden, no duplicate chat or input capture; existing interactive friend controls retained. |
| Key settings: both mouse thumbs, LShift/LCtrl, save/cancel | Existing geometry and spell tooltip; persistence and cancellation work; Alt/main mouse buttons remain disallowed. No casting in chat/inventory or on held repeats. |
| E inventory; left/right click, Shift, drag, double-click, 1–9, Q/Ctrl+Q | Server-authoritative counts, no lost/duplicated items; four crafting inputs, crystal result, 27+9 layout unchanged. |
| Shift an armor item with empty armor destination | No invisible auto-equipping; normal non-armor Shift transfers and Shift-crafted output still work. |
| Put an item in visible offhand; press F | Exactly the real offhand slot swaps; reconnect preserves server state. |
| Craft and Shift-craft; close/reopen recipe book holding an item; close with ingredients | Correct consumption/output/return, no unintended drop, ghost recipes and narrow-book layout work. |
| `/effect give @s minecraft:speed 120 1`; add resistance/regeneration; clear effects | Icon, localized name, level and countdown; scrolling for multiple effects; no effect panel after clear. |
| GUI scale 2/3/4/Auto; 1280x720/1920x1080/2560x1440 and small window | No clipped effect names/tooltip overflow or blocked inventory slots; existing Korean font, high-res icons and enchanted glint remain. |
| Equipment screen, click long broom slot | Existing 4+4 slots/stats remain; five vertical owned cards per page, model clipping and popup layering correct. |
| Select card, equip, cancel/back; change ownership before confirming | Equipped badge updates only from server snapshot; unauthorized equipment refused; no optimistic grant. |
| LuckPerms `/lp user <대상> permission set chacademy.broom.own.broom_7 true` then refresh | Owned card appears; equip and flight use it. Use matching `unset` to remove only the test grant. |
| Wildcard grant or OP test account | All ten owned, two pages; non-owner sees empty/practice explanation. |
| Cast flight through assigned magic key | 80 mana, base 30-second CD; owned unlimited, unowned practice ten seconds. |
| While riding cast gale_dash/light_leap repeatedly | Zero mana/CD change, no dispatch; one deduplicated shared reason notice. |
| Press rebound sneak key once (default Shift), including a magic LShift assignment | Free dismount without spell co-cast; original flight cooldown remains; no Shift descent or double-press requirement. |
| Dismount/fall/practice timeout; revoke owned permission mid-flight | Original allowFlight restored, normal fall damage after exit; timeout slow falling retained; revoked unlimited ride ends. |
| Two clients, world/region changes, reconnect | Other rider model/pose visible; protection, lifecycle and model state remain consistent. |
| Permitted spells against animal/custom mob/survival player; NPC/protected region | Prior targeting expansion preserved; WorldGuard/NPC/invincibility refusals retained. Pet Invincible remains effective. |
| Observe newly naturally spawned wild creatures and rare variants | Grounded base types and rare stats retained; riding pets still fly. Do not bulk-kill existing mobs. |
| Story ch1-1 through ch1-6 descriptions, portraits, pet/shiny UI, chat | Existing private metadata, fonts, assets and settings remain unchanged. |

## Targeted deployment and rollback

Before replacement record `Control.ps1 -Action Status`, both backend `list` responses, old/new binary SHA256 and backups. Stop only via:

```powershell
& 'C:/Program Files/PowerShell/7/pwsh.exe' -NoProfile -File C:/Chacademi/network/scripts/Stop-Network.ps1 -Server school
& 'C:/Program Files/PowerShell/7/pwsh.exe' -NoProfile -File C:/Chacademi/network/scripts/Stop-Network.ps1 -Server wild
```

Never use `Control.ps1 -Action Stop -Server` (it can stop the whole network). Replace only Bridge, MagicMythic and Motion plugin JARs; start each with `Control.ps1 -Action Start -Server school|wild`. Verify enable versions, Done/listening and unchanged Velocity PID. Personal replacement touches only MagicCodex UI and Motion JARs after no-game/lock checks. Back up old exact binaries and compare all unrelated mod/config hashes. No forced process termination.

Rollback binaries only after normal targeted maintenance; restore exact backed-up JARs, retain new gameplay/player progress and Motion mastery files. Never restore old DB/progress snapshots as part of binary rollback.

## Completed deployment evidence

Source code commit: `0a3b188c419c7ed1e17bd308453231f464ad2056`, pushed and confirmed on the public integration branch. All binaries are from the hosting source checkout; see [full paths/bytes/SHA256/test counts](CODEX_INVENTORY_BROOM_EVIDENCE_20261013.json). Final tests: Bridge 309, MagicMythic 22, UI 278, Motion plugin 21 (including eight service safety cases), Motion client 3; total 633, zero failures/errors/skips. Unchanged tasks used Gradle up-to-date results; UI and added Motion service tests executed during this continuation.

Normal targeted maintenance completed. New Bridge broom.1, MagicMythic broom.2 and Motion 0.3.0-codex.1 enabled on both servers. School Done 2026-10-13 00:32:08 KST, PID3688; wild Done 00:32:05 KST, PID5724. Three protocol769 status responses passed on ports25565/25566/25567. Velocity PID7552 remained unchanged. All six installed server JAR hashes match release; 29 other school and 28 other wild plugin binaries were preserved. Backups/receipt: `C:/Chacademi/backups/inventory-broom-20261013-resume`. No runtime configuration, player record or DB schema was edited.

Personal MagicCodex UI and Motion JARs were backed up and replaced; both installed hashes match. Other14 mod files and1807 config files are byte-identical, including all six ch1-1..ch1-6 dialogue.yml files. Backup/receipt: `C:/Users/matil/Documents/Codex/2026-10-13/task/personal-backup-20261013`. Initial broad Java-process guard paused safely; subsequent classification found only two existing Gradle processes, no Minecraft. Neither process was stopped, no local build/game launch occurred, and both target JARs passed exclusive-lock checks. Existing370 UI PNG/font/JSON entries were identical to the prior release.

Operational caveats: SkeletalKnight and MariaDB duplicate-key warnings also appear in older console history. Wild ItemsAdder reported missing `output/generated.zip` during startup and that file is still absent; pack generation/configuration was outside this patch and was not changed. New plugin enables and server status responses succeeded. Actual player/GPU/gameplay acceptance remains pending using the combined list above.
