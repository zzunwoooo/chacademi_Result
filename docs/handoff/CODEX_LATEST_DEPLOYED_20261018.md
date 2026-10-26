# Latest integrated deployment - 2026-10-18

Status: DEPLOYED AND VERIFIED. Supersedes CODEX_LATEST_CANDIDATE_20261018.md.

Built source commit: 4df0fad21d0bc2dc3f12e6ff3f5a16ecae0ef866. Selected Kyudichi through ad13feb, shop 2369096 and shared alchemy 406151b on verified deployed ddd6726.

| Module | Tests passed | JAR | SHA-256 |
|---|---:|---|---|
| ui | 371 | magic-codex-ui-0.40.1-integrated-20261018.1+mc1.21.4.jar | 9189b32dc5f9e6b765a7663e8066bc461419d99793546a80db6dc187c2e65ae8 |
| arena | 18 | chaca-kyudichi-0.1.2-integrated-20261018.1.jar | 0a661c0559d67f558d6916304b90652d2df1248edbf5478a20c980f260672090 |
| velocity | 47 | chacademi-party-velocity-0.2.2-integrated-20261018.1.jar | 712a37dff4079bb691226c12166ce5b320e1f3cb21edfe585a618602ec5b9c9f |
| bridge | 347 | magic-codex-bridge-0.31.1-integrated-20261018.1.jar | c95d86d9843d52f00574e94b0534be2267333bd760e0d5c6aab536b07d159e55 |
| motion-client | 31 | chacademy-motion-0.3.1-integrated-20261018.1.jar | 174366031958d8c2ace84498fd155bf562f9cb3ea4ff9be367d9bddb4b9fddfb |

814 tests, zero failures/errors/skips. Minimal get(int) compilation collision and contradictory legacy shortcut tests are documented in the candidate handoff.

Deployment: personal UI/Motion installed and hashes verified. Approved vanilla controls applied (sprint Shift, crouch C, inventory Tab, list grave); other personal settings and Story preserved.
School/wild/arena: Bridge updated. Arena: Kyudichi updated. Velocity: party/queue updated. Existing 95 unrelated plugin JARs and three generated resource packs preserved by SHA-256. Housing and Motion server plugins unchanged.
Four new processes completed startup and answered actual Minecraft status packets (protocol 769); expected versions found in logs, no checked fatal startup markers.

Approved additive migration: kyudichi_season and codex_alchemy_config; kyudichi_match_player.damage/rating_after; kyudichi_match.name_a/name_b/house_a/house_b. Existing 57-table row counts preserved. Final read-only backup: 124 rows, SHA-256 f5432cb3051713975fbca41d3571b6fe4d12d5371a0b8694d4fd20f09a39594e.
Shared alchemy revision 1 exactly equals original preserved file on school/wild/arena. Season table empty; no season start/reset commands executed. No accounts, privileges or network rules changed.

Temporary launcher published: [v0.1.12](https://github.com/zzunwoooo/chacademi-launcher/releases/tag/v0.1.12), 13 uploaded SHA-256 digests verified; public manifest bytes and main feed JSON checked, installer/JAR/resource ZIP download responses verified. Existing v0.1.11 held draft retained.

Original eight lobby icons match ad13feb byte-for-byte in source and JAR. All 887 previous PNGs, thirty broom models/trails and 2257 resource files/167 VFX packs preserved.
No maps copied, game forced closed, game launched or screenshots taken. Live multiplayer/gameplay and visual acceptance were not performed. Season activation and duel arena registration remain owner administration tasks.

Hosting evidence: artifacts/latest-20261018; staging/integrated-latest-20261018/installed-latest.json and startup-verification-latest.json; staging/temp-launcher-20261018/publication.json and public-verification.json. Backups: backups/integrated-latest-20261018.
