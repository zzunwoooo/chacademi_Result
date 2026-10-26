# Fishing protocol v2 operating integration - 2026-10-20

Merged claude/fish-shop-20261020 through 5916b28 (7b862ac, 73b8bec, 3153cae) onto operating 388cb52. The existing VFX chunked readiness and wild main-thread spawn gate remain intact.

Incoming UI, fishing minigame, codex/reward displays, caught-only shop list and gear DB are included. FishingProtocol is v2. All three shared Bridge instances and personal/public clients must update together.

Integration retains the existing codex_fish_tank identity ledger rather than replacing its rows with destructive moves to codex_fish_item. ITEM rows provide authoritative metadata to the new id-only fish UI. Transactional capacity, owner locks, withdrawal generation tokens, terminal identities, catch recovery journal, commit-before-consume and uncertain-payment quarantine remain in effect. Admin fish are registered before delivery. Old fitted rod PDC parts migrate as a complete loadout, including when durability is the first write. Common ShopStore NPC routing is retained.

User scope: build and deploy only. ItemsAdder registration, item numbers, original rod materials and resource-pack generation/deployment were not changed. Live fishing/mana/shop configuration remains unchanged. The 681 removed client item assets are intentional incoming changes; all 200 fish UI illustrations remain.

Validation: Bridge403, Fabric427, Kyudichi18 and Discovery35 tests passed, including hosting MariaDB two-connection regressions. Existing item replay, payment rollback and catch journal tests pass. Added item metadata/reentry and legacy fitted gear migration regressions.

Deployment: school/wild/arena Bridge 0.31.2-integrated-20261020.4-fishing-v2.2. All server starts and 169 protected hashes verified. PortableVFX readychunks.1 and wild spawngate.1 retained; Velocity unchanged. No backup or map copy, game force-close or gameplay capture.

Live player fishing/minigame, full VFX readiness and natural wild spawn QA remain pending a player reconnect. Zero online players during deployment is not evidence of actual gameplay success.

Bridge SHA256: d4dccb825dafb42d726475a7fc36448cca54a98f37ae7517059e938360ed3f91

UI SHA256: 3e84fb354a94aa295998f218f455986bd48c8da544cee1117b4099c45e2034c9

Final compatibility correction: pre-v2 admin fish had a UUID plus positive finite size/date PDC, an empty generation token and no database row. Preserve these through a strict legacy fallback in display and insert. New id-only fish, stale withdrawal tokens and mismatched existing rows cannot use this fallback; SQL terminal identity validation remains authoritative. Seven additional regressions passed.

Public temporary launcher v0.1.20: https://github.com/zzunwoooo/chacademi-launcher/releases/tag/v0.1.20 (commit 234277c33a2eaa0df526fccabaf6211bada837be). All eight release asset digests, actual public UI download, updater SHA512 and main feed verified. Only UI changed;84 modules and VFX/preload settings preserved. Personal UI hash matched,14 other mods/options/preload limits preserved. No gameplay capture or force-close.
