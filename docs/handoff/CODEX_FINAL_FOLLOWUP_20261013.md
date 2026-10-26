# 2026-10-13 final follow-up: integrated verification

Continues `6a3c64853bc71385492a2802f2846ff7d0605bc4` on `codex/final-followup-20261013`. The original notification checkout and its uncommitted changes remain preserved. No reset or overwrite of another worktree was used. All source edits, compilation and tests run on Windows hosting with Java 21.0.10. This release does not update the public/temporary launcher feed.

## Implemented follow-up

| ID | Result in source | Verification boundary |
|---|---|---|
| A1–A3 | Bold notification faces; one master-volume-respecting sound on first visible frame; one render owner per frame; hidden-time pause, actual elapsed visible duration, deduplication and fixed stack positions; resource/disconnect cleanup | Queue/presentation tests. Audible output, dialogue and resource-reload flicker still require live game QA. |
| B1–B6 | Approved slot artwork without full-screen veil; smaller four orbs/result/book; lower inventory; nine hotbar slots plus real offhand within the upper nine-column width; centered recipe book with crafting available at narrow sizes; existing ghost result, Shift armor guard, F exchange and vanilla handler authority retained | Geometry, artwork, narrow rendering/input accessor and existing inventory tests. Real drag/Shift/server synchronization, lore colors and enchantment glint require live QA. |
| C1–C4 | Physical keyboard/mouse cannot open binding picker; click rendered keys; both Alt reserved, primary buttons locked, Shift/Ctrl allowed; new mouse thumb polygons share drawn coordinates; hidden six-slot reorder editor removed; first-registration ordering persists and reconciles legacy files | Binding/order restart, removal/re-add, mouse-art and blocklist tests. Physical device mapping requires live QA. |
| D1–D6 | Ninth ivory settings icon; smaller fifth My Info icon; exactly three categories; own/other local VFX opacity; camera defaults 1.5/1/5, reverse shoulder, smart cast, reset/cancel/atomic save; unknown properties preserved; normal mouse aiming, F5 semantics and removal of free-cursor/Alt camera | Camera/config failure tests, actual Claude particle/trail/model alpha path, server owner metadata. GPU effects and F5 play experience require live QA. |
| E1–E5 | Rigid straight 3D preview, screen-centre ray then eye obstruction, 42 range metadata corrections, faint element colors, compact hints and existing cancel/lifecycle paths | Aim plane/range tests. All spells, air/walls, protection regions and latency require live QA; future homing/bounces are not predicted. |
| F1–F2 | Nickname capsule inside equipment panel, centered broom label, preserved 4+4 gear/stats; five-card paging; ten integer rounded gauge segments and equipped comparison; Object-based BroomApi 0.3.0 | Existing equipment/protocol/permission tests. Model lighting, tooltip layering and live equip acknowledgements require live QA. |
| G1–G5 | Clean arch/diamond/feather and cooldown fill, no percent label; HUD in chat/cursor; four WASD edge-triggered recatch steps; server-confirmed approach over ten ticks; low balance; elapsed fatigue and cooldown survive recatch including response wait; smooth fatigue cap with no fixed expiry; roll impulse +20%, existing A/D double-tap and collision rules | Motion tests and deterministic 270-run simulation. Actual feel, lag, collision and fall protection require live QA. |
| H1–H4 | Smaller combined name/HP/chance HUD; smaller channel UI; independent shiny UV crop excludes neighboring gold border; rounded HP; aimed information up to 25 blocks including walls/chat/cursor; owned status comes from server permission; actual attack/taming obstruction unchanged; no golem substitution | Label/authority and existing taming tests. ModelEngine tags, long names and aim selection require live QA. |
| I1 | Real-time 72-hour-per-season clock, explicit offset timestamp and time zone, restart-independent elapsed calculation, four-season cycle; no DB schema changes | Four boundary/restart/zone/config tests. **Activation awaits the user's chosen epoch/start season/time zone. Live calendar mode is preserved; no date invented.** |

Own/other effect opacity defaults to 100%/100%. Unknown server effect owners use the “other” value. Owner metadata is additive; older clients keep existing rendering. Opacity changes drawing only, not server casts, mana, cooldown, damage or simulation lifespan.

## Approved images

User-downloaded `Chacademi_PNG_Assets_20261013.zip`, 7,068,311 bytes, SHA256 `2d7905ecce9b04f7cc1df84a2938474258bf723f1aa9438dd1270e275c256f0e`. User file and hosting copy match. The six original production PNGs and extracted components are in source; private feedback images are excluded. Actual pixels were inspected with the supported image viewer without a file_id failure. No more browser/Drive/Library downloads were attempted after the user's stop instruction. See [slice manifest](CODEX_FOLLOWUP_ASSET_SLICES_20261013.md).

