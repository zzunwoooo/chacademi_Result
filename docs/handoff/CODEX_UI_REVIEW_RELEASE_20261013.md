# 2026-10-13 UI review, camera and broom integration

## Evidence and scope

Continues integration 59f10f8cf428d879670561f65588b4b153fa330e without reset. All source edits, Java 21 builds and tests ran on hosting. Camera source: claude/aim-camera-20261013 at 659b70b7ccb276c265076d2cecb54dc9d0f49f4b; its handoff was reviewed before selective integration. Existing PlayerHudClient initialization lambda and public APIs remain compatible. No server aiming protocol was added: current look packet precedes the existing cast request; server validation remains authoritative.

The user downloaded Chacademi_UI_20261013_inputs.zip (14,050,026 bytes; SHA256 497a4828bf115c49eef86857008a9688fb141f93fb4a8be440534682f960ed25). Local and hosting hashes matched; actual pixels were viewed. Only the three approved production atlases are tracked. Private feedback screenshots/reference inputs are excluded. No Library helper patch, signed-URL workaround or paid API was used.

## Requirement status

| Area | Implemented and build-tested | Remaining live QA |
| --- | --- | --- |
| Journal | Geometry arrow, dot/title, optional chapter hierarchy, independent folds, scroll, no source/ID footer; old files remain compatible | Many chapters, Korean long names, smallest GUI scale |
| Admin | `/유저관리 <대상> 퀘스트 <메인/일일> <조회/부여/진행/완료/초기화> [제목/ID]`; title-first deduplicated/state-filtered completion, ambiguous-name rejection, Korean result | Online Korean/English targets and asynchronous first autocomplete |
| Notice | Shorter lifetime, exact duplicate suppression, click dismissal, one sound, 90% scale, higher HUD placement, dialogue overlay render | Dialogue click consumption and HUD overlap |
| Inventory | Smaller upper-center composition, lowered book button, aligned 9-column hotbar, separate offhand, centered book, real/ghost result hides crystal, effects shifted | Server slot sync, recipe book, all resolutions and glint |
| Key editor | Physical keyboard no longer opens picker; rendered key click does. Left Alt red/reserved; smaller keyboard, right-side mouse and corrected thumb physical mapping | Rebound keys and physical mouse device |
| Equipment | Full-size avatar preserved, actual lower extension, broom between avatar/name, 4+4 slots/stats, bright front model | GL/scissor/z-order across shaders/GPU |
| Broom selector | Five vertical atlas cards, pages/wheel, hover/selected/equipped states, server-confirmed badge, four grades, 1–10 integer comparison gauges | Permissions/revocation and live server response |
| Flight | Common arc HUD, third-person above head, first-person fixed; pitch control, stronger stat differences, braking, six rebound WASD recatch steps, low recatch balance | Latency, balance/physics feel, head projection, fall safety |
| Monster | Combined name/health/chance HUD, gold/purple frame; authoritative health and tame eligibility, UUID-safe name hiding | ModelEngine independent name-tag entities and occlusion |
| Camera | Built-in shoulder view, Z cursor, Alt camera, 42 spell previews/left confirm/right cancel, hint near crosshair, broom cursor exclusion | Full in-game camera/packet/protection validation |
| Season | Current implementation uses real calendar months rather than game ticks | **Not user's final intent. Next patch must replace with real 72-hour seasons; epoch still undecided.** |

Season follow-up: configurable epoch, spring → summer → autumn → winter every 72 real hours, independent of game time. The user explicitly deferred this correction to the next patch. Do not mark monthly calendar behavior as completion of that request.

Default iron/snow golem pet IDs in school/wild are missing from MCPets. They are deliberately not redirected to unrelated custom golems or registered automatically; the action says `교화가 불가능한 대상입니다` without charging a failed taming cast.

## Automated verification

710 tests, zero failures/errors/skips (Bridge 314, Mythic 22, UI 294, Motion server 21, Motion client 9, Story 50). An intermediate run found an effect-panel overlap, an obsolete mouse-layout assertion and an obsolete English ACK assertion; all were corrected and affected modules rerun. Permission, ACK target/operation/single-use checks remain intact. Evidence contains exact final JAR/PNG hashes.

Run on hosting in the source checkout with Java 21.0.10 and GRADLE_USER_HOME=C:/Chacademi/tools/gradle-cache:

```powershell
./gradlew.bat :magic-codex-paper:test :magic-codex-paper:build :magic-mythic-paper:test :magic-mythic-paper:build --offline --no-daemon
./gradlew.bat -p magic-codex-fabric test remapJar --offline --no-daemon
./gradlew.bat -p chacademy-motion/chacademy-motion-plugin test build --offline --no-daemon
./gradlew.bat -p chacademy-motion/chacademy-motion-mod test remapJar -PplayerAnimatorJar=C:/Chacademi/staging/survival-inventory-20261012-task5/dependencies/player-animation-lib-fabric-2.0.5+1.21.4.jar --offline --no-daemon
./gradlew.bat -p chacademy-story/mod test remapJar --offline --no-daemon
```

Motion is 0.3.0-codex.2, never downgraded to 0.2.0. playerAnimator 2.0.5 and Fabric API 0.119.4+1.21.4 are retained. Build/remap/test success does not establish actual Minecraft rendering or multiplayer gameplay.

## Combined manual acceptance checklist (not executed)

