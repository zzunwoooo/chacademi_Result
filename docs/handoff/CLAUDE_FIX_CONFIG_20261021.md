# 설정 묶음 수정 (config, Claude, 2026-10-21)

브랜치: `claude/fix-config-20261021` (기준 `0cd1454`)

**빌드 미검증: 문법 검사(`parsecheck.sh`)만 통과.** Gradle·실게임 확인은 하지 않았습니다. `ArtifactOwnershipStore.java`만 단독 `javac` 컴파일을 확인했습니다(외부 의존 없음).

참고: `/home/claude/audit/claude-reply-to-gpt-20261021.md`(C05·C08·C13·CropAwakening 부록 3), `02-magic-gameplay.md` 1번 (a)·C, `03-character.md` A-1·A-2, `07-npc-story.md` 7-5, `10-db-ops.md` 3-2b.

---

## 1. C05 운영 overlay 검사·재생성 도구 + overlay 재생성

### 고친 것
- 새 도구 `tools/operating_overlay.py` (python3 표준 라이브러리만. PyYAML 없이 이 저장소 설정 파일이 쓰는 YAML 문법만 읽는 작은 파서 포함. 대상 파일 전부를 PyYAML 결과와 대조해 같게 읽는 것을 확인함).
- 설정 `deployment/operating-config-overrides.json`
  - `files`: overlay ↔ 원본 대응 7개
    | overlay | 원본 |
    |---|---|
    | `MagicCodexBridge/mana-spells.yml` | `magic-codex-paper/src/main/resources/mana-spells.yml` |
    | `MagicCodexBridge/spell-runtime.yml` | `magic-mythic-paper/server-config/spell-runtime.yml` (`tools/spells.py` 생성물) |
    | `MagicCodexBridge/artifacts.yml` | `magic-codex-paper/src/main/resources/artifacts.yml` |
    | `MagicCodexBridge/climate.yml` | `magic-codex-paper/src/main/resources/climate.yml` |
    | `PortableVFX/spell-catalog.yml` | `portable-vfx-runtime/paper/src/main/resources/spell-catalog.yml` |
    | `PortableVFX/spell-bindings.yml` | `portable-vfx-runtime/paper/src/main/resources/spell-bindings.yml` |
    | `MythicMobs/Skills/chacademi_spells.yml` | `magic-mythic-paper/mythicmobs/Skills/chacademi_spells.yml` (`tools/spells.py` 생성물) |
  - `keep`: 의도된 운영 값 (재생성해도 overlay 값을 유지하고 목록을 출력)
    - `mana-spells` `vine_ladder.enabled`, `light_mark.enabled` (103·134 운영 전용 서버 이벤트 연동, `CODEX_OPERATING_ALCHEMY_CARRIER_20261016.md`)
    - `spell-catalog` `vine_ladder`, `light_mark` 항목 전체 (운영 phases·enabled·reviewed)
    - `spell-bindings` `vine_ladder`, `light_mark` (원본에 없는 운영 전용 바인딩)
    - `climate` `season-clock: realtime`, `real-time-season.epoch: 2026-10-16T15:33:16+09:00`
  - `pending`: **`chacademi_spells.yml`의 `CHA_falling_star`** — 메테오 수정(combat 묶음)이 이 줄을 맡으므로 이번에는 overlay의 옛 블록(`spell=` 없음)을 그대로 둠. 검사 때 "보류" 경고로만 나오고 실패로 세지 않음.
  - `donation`: 기증 목록이 마법 목록 전부를 담는지 확인 (`exclude` 비어 있음, 옛 기록용 `wind_vault`만 허용).
- 판단 기준: overlay와 원본이 다른 곳 중 위 `keep` 외에는 전부 **옛 값(원본이 더 새것)** 으로 판정함.
  - 168~214 아르카나 26종의 `enabled:false`·마나·쿨타임·이름 차이: overlay가 `cb84806`(168~214 구현) 이전 상태. 원본 값으로 교체.
  - 아이콘 6개(`earth_shaping` 등): 원본은 10-20 검수 아이콘(`reviewed_20261020/`), overlay는 옛 경로. 원본으로 교체(클라 jar에 그림 있음).
  - `rekindle`·`wick_trimming` `utility:true, range:24`: 10-20 기믹 개편 전 값. 원본(`utility:false`, `chagimmick`)으로 교체.
  - overlay에만 있던 마법 105종(버린 168~299, 전부 `enabled:false`)과 `spell-runtime`의 `feather_step`: 원본에서 삭제된 것이라 제거.