## Reproducible host commands

Run from `C:/Chacademi/staging/aim-straight-20261013/source`, with `JAVA_HOME=C:/Program Files/Java/jdk-21.0.10` and `GRADLE_USER_HOME=C:/Chacademi/tools/gradle-cache`:

```powershell
./gradlew.bat :magic-codex-paper:test :magic-codex-paper:build :magic-mythic-paper:test :magic-mythic-paper:build --offline --no-daemon
./gradlew.bat -p magic-codex-fabric test remapJar --offline --no-daemon
./gradlew.bat -p chacademy-motion/chacademy-motion-plugin test build --offline --no-daemon
./gradlew.bat -p chacademy-motion/chacademy-motion-mod test remapJar '-PplayerAnimatorJar=C:/Chacademi/staging/survival-inventory-20261012-task5/dependencies/player-animation-lib-fabric-2.0.5+1.21.4.jar' --offline --no-daemon
./gradlew.bat -p chacademy-story/mod test remapJar --offline --no-daemon
./gradlew.bat -p portable-vfx-runtime :common:protocolTest :client:test :client:remapJar :paper:test :paper:build --offline --no-daemon
./gradlew.bat -p magic-codex-fabric -PvisualCheck -PfollowupCheck runVisualTest --offline --no-daemon
```

Expected: build tasks succeed, no failed/error tests; optional authored VFX fixtures must be reported as skipped if not supplied. Visual fixture succeeds only with `run/followup-visual/done.txt`, no `failed.txt`, and nine screenshots. A successful compilation does not count as live game QA. Final XML counts, artifacts and hashes are recorded separately in `FINAL_FOLLOWUP_EVIDENCE_20261013.json`.

## One combined live-game regression checklist (not yet executed)

Use a test account, record each row PASS/FAIL with screenshot/log and before/after inventory, mana, cooldown or quest state. Test GUI 2/3/4, window resizing, long Korean/English names and resource reload where applicable.

| Test / actual action | Expected result |
|---|---|
| `/메인퀘스트`; open multiple chapter groups, scroll, resize, reopen | Correct main quest categories/state; independent folds; existing Story ch1-1 through ch1-6 descriptions remain intact. |
| `/유저관리 <대상> 퀘스트기록편집 조회 story:ch1-1` | Authorized target record shown; unauthorized caller refused. |
| `/유저관리 <대상> 퀘스트기록편집 부여 story:ch1-1`, then `진행 story:ch1-1` | Server record changes as requested; suggestions and Korean/English target names resolve unambiguously. |
| `/유저관리 <대상> 퀘스트기록편집 완료 story:ch1-1` | Record marked complete without issuing completion rewards. |
| `/유저관리 <대상> 퀘스트기록편집 초기화 story:ch1-1` | Quest progression reset; reward and affinity receipt records preserved, no duplicate payout. |
| Existing `/유저관리 <대상> 퀘스트 <메인/일일> <조회/부여/진행/완료/초기화> [제목/ID]` | Existing title/ID commands remain compatible; ambiguous titles refused. |
| Trigger quest/friend/system/unavailable notices, repeat, dismiss, chat/cursor/dialogue/menu transitions | One card and sound per ID while queued; bold dynamic text; stable stack; hidden time paused; correct click consumption. |
| Open survival inventory; Shift ordinary items/crafting input/output/armor, drag/split, F swap, tooltip, enchanted items | Real server slots stay synchronized; armor Shift auto-equip alone blocked; 27+9+offhand maintained; high-res/glint/lore colors preserved. |
| Open recipe book at small window, click recipe and take result; close/reopen inventory | Book centered, crafting visible/clickable, result hides empty crystal, reopen starts book closed. |
| Apply several effects with long names/levels/times; resize | Icons/name/level/time fit; bounded scrolling, hidden when empty. |
| Settings → 키세팅; physical Shift/Ctrl/Alt/thumb versus rendered-key click; replace/remove/re-add and restart | Physical keys do not open picker; both Alt forbidden; actual thumb mapping; six capacity, first registration order persisted without old reorder UI. |
| Settings → 스킬 이펙트; own/other 0%, 50%, 100%; cancel/save/restart | Correct caster's particle/trail/model alpha changes locally; no mana/damage/lifetime changes. |
| Settings → 시점 · 조작; sliders/reverse/smart/reset/cancel/save; F5; mouse movement | Defaults 1.5/1/5, cancel unchanged, saved values survive restart, no free cursor/Alt rotate, first/front views remain valid. |
| 42 spells: preview then left confirm/right cancel; wall/air/near entity; death/disconnect/menu | Stable 3D indicator and range match cast; no through-wall damage, protected locations still rejected; no leaked preview. |
| Equipment broom slot → five-card pages/wheel/tooltips → equip/cancel | Existing 4+4 equipment/stats retained; badge changes only after server confirmation; grade/integer segments readable; no model/tooltip scissor leak. |
| Give/revoke `chacademy.broom.own.<ID>` / `chacademy.broom.own.*`; OP and unowned player | Server owns equipped/owned state; revoked broom is not usable; OP sees all; no false local badge. |
| Flight mount, gale_dash/light_leap spam, actual sneak key once, immediate remount attempt | Mount 80 mana/30s CD; two movement spells rejected before spend with deduplicated notice; sneak free dismount, no CD reset or simultaneous LShift spell; no invented hotkey. |
| Owned flight >10s; unowned practice; falling/creative flight before and after | Owned unlimited duration; practice 10s; original allowFlight restored, safe fall handling retained. |
| Four recatch WASD prompts, wrong/held keys, latency, world/death/permission change | Edge-based ordered sequence; one request, server validation; smooth ~0.5s approach, low balance; no duplicated broom or reset fatigue/CD. |
| Smooth/turning/rain flight; A/D double-tap near wall | Gradual maximum stability decay, recovery never exceeds cap; skill/grade matter; no fixed 60s expiry; increased roll remains collision-bounded. |
| Aim normal/shiny/owned/untameable monster through wall within/over 25 blocks; chat/Alt | Small readable name/rounded HP/chance or 보유중; no shiny gold stray line; information only through wall; damage/taming authority unchanged. |
| Wild server fauna and attack-target regression | Grounded wildlife stays grounded as configured; invalid/friendly/protected targets refused; no unrelated pet/golem registration. |
| After user chooses season epoch: configure real-time mode, cross 72h/288h boundary and restart | Spring→summer→autumn→winter by real elapsed hours, stable across restart/time zones; no game-tick dependency. |

