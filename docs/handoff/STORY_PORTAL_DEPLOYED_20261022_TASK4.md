# Story portal reentry update — deployed

Deployment completed on the SSH host at 2026-10-21 22:03 UTC (host calendar 2026-10-22).

## Sources and reconciliation

- Requested source: `zzunwoooo/chacademi-magiccodex-source`, `claude/story-steps-20261020`, `325ac04de8c1bad043ca0d7794eb954611334002`.
- Inspected the actual commit and handoff section 11 first: only `QuestEngine.java` plus the handoff changed. Closing a location-triggered dialogue now requires leaving the portal before it can reopen.
- Current integration base: `zzunwoooo/chacademi-server`, `8fa1655ce58771b38eefd58d601f0d1cd11903fa`, retaining `7ff347ff` NPC integration and `c6364d95` verification fixes.
- That current tree intentionally lacked the Story quest engine, as recorded by task15. Reconciled the separate `claude/fix-story-20261021` branch with it rather than building or deploying the old branch wholesale. Merge `607d2ee9` retains A07 reward receipts, A13 durable submission/start-once logic, C10 recovery, C11 house-preset source, and existing economy/NPC/client work. Preserved the task13 NPC model-height marker implementation.
- Requested patch applied as `4770b8c8`; final source correction and four behavioral regression tests: `13969044e38b469dd56ea6532177fa300eccd754`.
- Branch: `codex/story-portal-20261022-task4`. Host worktree: `C:\Chacademi\staging\story-portal-20261022-task4`.
- Final tick keeps the latest fresh-item-readiness check and adds the portal gate to that same path. Administrator restart keeps the A13 `manual=true` behavior. Duplicate code produced by automatic conflict resolution was removed before building/deployment.

## Verification

All work and Gradle execution occurred on the SSH host with its existing Java 21 and dependency caches.

| Module | Tests | Failures/errors | Skipped |
| --- | ---: | ---: | ---: |
| Story plugin | 81 | 0 | 0 |
| Story mod | 55 | 0 | 0 |
| MagicCodexBridge | 429 | 0 | 2 |
| Housing | 9 | 0 | 0 |
| NPC | 31 | 0 | 0 |
| Total | 605 | 0 | 2 |

The two skips are existing real-DB gated tests. The four new plugin tests cover cancellation staying closed in both tick/location paths, leaving and returning opening once, unrelated abort rejection, automatic triggers still retrying, and quit cleanup. Story mod compilation initially exposed a missing `LocalStoryRewards.java` test-source input after the A07 change; adding that helper to the existing test source set fixed it. Final Story plugin `test jar`, Story mod `test remapJar`, and affected server-module tests all passed. Client artifacts were not deployed.

## Deployment and recovery

- Only changed deployed artifact: school's `C:\Chacademi\network\school\plugins\chacademy-story-plugin-0.1.3-houses.1-commands.1.jar`. The existing filename is retained; embedded version is **0.1.4-quest.3-portal.1**.
- SHA256: `5c3d9df94f34c37da8dac252cdcafe1ea1120d0991aba3332df9af4cfd4f35eb`.
- Candidate: `chacademy-story\plugin\build\libs\chacademy-story-plugin-0.1.4-quest.3-portal.1.jar` inside the host worktree.
- Previous live SHA256: `8078b4c69edad939d459f168e23ac5495cd8375bb4d489a0d1aab86628273cb9` (0.1.4-quest.2).
- Backup: `C:\Chacademi\backups\story-portal-20261022-task4`, including deployed JAR, all 42 Story files, plugin/config hash manifest, before-state, and an additional Story snapshot after clean shutdown.
- Verified school update queue empty before shutdown. Used `Control.ps1 -Action Console -Server school -Command stop`, confirmed school stopped and world/chunk I/O saved, replaced only the guarded Story JAR, then `Control.ps1 -Action Start -Server school`.
- **Did not use `Control.ps1 -Action Stop`**: Supervisor's stop action is blanket, despite the server argument.
- School PID changed 29092 -> 24900. Wild 30464, arena 23616, Velocity 25368, supervisor 23584 remained unchanged. School had one online player at the preflight check; normal shutdown disconnected that connection.
- To roll back, use the same school-only normal shutdown/start procedure and restore only the backed-up Story JAR after checking the current target hash. Do not restore old progress/config over subsequent player activity.

## Observed runtime and preserved state

- New embedded Story version loaded/enabled, dialogue graph count 32, quest definitions 15, Citizens NPC click hook and Bridge/Housing success-event hooks connected, server ready (`Done`), and all four Minecraft status ports returned responses.
- Deployment audit found exactly one changed top-level plugin JAR and zero changed Story files compared with the clean-stop snapshot. Existing `config.yml`, `steps.yml`, `progress.yml`, `quest-progress.yml`, dialogue files and activation state remained byte-identical.
- Existing Story engine was already running before this update. No activation, stage reset, administrator progression, config reset, schema/DB migration or client/launcher update was performed. Bridge/Housing/NPC/C11 source was reconciled and tested but their operating JARs remain unchanged; this is not a deployment claim for those newer modules.
- No Story ERROR/SEVERE or startup exception observed. Existing `finish-if-missing-mod: true` warning was preserved. Unrelated startup warnings remain: MythicMobs SkeletalKnight loading and MariaDB duplicate key/column messages from shop/portrait plugins; those plugins and settings were not changed.
- Real live-player ESC/wait/exit/reenter acceptance is still untested. Automated tests exercise that behavior without editing operational progress or introducing a test quest. C01 operational DB migration remains unperformed.

Machine evidence in host worktree `verification/`: `deployment-result.json`, `health-after.json`, `story-build.log`, `dependent-build.log`, `story-mod-build-2.log`, `audit-deployment.py`; JUnit XML under each module's `build/test-results/test`.