- 이 브랜치에서 실제로 재생성함 (`regenerate` → `check` 차이 0). 재생성 전 `check` 결과(차이 718건):
  - mana-spells: 누락 43(몹 24·아르카나 6·기믹 12·`fish_trap`), 꺼짐 26, 값 다름 17, 남는 항목 105
  - spell-runtime: 누락 69 (원본 219 / overlay 151), 값 다름 2
  - spell-catalog: 누락 42, 꺼짐 26, 값 다름 33, 남는 항목 105
  - spell-bindings: 누락 44
  - chacademi_spells: 누락 스킬 70, `element=`/`spell=` 누락 87개 스킬
  - 기증 목록: 누락 43
- `deployment/operating-config-manifest.json`의 sha256 7개 갱신.

### 쓰는 법 — **배포 전마다 반드시**
```
python3 tools/operating_overlay.py check        # 종료 코드 0 = 차이 없음, 1 = 차이 있음 (배포 중단)
python3 tools/operating_overlay.py check -v     # 항목 전부 보기
python3 tools/operating_overlay.py detect       # 다른 곳 전부 + [유지: 의도된 운영 값] 표시 (새 운영 값이 생겼는지 볼 때)
python3 tools/operating_overlay.py regenerate   # 원본으로 overlay 재생성 (keep·pending 유지) + manifest 갱신 + check
```
- 순서: 원본(또는 `tools/spells.py`·`gimmick_spells.py` 등 생성기) 수정 → 생성기 실행 → `regenerate` → `check` 0 → 커밋 → 서버에 overlay 7개 복사.
- 운영에서 일부러 원본과 다르게 둘 값이 생기면 `operating-config-overrides.json`의 `keep`에 경로·이유를 추가한 뒤 `regenerate`.
- 재생성은 **원본 파일 글자를 그대로 쓰고** keep/pending 블록만 overlay에서 옮겨 붙입니다. 결과를 다시 읽어 "원본 + 유지 값"과 의미가 같은지 검증하고, 다르면 파일을 쓰지 않고 멈춥니다.

### 배포 시 주의
- 이 overlay를 서버에 덮으면 168~214 아르카나 26종이 켜지고, 몹 습득 24·아르카나 6·기믹 12·`fish_trap` 경로가 생깁니다. MythicMobs `/mm reload` + `/서버관리 새로고침 마법`(또는 재시작) 필요.
- VFX 팩(기믹 12개 등)이 클라이언트에 없으면 카탈로그 마법은 "준비 안 됨"으로 거절됩니다(기존 동작).
- **combat 묶음 병합 시**: 그 묶음이 원본 `chacademi_spells.yml`(또는 `spells.py`)의 `CHA_falling_star`를 고친 뒤, `operating-config-overrides.json`의 `pending` 항목을 지우고 `regenerate` 하세요. overlay 파일 자체는 통째로 다시 만들어졌으므로, 그 묶음이 overlay 줄을 직접 고쳤다면 충돌 시 overlay는 이 브랜치 쪽을 택하고 `regenerate`로 다시 맞추면 됩니다.

---

## 2. 기증 목록 (donation-spells.yml) — 빠진 43종 추가 + 서버 파일 자동 병합

### 판단 (마법별)
43종 모두 플레이어가 `magic.learned.<id>` 권한으로 배우는 도감 마법이라 **전부 포함**. 몹 전용 스킬(`chacademi_mob_skills.yml`)은 도감·기증 목록에 원래 없음 → 제외할 "몹 전용"은 0개.

| 묶음 | 아이디 | 결정 |
|---|---|---|
| 몹 습득 300~323 (24) | fox_orbit, curving_plumes, crystal_orbit, venom_spit, smoke_veil, domain_stomp, seal_circle, seal_talismans, shield_slam, chain_arrow, mana_drain, soul_scythe, oblivion_orbs, bone_grasp, shadow_leap, binding_pull, fairy_dust, moon_wind, gale_wings, feather_storm, hunting_pounce, roar_leap, gravity_slam, tail_spikes | 포함 (몹 처치 발견으로 배움) |
| 아르카나 (6) | landing_airbrake, hushed_soles, thorn_manners, cinder_skipring, droplet_relay, folding_tideplane | 포함 |
| 324 (1) | fish_trap | 포함 |
| 던전 기믹 239~250 (12) | thawing_heat, steam_ignition, water_level, stone_weight, vine_awaken, light_beam, mirror_tune, prism_tint, statue_wake, rewind, conduit_turn, dimension_mark | 포함 (발견 연결은 아직 없음. 권한을 가진 사람만 기증 가능하므로 획득 경로를 만들지는 않음) |

