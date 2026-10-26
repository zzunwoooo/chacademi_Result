# 야생 몹 자체 스폰 루프 (Claude, 2026-10-23)

브랜치 `claude/wildlife-spawner-20261023` (기준: `codex/story-nameplates-bundle-20261022-task7`) · 모듈 `creature-spawns-paper` · 버전 `0.5.0-spawner-20261023`

## 변경
- 새 `WildSpawner.java`: MythicMobs RandomSpawns 대신 플러그인이 직접 첫 스폰. 미몹 몹(`CA_Wild_<id>`/`_shiny`)을 `MythicMob.spawn(..., SpawnReason.NATURAL)`로 소환 → 기존 `spawn()` 이벤트 재검사·자연 표시·`group()`·`cleanup()`을 그대로 탐.
- `Habitat`: `rarity`(1/2/3/boss), `weight`(선택, 0이면 등급 가중치) 추가. 운영 habitats.yml에 rarity가 없으면 JAR 안 기본 habitats.yml 값 사용.
- `habitats.yml`: 138종에 `rarity` 줄 추가 (1성 33 / 2성 45 / 3성 59 / boss 1 = glacialcore, 예전 spawn-plan.json 기준).
- `config.yml`: `chunk-cap` 6→4, `spawner` 블록 추가(enabled, period-seconds 5, attempts-per-player 3, max-attempts-per-tick 40, rarity-weights 4.5/1.8/0.4/0.06). 블록이 없어도 같은 기본값으로 동작.
- `WildlifePlugin`: 루프 시작/정지/리로드 연결, 루프용 헬퍼(timeAndSeasonOk·chunkFull 등), 상태·진단에 루프 통계.
- `deployment/`: RandomSpawns 패치 스크립트 2개 삭제(더 이상 해당 없음), README를 새 배포 절차로 교체.

## 빌드 시 주의 (Claude 환경에서 Paper/Mythic 저장소 접근 불가 → 컴파일 못 함, 구문만 확인)
- 새로 쓴 API: `World.getHighestBlockYAt(int,int,HeightMap)`, `HeightMap.MOTION_BLOCKING_NO_LEAVES`, `Block.getLightFromSky()`, `YamlConfiguration.loadConfiguration(Reader)`, `JavaPlugin.getResource`, `ActiveMob.getEntity().getBukkitEntity()`. 컴파일 오류가 나면 이 부분부터.
- 기존 테스트 `BiomeCompatibilityTest`는 `Habitat.parse(id,section,warnings)` 3인자 오버로드를 그대로 씀(유지함).
- `HabitatCondition`/`MainThreadSpawnGate`(cahabitat)는 남겨 둠. RandomSpawns 파일이 실수로 남아 있으면 상태 명령에 "(구) 미몹 랜덤스폰 조건 호출" 줄이 뜸.

## 배포
`creature-spawns-paper/deployment/README.txt` 참고. 야생 서버만. 핵심: 새 JAR, RandomSpawns `chacademia_wildlife.yml` 제거, 운영 config.yml `chunk-cap` 4로.

## 2차 (부하 최적화)
- `WildlifePlugin.check()` 순서 변경: 보호 구역 → 청크 개체 수 → 플레이어 거리·동종·지역 개체 수(캐시)를 **블록 검사보다 먼저**. 주변이 꽉 찬 평상시엔 블록을 읽기 전에 실패. 결과 문자열은 같고, 실패 이유가 겹칠 때 어떤 이유로 집계되는지만 바뀜.
- 자체 스폰 루프는 시도 앞부분에서 청크를 이미 세므로 `checkChunkCounted()`로 청크 재계산을 건너뜀(한 시도에 한 번만 셈). 스폰 이벤트 재검사·무리 추가는 기존처럼 청크를 셈.

## 3차 (확률 확인 명령)
- `/월드관리 야생몹 확률 [닉네임]`: 서 있는 자리(바이옴, 물속/지상) 기준으로 스폰 시도가 걸렸을 때 각 종이 뽑힐 % (등급 가중치 비율), 각 종이 이 자리에서 지형 조건상 가능한지, 지금 안 나오는 종과 이유(높이/시간·계절·날씨), 청크·지역 개체 수를 보여 줌. 읽기 전용.
- `Habitat`에 `name` 필드 추가(habitats.yml `name`, 없으면 id).

## 4차 (빈 바이옴 + 크리에이티브)
- 서식지가 하나도 없던 Terralith 바이옴 34개(yellowstone, highlands, shrubland, yosemite_*, painted_mountains, cave/ice_caves 등, Terralith 2.5 바이옴 목록 기준) + 지상 몹이 없던 desert_oasis를 `biome-compatibility.yml`의 새 `terralith-biomes` 섹션으로 비슷한 바이옴 서식지에 연결. 원래 서식지는 그대로 정확히 일치, 연결은 명시적 목록만(와일드카드 없음). 이제 Terralith 바이옴 99개 모두 지상(동굴 포함) 몹이 1종 이상.
- 운영 서버의 `plugins/ChAcademiaWildlife/biome-compatibility.yml`은 파일이 있으면 자동 갱신 안 됨 → **레포 최신 파일로 교체 필수**. 없으면 콘솔에 경고.
- 스폰 대상 플레이어: 생존·모험 → 관전 제외 전부(크리에이티브 포함). 루프 대상과 check()의 플레이어 거리 조건 둘 다. (살아나는 작물은 그대로 생존·모험만)
- 테스트 `terralithBiomesWithoutOwnHabitatReuseSimilarHabitats` 추가.

