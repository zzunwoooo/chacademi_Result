# Latest operating integration candidate - 2026-10-18

Status: built and tested; NOT deployed. Database schema approval pending.

Base: ddd6726434a709a70498b3f64ea0671979e03408. Actual installed server JAR hashes match the previous integrated deployment receipt. Original worktrees and their untracked work were preserved.

Selected upstream commits: 340d242, 03cc85b, c8eabe8, ee1f403, ad13feb (Kyudichi); 2369096 (shop notices); 406151b (shared alchemy delta; existing admin editor retained).

Preserved: housing f1d3302, shop db396a7, shoulder crosshair 9ed319e, thirty broom models and trails, Space ascent, barrel roll f0e9aaf, existing alchemy administration and artifact ownership. New owner controls supersede old crouch-to-dismount: crouch descends; the flight spell dismounts.

Minimal compile fix: rename the new static KyudichiLoadout.get(int mode) to forMode(int mode), including its two callers; Java forbids collision with the existing instance get(int slot).

Test alignment: upstream 03cc85b explicitly makes all supported non-WASD keys rebindable and preserves spells on former shortcut keys. Four legacy assertions contradicted the same commit's new tests. Updated those assertions while retaining duplicate-key, malformed-file, WASD migration and saved-file-preservation checks.

Hosting results: UI 371; Kyudichi 18; Velocity 47; Bridge 347; Motion client 31. Total 814, zero failures/errors/skips. All corresponding test/build/remap tasks passed.

Resource verification: all 8 icon PNGs exactly match ad13feb and the built JAR; all 887 PNGs from the previously deployed UI JAR unchanged. Motion contains 30 models and 30 trail mappings. No maps copied or screenshots created.

Pending schema approval: kyudichi_season; kyudichi_match_player.damage/rating_after; kyudichi_match.name_a/name_b/house_a/house_b; codex_alchemy_config. No DB account, security or network privileges expanded. Do not activate season-reset commands.

Candidates: C:/Chacademi/artifacts/latest-20261018/build-evidence.json and resource-verification.json.
Temporary launcher candidate: C:/Chacademi/staging/temp-launcher-20261018, v0.1.12; existing v0.1.11 held draft retained. Existing 2257 resource files and 167 VFX packs retained. New default vanilla keys are seed-only; personal options preserved.

Deployment not yet performed. Live startup and in-game acceptance remain unverified.