- 점수는 모두 기존과 같은 **1점** (오너가 등급별 점수를 정하면 바꿈). 이름은 `mana-spells.yml` 이름 그대로.
- `fish_trap` 이름은 지금 `mana-spells.yml`과 같은 "통발"로 넣었습니다. **표시 문구 "통발→아공간 수조" 변경은 client 묶음 담당이라 건드리지 않았으니, 그 묶음이 `donation-spells.yml`의 이 이름도 같이 바꿔야 합니다.**
- 파일 머리 주석의 없어진 명령 `/기증기록 reload` → `/서버관리 새로고침 기증`.

### 서버 병합
- `SchoolBridge.loadCatalog()`가 읽기 전에 `mergeBundledCatalog()`를 부름: jar 안 기본 목록에서 **서버 파일에 없는 아이디만** 덧붙이고 저장. 이미 있는 항목(이름·점수·권한)은 건드리지 않음. 예전 300종 파일에 남은 옛 아이디도 지우지 않음.
- 켤 때와 `/서버관리 새로고침 기증` 때 모두 동작. 덧붙이면 콘솔에 "기증 마법 N개를 덧붙였습니다 [...]", 실패하면 경고 후 기존 파일 그대로 사용.

### 바뀐 파일
`magic-codex-paper/src/main/resources/donation-spells.yml`, `SchoolBridge.java`, 테스트 `DonationCatalogTest`(빠짐 강제 → 전부 포함 강제), `GimmickCatalogIntegrationTest`(기믹이 기증 목록에 없어야 한다 → 있어야 한다, 발견 미연결 검사는 유지)

---

## 3. 첫 기증 전체 공지 (7-5 #3)

- 기증 성공(= 그 마법의 첫 기증자, DB 기본키로 1명만 성공) 시 접속자 전원에게 알림 카드:
  `○○님이 「마법명」을 학교에 처음 기증했습니다! (루미나 +1)` — 닉네임은 기존 `DisplayNames`(한글 닉네임), 조사 을/를은 받침으로 고름(한글이 아니면 "을(를)").
- 기존 알림 기능 `CodexCommands.notify(…, "system", "school-donation-<id>", …)` 사용. 액션바 없음.
- 기증 INSERT + 기숙사 점수는 그대로 `SchoolStore.donate` 한 트랜잭션. 공지는 **커밋 뒤 메인 스레드**(work 완료 콜백)에서만.
- "네트워크 전체": 공유(MariaDB) 모드면 다른 서버도 이미 있는 2초 갱신(`refreshShared`)으로 새 기록을 받고, 이전 스냅샷에 없던 최근 2분 이내 기록이면 같은 카드를 띄움(추가 조회·매 틱 작업 없음). 같은 기록(마법+시각)은 서버마다 한 번만.
  - **주의:** `10-db-ops.md`대로 지금 운영은 학교 DB가 서버별 SQLite라(일반 `database.properties` 없음), 공유 DB로 바뀌기 전까지는 기증한 서버의 접속자에게만 뜹니다. 기증 기록 자체도 서버별입니다.
- 테스트: `SchoolTest.donationAnnouncementUsesCorrectObjectParticle`.
- 실게임: 소속 있는 유저가 배운 마법 기증 → 같은 서버 다른 유저 화면에 카드, 두 번째 기증자는 "누군가 이미 기증한 마법입니다."만 뜨고 카드 없음.

---

## 4. C08 아티팩트: 효과 코드 없는 29개 끄기, 2개 켜기

- `artifacts.yml` 150개 아이디를 `ArtifactEffects/Actions/Life/Bridge…` 소스의 `"아이디"` 문자열과 대조 → 35개 미등장, 그중 `element_ring_*` 6개는 `"element_ring_" + element`로 처리되므로 제외 → **29개** (03-character.md 표와 같음).
- `enabled: false` (도감·뽑기 풀에서 빠짐, `ArtifactBridge`가 enabled=false 항목을 카탈로그에 넣지 않음):
  - 일반 15: broom_cord(빗자루 끈), squeaky_shoes(삑삑이 신발), dancing_ribbon(춤추는 리본), waving_glove(인사 장갑), sleepy_pillow(졸린 베개), shy_mask(수줍은 가면), discovery_journal(반응 일지), thermos_lunchbox(보온 도시락), laughing_mask(웃음 가면), turtle_shell_backpack(거북이 등껍질 배낭), stretch_headband(기지개 밴드), jack_in_the_box(깜짝 상자), yum_bib(냠냠 턱받이), broom_cushion(빗자루 쿠션), folding_spyglass(접이식 망원경)
  - 고급 4: tap_dance_shoes(탭댄스 구두), chatty_parrot(수다 앵무새), pinwheel_charm(바람개비 장식), shapeshift_mask(변신 가면)
  - 유일 1: sequence_necklace(순서의 목걸이)
  - 전설 9: fire_spirit_lantern(화염 정령의 등불), mirror_twin(거울 쌍둥이), rainbow_prism(무지개 프리즘), chaos_dice(혼돈의 주사위), the_only_tome(단 하나의 서), inverted_mana_flask(거꾸로 된 마나병), shadow_lord_mask(그림자 군주의 가면), star_echo(별의 잔향), storm_wings(폭풍 날개)
