# Attack targets and grounded rare wildlife - 2026-10-12

Status: deployed with the [combined journal/input release](CODEX_JOURNAL_INPUT_RELEASE_20261012.md) on2026-10-12. Original preparation base:0673e80.

## Attack targets
Targets.enemy now accepts all valid living gameplay entities, including animals, custom mobs, tamed animals and MCPets. Self, dead/invalid entities, NPC metadata/tag, armor stands and creative/spectator players are excluded. Legacy pvp-worlds and configurable pet/prefix deny lists no longer gate combat. WorldGuard chacademi-magic, region PvP deny, invincibility and damage event cancellation remain authoritative.

SpellRuntime prepare/execute use MagicProtectionLink.canTarget; bolt collision/bounce, area selection, mark hits and final hurt all use the same Targets policy. No direct forced-health damage was added. The unused old SpellRuntime enemy helper is not used by this path.

Conflict to report: existing CA_Pet definitions retain Invincible:true, so accepting a pet as a target does not override its plugin damage protection. Riding flight and pet ownership remain unchanged. Removing pet invincibility was not silently bundled into this patch.

## Wildlife
Actual school/wild mobs/chacademia_custom_wild.yml matched at inspection. The new deployable file is reference/output/wildlife-ground-v3/chacademia_custom_wild.yml, containing only 276 CA_Wild definitions. Replace only the existing wild file during coordinated deployment; do NOT install the historical combined custom-creatures file alongside it.

60 entries (30 species including four wyverns, normal+shiny) changed from VEX/PARROT to HUSK with explicit ground AI. Existing hostility and target selectors remain; aquatic COD/DROWNED movement remains aquatic. No global flight interception or new per-tick scans. Existing already-spawned mobs retain their old entity type until naturally removed/replaced; do not bulk-kill them.

Rare means existing stars=3: 60 species/120 definitions. Health x1.5, positive damage x1.35, speed x1.2. Typical 130/9/0.24 -> 195/12.15/0.288; glacialcore 700/14/0.24 -> 1050/18.9/0.288; passive nocsy_cat_fallen damage remains 0. Full before/after/type/rarity list is changes.json beside the deployable YAML. Capture baseChance is not spawn probability; capture values, random spawn probabilities, habitats, shiny rate and rarity were not changed. All 276 historical pet definition blocks are byte-identical to the pre-change commit.

## Validation
Host Java21 offline Gradle :magic-mythic-paper:test :magic-mythic-paper:build passed: 20 tests. Tests exercise actual Targets with animal/player/tamed/NPC/self/dead/creative/spectator, area selection and final recheck, existing WorldGuard protection, and real YAML parsing/ground AI/stats/pet preservation. Live projectile and ModelEngine locomotion acceptance still require the coordinated deployment/playtest.

Artifact: `chacademia-magic-mythic-0.1.2-recovery.1-gimmick.1-targets.1.jar` SHA256 `114b867690ba687130135256cea6492e02430c51452d5846e64acf2254eed9cf`.
Host staging: C:/Chacademi/staging/magic-protection-20261011-task5/targets-wildlife-20261012.