## 5차 (오너 피드백 2026-10-23 오전)
Wildlife (`creature-spawns-paper`):
- 인접 청크 비슷한 몹 억제: 후보 지점 수평 24칸(≈인접 청크) 안에 같은 **묶음(family)** 몹이 있으면 그 종 가중치 ×0.2 (`spawner.similar-nearby-weight/radius`). 묶음 = id 첫 단어(golem_*, dragon_*, bear·bear_black·baby_polar_bear …), 요정 4종 = fairy, mf_warden_*, ncr_*. habitats.yml `family:`로 직접 지정 가능. 개체 수 캐시(PopulationCache) 재사용 → 추가 엔티티 스캔 없음.
- 몹 수 감소: `area-cap` 24 → 12.
- 3성·보스 시도당 확률 낮춤: 종을 뽑은 뒤 등급 통과 판정 `spawner.rarity-attempt-chance` 1성 1.0 / 2성 0.5 / 3성 0.08 / 보스 0.01. 3성만 사는 바이옴(드래곤 등)에서도 드물게 나옴. `/월드관리 야생몹 확률`에 "시도당 %" 함께 표시.
- 변색종 2배: `shiny-denominator` 4096 → 2048 (살아나는 작물은 그대로 4096).
- 허스크 허기 막기: 새 `NoHuskHunger` 리스너 — `chacademia_custom` 태그 허스크가 때린 그 틱의 허기(원인 ATTACK)만 취소.
- 3성 몹 크기 1.35배: 새 `MobScale` — ModelEngine `ActiveModel.setScale/setHitboxScale`. 자연 스폰 2틱 뒤 + 10초 정리 루프에서 크기가 다르면 다시 적용(재로드 복구). `rarity-model-scale`로 조절. ModelEngine 없으면 조용히 꺼짐. **build.gradle에 ModelEngine-4.0.8.jar compileOnly 추가, plugin.yml softdepend ModelEngine.**
- 운영 config.yml은 자동 갱신 안 됨 → 레포 config.yml로 교체하고 worlds·excluded-areas만 운영 값 유지.

magic-mythic-paper:
- `TelegraphMech.hit`: 판정 여유(대상 몸 폭/2) 제거 → 발 위치가 **그려진 예고 모양 안**일 때만 맞음(몹 스킬·플레이어 chatelegraph 마법 공통).
- `chacademi_mob_skills.yml`: 예고 없는 기본기(warn=false) 110줄 범위 ×1.5 (원·부채꼴 r, 직선 length). vfxref는 그대로라 VFX도 같이 1.5배로 커짐. (자동 생성 원본 ms/skills.py는 레포에 없음 → 다시 생성하면 이 값이 덮일 수 있음)

reference/output/wildlife-ground-v3/chacademia_custom_wild.yml (야생 몹 Mobs):
- `MovementSpeed` 전부 ×1.35 (0.24 → 0.324, 0.288 → 0.389). 운영 `MythicMobs/Mobs` 야생 몹 파일에 병합 필요.

## 6차 (사거리·추적·원거리 스킬·비전투 회복)
Wildlife: 새 `WildCombat` 리스너 + `ranged-skills.yml`(새 리소스) + config `combat` 블록
- 플레이어가 CA_Wild 몹을 때리면(직접·투사체, 마법 피해도 EntityDamageByEntityEvent로 오면 포함) 그 플레이어를 대상으로 잡고 FOLLOW_RANGE를 거리+16(최대 96)까지 올림 → 멀리서 때려도 쫓아옴. 관전·크리에이티브 공격자는 제외(바닐라 AI가 크리에이티브를 대상으로 못 잡음).
- 8칸 넘게 떨어져 맞으면 `ranged-skills.yml` 순서(투사체 먼저, 사거리 긴 순)로 `MythicBukkit.getAPIHelper().castSkill(mob, skill)` 1개, 몹당 4초 쿨. 스킬 자체 Conditions(targetwithin)는 그대로 적용.
- 비전투(10초 동안 주고받은 피해 없음) 몹은 2초마다 최대 체력 5% 회복. AI 꺼짐·무적 개체(교화 봉인 등)는 제외.
- 상태 명령에 "전투 보정" 한 줄.

magic-mythic-paper `chacademi_mob_skills.yml`:
- 모든 CHA_MOB 스킬 `targetwithin{d}` ×1.3 (최대 32) — 몹이 더 먼 거리에서 스킬을 씀.
- `chamobshot range` ×1.3 (최대 40), 예고 있는 직선기(조준 사격·돌진 등) `length` ×1.3.
- (위 5차의 기본기 ×1.5와 별개로 누적)

reference/output/wildlife-ground-v3/chacademia_custom_wild.yml:
- `FollowRange` 20 → 32 (인식 범위).