- 켬(`enabled: false` 줄 삭제): bug_repellent_incense(벌레 퇴치 향, `ArtifactEffects` 독·화상 -30%), comeback_medal(역전의 메달, 혼자 남으면 10초 피해 2배)
- 보이는 항목 148 → 121. overlay(`deployment/operating-config/MagicCodexBridge/artifacts.yml`)도 재생성으로 같음.
- 배포 주의: 이미 이 29개를 뽑은 유저의 **DB 보유 기록은 그대로**지만 도감에서 안 보이고 장착 목록에서 빠짐(원래 효과 없음). 구현 후 해당 줄을 지우면 다시 보입니다. 서버 `plugins/MagicCodexBridge/artifacts.yml`을 overlay로 교체 후 `/서버관리 새로고침 아티팩트`.
- 테스트: `ArtifactCatalogTest`(148 → 121, 두 개 켜짐, 29개 꺼짐 확인).

---

## 5. 살아나는 작물: 수확물 손실 없앰 (CropAwakening)

- 기본값 `cancel-drops: false` (코드 기본값 + `creature-spawns-paper/src/main/resources/config.yml`). 원래 드롭은 그대로 떨어지고 몹은 보너스. 작물 몹 보상 표(`Drops: []`)는 그대로 비워 둠.
- `cancel-drops: true`로 켜도 잃지 않음:
  1. `BlockBreakEvent`에서 `setDropItems(false)`를 더 이상 쓰지 않음. 깨어남이 정해진 블록만 기록.
  2. 같은 틱 `BlockDropItemEvent`(HIGHEST)에서 최종 드롭(다른 플러그인이 더한 것 포함)을 사본으로 옮기고 목록을 비움.
  3. 다음 틱: 수확자 오프라인·청크 언로드·소환 실패(개체 제한, 미몹 없음, spawn null)면 그 자리에 바로 돌려줌.
  4. 소환 성공이면 몹이 사본을 맡음(메모리 맵). **처음 일어난 한 번에만** 돌려줌: 사망(MONITOR, MythicMobs `PreventOtherDrops`와 무관하게 직접 떨어뜨림) / 수명 끝(연기 + 그 자리) / 청크 언로드(수확자 인벤토리, 넘치면 발밑, 접속 중이 아니면 다음 틱 원래 자리) / 수확자 접속 종료(그 자리에 돌려주고 몹 제거) / 플러그인 종료(그 자리).
  5. 서버 비정상 종료 30초 창은 비영속 몹이라 예외(작물 1칸, 설계상 허용).
- 운영 설정: 야생 서버 `plugins/ChAcademiaWildlife/config.yml`에 `crop-awakening` 섹션이 없으면(10-20 인계 기준) jar 기본값 false가 적용됩니다. 섹션을 붙여 넣어 `cancel-drops: true`가 있으면 `false`로 바꾸세요(true여도 이제 손실은 없음). `deployment/operating-config`에는 야생 설정 overlay가 없어 고칠 파일이 없습니다.
- 바뀐 파일: `CropAwakening.java`, `WildlifePlugin.java`(onDisable에서 `crops.close()`), `config.yml`, `CropAwakeningTest.java`
- 테스트: 기존 테스트는 "깨어남 = isDropItems false"로 판정하던 것을 예약 작업 수로 바꿈. 추가: 기본값이면 드롭 유지 / cancel 모드 오프라인이면 1회 반환 / 소환 실패면 1회 반환 / 사망 시 1회 + 수명 끝 추가 0.
- 실게임: `chance: 1.0`으로 잠깐 바꾸고 밀 수확 → (기본) 밀·씨앗이 떨어지고 몹도 나옴. `cancel-drops: true` → 드롭 없음, 몹 처치 시 원래 밀·씨앗, 30초 방치 시 연기와 함께 그 자리에 드롭, 수확 직후 접속 종료 시 그 자리에 드롭.

