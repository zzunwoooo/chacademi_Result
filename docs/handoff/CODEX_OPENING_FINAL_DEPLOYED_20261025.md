# Opening-final and townmap — deployed 2026-10-25

Owner approved after the complete final report; parent sent the shutdown notice before execution. Product source is `70044a79232b22b37c5e024f3fc0bcaf808a04cc` on `claude/opening-final-20261025`. Later changes in this documentation commit do not change that tested product tree.

- Applied 14 server JARs and 44 configuration/content files. Archived the 29 old sorting-family files after narrow backups; no user DB/world was copied or removed.
- School/wild/arena were stopped individually with Stop-Network.ps1 -Server; no controller Stop was used. Proxy PID9392 stayed alive.
- Stop requested 15:19:25 UTC; starts requested 15:22:48 UTC. Done logs: wild15:23:57,arena15:23:58,school15:24:00 UTC. All three backend Minecraft status packets plus proxy local/public status passed. This is status verification, not authenticated player login.
- All 58 installed files matched their expected SHA256 after boot. Target plugins enabled, none disabled; no new DB/link/CHAMETEOR loading errors in startup logs. No manual in-game UI, spell, story or GPU rendering checks were performed.
- Source JUnit2263:2254 passed,9 existing optional environment skips,0failed/errors; VFX protocol main33878assertions and Python13 passed. The updated actual school JAR passed26 focused runtime tests. See earlier verification report for the exact nine skipped cases.

[Final receipt](opening-final-deployed-20261025/receipt.json), [per-file manifest](opening-final-deployed-20261025/manifest.json), [townmap checks](opening-final-deployed-20261025/townmap-validation.json), [safe config patch spec](opening-final-deployed-20261025/townmap-patch-spec.json). Static deployment input manifest SHA256: dc320cf1d2a91e7ce16a5ad2028ece0d10f2ad69b38da6ec387490c67df19d5e.

Temporary launcher main is `c023a10c2fb379c7898717cffa0fc59917f84e6c`, content release `content-opening-final-20261025-task3`. Public three downloads were fully SHA-checked. Actual default public feed and payload manifest matched Git blob bytes (Windows CRLF staging hashes differ): distribution151ae4ef550ec20f622aeda95d5e5b8329f8d09ed555d44bf28309167ba3c9e4; payload864d91cbbc4b6f2f85890b3a5101092a4e5caf9a68427419b5693c0a2d719825. [Feed proof](opening-final-deployed-20261025/public-feed.json). First large upload returned408 and was confirmed absent before a native curl retry; final public SHA passed. Initial canonical CDN cache was old; fresh public bytes and then canonical URL both converged before final verification. Installer latest remains v0.1.28.

[Public UI proof](opening-final-deployed-20261025/public-icons.json):26 corrected icons,12 existing gimmick originals and1479x1064school PNG verified. Map transforms/wire passed;0.25initial scale still needs in-game landmark confirmation/calibration. School NPC12 and all unrelated story step values were preserved; occupied oldalchemych1-17 is now ch1-17-alchemy-preserved while sorting family uses17. Wild/arena Story plugins outside the approved school-only Story JAR target were left unchanged.

Private artifact root: C:/Chacademi/artifacts/opening-final-20261025-task3/townmap. Limited57original-file rollback backup: C:/Chacademi/backups/opening-final-20261025-task3-20261025T132141Z. New30paths are tracked for hash-guarded rollback. No rollback was needed. New default files created by plugins are distinct from the58explicit install targets.

The binary baseline dependency remains: these are live-preserving bounded merges, not a source-only reproduction of all legacy/cache hotfixes. Townmap packaging sources are archived under tools/opening-final-20261025/townmap. No binary baseline, operating YAML bodies, credentials, player data or large backups are published.

Personal launcher,dungeon,nonexistent tutorial servers,cancelled harbor cutscene and the separate151loot followup were excluded. Feed reservation is released; followup publishers must re-read/rebase launcher main from c023a10 or later, preserving this bundle.
