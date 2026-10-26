
## 2026-10-20 post-start correction
- Restore the exact 0d39e15 MobShot AIM-point homing/orbit completion and caster exclusion; the inspected diff contained no hitbox or VFX changes. Three entity-selection regression tests added.
- Deploy only the 56 newly introduced metaskills from deployment/mythicmobs/Skills/chacademi_player_additions_20261020.yml alongside existing live chacademi_spells.yml. This split is deployment-only; never load it alongside the complete source main file. Existing live definitions have no name conflicts. Validate all 86 runtime routes and nested skill references before and after restart.
- Mythic version 0.2.0-integrated-20261020.1-aimpoint.1. All 69 Mythic tests pass. Test VFX dependency now points at the installed integrated artifact rather than a removed old runtime JAR.
- First rollout: 4,379 approved files and 15 old JAR removals; all four services reached ready state without plugin loading errors. Multiverse normalized wild worlds.yml on startup; all spawn limits remain unchanged (50/8/10/15/4/4/4). No assistant write, restoration, backup, map copying, game termination or in-game capture.