Use a test account; do not claim reward or gameplay outcomes from unit tests alone.

| Command/action | Expected result |
| --- | --- |
| `/메인퀘스트` | Existing main journal opens with title/description and optional chapter folds |
| `/유저관리 <대상> 퀘스트 메인 조회` and `일일 조회` | Correct target/category/state, Korean results, no unintended mutation |
| `/유저관리 <대상> 퀘스트 메인 부여 <고유제목>` | Only eligible quest granted; ambiguous title rejected |
| `/유저관리 <대상> 퀘스트 메인 진행 ch1-1` | Matching quest moves to active; no reward |
| `/유저관리 <대상> 퀘스트 메인 완료 ch1-1` | Active quest completes; no automatic reward or affinity |
| `/유저관리 <대상> 퀘스트 메인 초기화 ch1-1` | Progress resets; reward/affinity receipt history survives |
| Legacy `/유저관리 <대상> 퀘스트기록편집 조회`, `부여 story:ch1-1`, `진행 story:ch1-1`, `완료 story:ch1-1`, `초기화 story:ch1-1` | Backward compatibility with the same ownership/security rules |
| Inventory E, Shift transfers/crafting, F swap, recipe book open/reopen, `/effect give <대상> minecraft:speed 120 1` and multiple effects | 27+9 slots/real offhand, no armor Shift auto-equip, authoritative item counts, bounded effect rows/no empty panel |
| Hold/repeat unavailable spell on broom, click notice in gameplay/dialogue | One non-flickering notice, no duplicate chat/mana/CD consumption; only notice consumes click |
| Key editor: physical keys, rendered key click, Left Alt and both thumb buttons | Physical keys do not open editor; correct visible key mapping and reserved Alt |
| Broom mount, rebound sneak once, remount, gale_dash/light_leap while mounted | 80 mana/30-second mount CD, free dismount without CD reset, movement spells blocked pre-cost |
| Own/unowned/permission-revoked broom, equip then reconnect | Owned unlimited, practice 10 seconds, server-authorized equipped badge; revocation handled |
| Broom dive/brake/recatch six displayed movement keys, then fall/disconnect | Stats affect flight, recatch starts low balance; original allowFlight and fall protections retained |
| `/조준`, `/조준 커서 켜기`, `/조준 스마트 끄기`, Z, Alt; 1st/front/rear perspectives | Rear shoulder only, near-crosshair hint, left confirm/right cancel, no HUD click cast |
| Target eligible/ineligible monster and shiny; iron/snow golem | Correct health/chance and purple shiny frame; ineligible message without unrelated pet registration |
| Wild transfer, target pets/players/NPCs, grounded wild mobs | Previous targeting exclusions and surface placement unchanged; no unintended attack/taming |
| Story ch1-1…ch1-6, portraits, chat, party HUD, mouse, pet/shiny, quest receipt replay | Existing features and private description-only edits preserved |

## Public temporary launcher v0.1.5

[Download/release](https://github.com/zzunwoooo/chacademi-launcher/releases/tag/v0.1.5) is published and latest. Launcher source/main: `dbb59e721d40ba1bc31fd586c6031886bde9df0e`; game binary source: `bd6d6e001c38512569c11fb4db07d069bff110f3`. All 10 uploaded asset digests matched local files; four release metadata downloads matched bytes/hashes; public main/distribution.json matched after Git newline normalization. [Full publication evidence](UI_REVIEW_LAUNCHER_PUBLICATION_20261013.json). Launcher tests: 16 named scenarios (7 assets, 5 pets, 4 camera migration), settings/offline seed scripts, lint, updater and ASAR checks all passed. Existing locally installed SSR is identified by Fabric ID, hash-backed-up to camera-mod-backups/*.disabled, then removed from mods; all configs and other mod IDs remain. Feed-managed stale jars are excluded by the regenerated fabric.addMods list. Actual installed-app self-update/login/gameplay QA is not performed. Final packaging used hosting Node22/Electron39 with normal compression.

## Verified deployment

School PID 12044 and wild PID 11764 reached `Done`; installed plugin hashes matched. Velocity PID 7552 was preserved. Hosting backups: `C:/Chacademi/backups/ui-review-20261013`. Personal UI/Motion/Story matched the same build hashes; SSR was backed up and removed. All 12 other mod files and 1,807 configuration files retained identical hashes, including private Story descriptions. No Minecraft process was forcibly stopped. Existing SkeletalKnight/DB initialization warnings and wild ItemsAdder generated.zip absence remain. See [deployment evidence](UI_REVIEW_DEPLOYMENT_20261013.json) and [26 atlas source rectangles](UI_REVIEW_ATLAS_SLICES_20261013.json). Actual game QA remains unperformed.

## Deployment record

Build artifacts: hosting C:/Chacademi/staging/survival-inventory-20261012-task5/release-ui-review-20261013. Deployment and public launcher publication are tracked separately after hash-verified backups. Shoulder Surfing is removed from the personal client and public feed because the approved camera is inside Magic Codex. Do not touch private Story content/options, other personal mods or Velocity. Stop each backend only through Stop-Network.ps1 -Server school|wild.

Prior release's exact regression commands and safety evidence are retained below as historical context. Its statement that the public launcher is out of scope applied to that prior release; the user has now explicitly authorized temporary launcher update after final verification.

---

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