## Deployment boundary

Only final verified personal UI/Motion/VFX client mods and necessary school/wild Bridge/Motion/VFX plugins are replaced. Story is regression-built but unchanged binary/config is preserved. Each replacement requires backup and SHA comparison; all unrelated mods/plugins/configs are hashed. Minecraft is never forcibly closed. School/wild stop only through `Stop-Network.ps1 -Server school|wild`, followed by normal per-server start. Velocity PID continuity and each server's startup log must be recorded. Public launcher feed is excluded.

## Recorded final results

JUnit: 973 discovered, 970 passed, 0 failures, 0 errors, 3 skipped. Protocol harness: 31,753 counted assertions plus owner round-trip and four malformed-packet rejection checks. Optional model fixture tests are explicitly listed in the evidence JSON. HolyTaming uses the preserved host fixture.

The isolated GPU fixture compiled and launched but terminated during LWJGL initialization with exit code -532265403 before any of nine cases completed. No live-game or GPU visual PASS is claimed.

Deterministic simulation: 270 runs, 30 seeds per profile/mastery. Middle mastery (0.5), stability 1.3, ordinary scheduled 65–85° turns: mean 64.26 seconds, range 51.50–75.15. Novice mean 39.37, expert 102.04. Smooth flight remained mounted at the 180-second observation cap in all 90 cases. These are synthetic input results, not a measured human-play average. Source and CSV are included; rain is harsher and remains live-feel QA.

## Verified deployment

Source code commit `b912fcf7f003e0533b0f624b4b4236c541ffb358` was confirmed on both public integration branches. Hosting artifacts: `C:/Chacademi/staging/aim-straight-20261013/release-final-followup-20261013`; backup: `C:/Chacademi/backups/final-followup-20261013`. School PID 3640 and wild PID 6456 reached `Done`; both loaded the new Bridge/Motion/VFX versions, 89 gameplay routes and 299 spells/96 visual bindings with zero skipped bindings. Velocity remained PID 7552. Installed six server JAR hashes match the release manifest; unrelated plugin hashes are unchanged.

Personal UI/Motion/VFX replacement completed with matching hashes. Twelve unrelated files (including Story) and all 1,808 configuration files retained identical hashes, preserving the private chapter descriptions. No Minecraft process was forcibly closed. Backup receipt remains private in the local task workspace.

Startup warnings are recorded and compared with the archived pre-deployment logs in [deployment evidence](FINAL_FOLLOWUP_DEPLOYMENT_20261013.json). Existing Mythic sample, database duplicate/migration and wild ItemsAdder generated-pack warnings are outside this patch; no unrelated DB/schema/resource-pack change was made. GPU/live game QA and the season epoch remain outstanding as listed above.
