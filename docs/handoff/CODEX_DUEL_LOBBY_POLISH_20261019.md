# Latest HUD v4 and duel lobby integration - 2026-10-19

Supersedes the earlier intermediate lobby-only report. Final build includes the complete HUD chain 37f8643 -> bd6db65 -> 14c2606 -> 7163262 and duel lobby 289c6f9. Both feature tips are ancestors of this integration branch.

The native 2048x84 gold HUD frame, clipped gauges, 16 new 256px status icons and timer/text rendering are included. Name/divider/damage coordinates remain the exact latest 7163262 values (-70/-12/-24) in their intended 2048px layout. No 1237px adaptation remains. HUD Java matches 7163262 and lobby Java matches 289c6f9.

Client version: 0.40.1-integrated-20261018.1-statushud.3-lobbypolish.1+mc1.21.4

SHA-256: 56d65a4b8ee9c800d4819229d29e8398095892b73479bb1f1bc57c73721761a0

384 client tests passed, including wide-screen lobby hit targets and actual PNG/font geometry at six GUI widths and four device scales. Actual gauge pixels remain clear; decorative frame/stroke contact is recorded in the local geometry audit. Runtime remapJar/build passed; optional source archives were excluded under host disk constraints. All 25 approved asset changes match upstream and 1528 unrelated assets are preserved.

Temporary launcher v0.1.14 published with explicit user approval: https://github.com/zzunwoooo/chacademi-launcher/releases/tag/v0.1.14. Release ID 416859534, commit 994472982e24f58f12bf339465cb4553e8d6707f. All eight public files were actually downloaded and SHA-256 matched the approved candidate and GitHub digests. Main distribution/payload/package JSON matched; the final UI JAR URL and hash are correct. Existing Motion/resource URLs also responded. No personal client or server redeployment during publication.

No server source changes or deployment were needed. Local personal-installation evidence is reported separately and is not included in this repository summary. No private installation paths, settings or service process data are included.

Manual checks: reopen Minecraft normally; use /대상체력바미리보기 일반, 이로치, 보스, 플레이어 and /큐디치미리보기 로비, 로비1대1. Verify new HUD appearance, special-key rebinding and party overlay/return. Live gameplay was not captured.