---

## 6. C13 Polymart 토큰

- `reference/output/mob-pet-asset-review-v1/config-texts.json`의 `polymart.yml` 텍스트에서 `token:`과 `nonce:` 값을 `<REDACTED-2026-10-21>`로 바꿈(JSON은 그대로 유효). 저장소 전체에서 그 토큰 문자열이 다른 파일에 없음을 확인(값은 출력하지 않음). `detail.json`에는 키 이름(`polymart`, `info`)만 있고 값은 없어 그대로 둠.
- `reference/source-manifest.json`의 해당 파일 sha256은 원본 자료의 값이라 그대로 둠(이제 저장소 파일과 다름).
- **git 이력에는 토큰이 남아 있습니다.** 해당 Polymart 계정(HSCrafting 다운로드 토큰)의 토큰은 반드시 재발급(폐기)해야 하고, 이력 정리(filter-repo 등)는 오너 판단으로 별도 진행해야 합니다.

---

## 7. 아티팩트 보유 갱신: 접속자마다 새 연결 → 주기당 연결 1개

- `ArtifactOwnershipStore.poll(players)`: 연결 하나로 `SELECT player,artifact … WHERE owned=TRUE AND player IN (?,…)`(200명 단위) + 미지급 우편 조회. 행이 없는 접속자는 빈 목록(기존 `owned()`와 같은 결과).
- `migrationComplete()`: 두 표식이 한 번 확인되면 캐시(표식은 지우지 않는 값). 그래서 정상 상태에서 2초 주기당 연결 1개(구매 대기가 있으면 구매마다 기존처럼 트랜잭션 연결).
  - 동작 차이: 운영자가 DB에서 표식 행을 지우면 재시작 전까지는 감지하지 않음.
- `ArtifactOwnershipService.tick()`은 `store.poll(players)` 결과로 같은 스냅샷·우편 처리.
- 테스트: `ArtifactOwnershipStoreTest.pollReadsAllOnlinePlayersWithOneConnection`(연결 수 1, 결과가 `owned()`·`pendingDeliveries()`와 같음).

---

## GPT가 돌릴 것
1. `python3 tools/operating_overlay.py check` → "결과: 차이 없음", 종료 코드 0 (보류 1건 경고는 정상).
2. `./gradlew :magic-codex-paper:test --tests '*DonationCatalogTest' --tests '*GimmickCatalogIntegrationTest' --tests '*ArtifactCatalogTest' --tests '*ArtifactOwnershipStoreTest' --tests '*SchoolTest' --tests '*MobSpellDeploymentConfigTest'`
3. `./gradlew :creature-spawns-paper:test --tests '*CropAwakeningTest' --tests '*CropInheritedDefaultsTest'`
4. 전체 `Build-Server.ps1 -Verify`

## 컴파일 위험 (확신 낮은 곳)
- `CropAwakeningTest`: `MythicMob.spawn(AbstractLocation, double, SpawnReason)` 시그니처를 `nullable(AbstractLocation.class), anyDouble(), any(SpawnReason.class)`로 stub. 레벨 인자가 `int`면 `anyInt()`로 바꿔야 함. `mockStatic(BukkitAdapter.class)` 사용.
- `SchoolBridge.mergeBundledCatalog`: `JavaPlugin.getResource`, `YamlConfiguration.loadConfiguration(Reader)`, `ConfigurationSection.createSection(String, Map)` — 표준 Bukkit API.
- `CropAwakening`: `BlockDropItemEvent.getItems()`(다른 모듈에서 사용 중), `EntitiesUnloadEvent`(다른 모듈에서 사용 중), `World.getChunkAt(Location)`, `PlayerInventory.addItem(ItemStack...)`.

## 못 한 것·보류
- `CHA_falling_star` `spell=`: combat 묶음 담당이라 overlay에서 보류(위 1번).
- 기증 점수 등급화: 오너 결정 전이라 전부 1점.
- 기증 공지의 서버 간 전달은 학교 DB 공유가 전제(지금 운영은 서버별 SQLite).
- git 이력의 토큰 제거: 이 브랜치에서 하지 않음(이력 재작성은 오너 판단).
- `deployment/mythicmobs/Skills/chacademi_player_additions_20261020.yml` 등 overlay 목록 밖 파일은 검사 대상이 아님. 필요하면 `files`에 추가.
