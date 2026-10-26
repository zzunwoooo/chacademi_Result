# Dungeon gimmick spells operating integration - 2026-10-20

Integrated claude/dungeon-gimmick-spells-20261020 through 2883264 (005b977 implementation,80cfb69 review) onto operating6b9866b. Existing fishing v2/transactional ledger, chunked readiness and wild asynchronous spawn fixes are retained.

## Planning update

Owner decision supersedes undeveloped slots168-299:105 defaults are retired. Previously developed creature spells remain at file orders300-323 (planning215-238); they were not renumbered. New gimmick spells use239-250. fish_trap324 remains. Mana/cooldowns/ranges below are provisional, icons empty, acquisition text descriptive only; no Discovery rule or donation price was invented.

|#|ID|Name|Element|Mana|Cooldown seconds|Range|Acquisition text (not implemented)|
|---|---|---|---|---|---|---|---|
|239|thawing_heat|해빙의 열기|fire|40|8|12|얼음 위에 화로를 피워 둔 채로 30초 머무르기.|
|240|steam_ignition|증기 점화|fire|30|6|12|물이 든 가마솥 아래에 불을 붙이기 5회.|
|241|water_level|수위 조절|water|20|1.5|12|양동이로 물을 퍼 담았다가 다시 붓기 10회.|
|242|stone_weight|무게추|earth|30|5|12|압력판 위에 서로 다른 물건을 올려 5회 작동시키기.|
|243|vine_awaken|덩굴 깨우기|earth|40|10|12|뼛가루로 묘목을 나무로 키우기 5회.|
|244|light_beam|빛줄기|light|50|6|32|유리판을 든 채 횃불 앞에 10초 서 있기.|
|245|mirror_tune|반사 조율|light|15|1|20|아이템 액자 속 물건을 8번 돌려 처음 모양으로 되돌리기.|
|246|prism_tint|프리즘 물들이기|light|15|1|20|서로 다른 색 염료 6가지를 모으기.|
|247|statue_wake|석상 깨우기|dark|20|2|16|밤에 갑옷 거치대 앞에 30초 서 있기.|
|248|rewind|되감기|dark|30|5|16|시계를 들고 같은 자리를 10번 오가기.|
|249|conduit_turn|도관 회전|earth|10|1|16|레드스톤 가루를 30블록 이어 깔기.|
|250|dimension_mark|차원 표식|dark|40|5|24|엔더 진주로 순간이동 10회.|

Mechanics: thaw channels3s and cancels on damage/distance>16; steam triggers valve; water level0-3; weight triggers after0.65s and uses VFX only; pot grows climbable vines; beam roots movement/jump3s and reflects through mirrors/prisms; tune advances/reverses mirror or dial; tint cycles six colours; statue/conduit rotate90degrees; rewind restores one device change within60s; mark pairs two gates within30s for120s. Sneaking reverses level/tune/tint. Invalid target refunds mana and clears cooldown. Rewind/mark are dark; conduit/weight are earth.

35rekindle/48wick_trimming now route through chagimmick, with brazier priority and existing vanilla ignition/extinguish fallback. Existing live noop skill placeholders were changed only for these two explicitly migrated skills. Existing mana/held metadata and unrelated live configuration were preserved by semantic three-way merge.

## Integration corrections

Quoted on/off model values so Bukkit YAML cannot interpret them as booleans; added37-model blueprint/collision contract regression. Beam device effects obey the same WorldGuard pair boundary as other spells. A free-standing activated vine pot now grows vines as documented. Synced only the feature delta into the Bridge shipped compatibility routes. Updated catalog regressions and explicitly kept new acquisition/prices pending. New tests cover twelve costs/elements/routes and the105 retired IDs while retaining fishing/Arcana catalog entries. Test dependencies point to the deployed VFX API version.

## Verified delivery

Build-Server.ps1 -Verify and all affected builds passed. Tests: Gimmick16,MagicMythic69,Bridge405,Discovery35,UI428,VFX143,Kyudichi18. Deployed-VFX-API69-test rerun also passed.

School/wild/arena:615 total changed files,151 protected hashes;37 blueprints and153 VFX files per server. Five plugin versions and ports/Done logs verified. Velocity unchanged. Wild spawn gate JAR and biome compatibility hashes separately verified. Gimmick/player puzzle state unchanged.

Personal: UI plus12 VFX packs/153 files installed with hashes checked;14 other mods/options/4096 preload limits preserved. Temporary launcher v0.1.21 contains UI and expanded client asset bundle;83 other managed modules preserved. Nine release asset digests, latest.yml updater SHA512 and public main feed/asset URLs verified.

No backup,map copy,forced game close or gameplay capture. ItemsAdder registrations/materials/resource-pack generation and merge remain owner-managed; server ModelEngine blueprints were delivered. No NPC or placed puzzle instance was created. Real gameplay remains unverified: prism tint, conduit/mirror orientation, moving platform riding, barrier restart behaviour,new spell interactions and reconnect readiness/natural spawn require player QA. No player was online at deployment.

Release: https://github.com/zzunwoooo/chacademi-launcher/releases/tag/v0.1.21

Launcher commit: 64fe934aed92d3dd6fbd87fc805ae4d10e6cff97

UI SHA256: 6d8d29f1143a347c67047592cc0dcaa0dd2fa9aa5eb753916b050d11e51d960d

gimmick-paper SHA256: f45b41581c1c4ccb269d7f45766f0e6759d20e686874653efd0c545db9ab0645

magic-mythic-paper SHA256: f29f6606f48ec1642363f0f1e73c1ab5b35a5fce62e2a433e12afd7a6784fff6

magic-codex-paper SHA256: f34f9f16525f5aa6030aeaa0c541584c8e81eea060b18a1d2e6e75370db84b1b

magic-discovery-paper SHA256: 60878274ac8249425fb1591aa096283f74bebea58cc455451e92626b2b6a2ae3

portable-vfx-runtime/paper SHA256: cad00eed9977fa7b703a318716b374651f6dcb70e936ad993fe7528b700eeeda
