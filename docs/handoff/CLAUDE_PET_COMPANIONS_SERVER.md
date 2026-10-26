# 동행 펫 서버 쪽 (메모 #81, 오너 2026-10-26)

pet-companions.yml 에 있는 펫(ca_* 276종)은 MCPets 몹을 소환하지 않는다. 서버는 "누가 어떤 펫을 꺼냈는지"만 기억하고
같은 세계 사람들에게 `magiccodex:pet_companions` (PetCompanionProtocol) 로 알린다. 클라가 그린다 (서버 엔티티 0).
이 파일에 없는 펫(바닐라)은 예전 MCPets 소환 그대로.

## 바꾼 파일
- `magic-codex-paper/.../PetCompanions.java` (새로 만듦): 꺼내기·넣기, 장착 효과, 전투 중 자동 발동, 경기 확인, 전송.
- `magic-codex-paper/.../CompanionMath.java` (새로 만듦) + `src/test/.../CompanionMathTest.java`: 순수 계산 (토큰, 전투 시간, 흡수, 단계).
- `PetBridge.java`: 생성자 `(plugin, ManaService)`. **동행 펫은 MCPets 에 전혀 의존하지 않는다** (오너: 동행 펫 MCPets 파일 삭제 예정).
  동행 펫의 목록·이름·별·아이콘·info·보유 권한은 pet-companions.yml (`permission`, `mythic`, `icon: {material, name, lore}`).
  펫 창 SUMMON/DISMISS 가 동행 펫이면 MCPets 연결 확인 전에 우리 꺼내기/넣기 (MCPets spawn 호출 없음). MCPets 가 없어도 펫 창은 동행 펫만으로 동작.
  `capturePermission`/`capturable`(교화), `ownable()`(펫 보기·주기·회수 명령), `owned()`(변신 가면, mythic 이름)도 동행 펫은 yml 기준.
  바닐라 펫만 MCPets 에서 읽고, MCPets 목록에 같은 ID가 있어도 동행 펫이면 무시. 동행 펫을 꺼내면 MCPets 펫은 들여보내고, MCPets 펫을 소환하면 동행 펫을 넣는다.
  `/서버관리 새로고침 펫` 에 pet-companions.yml 다시 읽기, `/서버관리 상태` 에 "동행 펫" 줄 추가.
- `MagicCodexBridge.java`: artifact_runtime 앞에 `cpet=<펫ID>;` 토큰 붙이기/떼기 (새 DB 컬럼 없음), 지능 배율 훅(equipmentPower), 낚시 배율 `petFishing`, 계절 면역 연결.
- `ClimateService.java`: `immune` 판정이면 계절·온도의 이동속도·마나 회복 배율·더위 허기 모두 1. `powerMultiplier(Entity caster, String element)` 추가.
- `FishingBridge.java`: 희귀 등급 `bonus` 에 펫 fishing 배율 곱하기.
- `magic-mythic-paper/mythicmobs/Skills/chacademi_pet_skills.yml`, `deployment/operating-config/MythicMobs/Skills/chacademi_pet_skills.yml`,
  `magic-mythic-paper/tools/pet_companions.py`: 스킬 줄 targeter `@target` → `@trigger`.
  이유: 시전자가 플레이어이면 MythicMobs `@target` 은 플레이어가 바라보는 엔티티라서 싸우는 상대에게 안 날아간다. 서버가 상대를 trigger 로 넘긴다.

## 동작
- 한 번에 한 마리. 꺼낸 상태는 학교·야생 이동·재접속에도 유지 (codex_player_state.artifact_runtime 앞 토큰). 도착 때 yml 보유 권한을 다시 확인하고, 없으면 넣는다.
- 장착 효과 (`PlayerStats.register("pets")`, 서버 이동 때 다시 적용):
  최대 체력 1성 +3 · 2성 +6 · 3성 +10. 2·3성 passive: speed(MOVEMENT_SPEED ×) knockback(KNOCKBACK_RESISTANCE) fall(FALL_DAMAGE_MULTIPLIER)
  max_mana / mana_regen(ManaService "magiccodex:pet") haste·jump·dolphin·night_vision·water_breathing·fire_resist(무한 포션, 파티클 없음·ambient)
  fishing(낚시 희귀 배율) climate_immune(계절·온도 배율 전부 1). 넣거나 바꾸면 전부 지운다.
- 전투 중 자동 발동: 2성 60초 · 3성 40초. 전투 중 = 마지막으로 때리거나 맞은 뒤 8초. 상대는 그 마지막 상대 (살아 있고 같은 세계, 24블록 안).
  skills 펫은 CHA_PET_* 하나를 무작위로 (시전자 = 주인, trigger·대상 = 상대). combat 펫은 effects 를 seconds 동안.
  발동 때 그 세계에 FLAG_CAST 를 켠 목록을 한 번 보낸다.
- 경기: 큐디치·결투 중에는 꺼낼 수 없음("경기 중에는 펫을 꺼낼 수 없어요"). 꺼내 둔 펫은 경기가 시작되면 1초 안에 넣는다.
  다른 경기(ChacaKyudichi `generalPresetLocked` 가 참인데 종류가 blocked 가 아닌 경우)는 체력만 남기고 능력·발동을 끈다.
  경기 종류는 MagicMythic 의 `MagicMatchEvent` 를 리플렉션으로 만들어 던져서 묻는다 (QuidditchCombat 과 같은 질문).
- 전송: 꺼내기·넣기·접속(채널 등록 때 포함)·퇴장·세계 이동·리스폰·발동 때 그 세계 전원에게. `getListeningPluginChannels` 확인 후 보낸다.
- 부담: 매 틱 하는 일 없음. 1초 작업은 펫을 꺼낸 사람만 본다. 피해 이벤트는 펫을 꺼낸 사람이 없으면 바로 끝난다.

## 배포 주의
- MythicMobs `Skills/chacademi_pet_skills.yml` 을 같이 배포하고 `/mm reload` (스킬 줄이 `@trigger` 로 바뀜). 없으면 스킬 펫은 발동해도 아무 일 없음 (콘솔 경고 한 번).
- `plugins/MagicCodexBridge/pet-companions.yml` 은 없을 때만 jar 에서 저장된다. 서버에 예전 파일이 있으면 새 것으로 바꿔 넣고 `/서버관리 새로고침 펫`.
- pet-companions.yml 의 각 펫에 `permission`(없으면 `chacademia.pet.<id>`), `mythic`, `icon` 이 있다고 가정했다 (생성기 재실행분). icon 이 없으면 펫 창 아이콘이 빈 칸.
  permission 형식(`[a-z0-9_.-]`)이 틀리면 설정 읽기 전체가 실패한다 (새로고침이면 기존 설정 유지).
- 보유 권한 노드는 MCPets 때와 같아야 기존 보유가 이어진다 (생성기에서 맞출 것).
- 클라(magic-codex-fabric)와 VFX 팩은 별도 작업. 클라가 채널을 안 들으면 서버는 보내지 않는다 (효과는 그대로).

## 게임에서 확인
1. 2성 펫(ca_elk 고산 엘크) 보유 → 펫 창에서 소환: "고산 엘크 펫을 꺼냈어요." · 최대 체력 +6 · 이동속도 +10% · 펫 창 active 표시 · 정보 줄 보임.
2. 다른 사람 화면에 내 펫 (클라 작업 후). 학교↔야생 이동 후에도 꺼낸 상태와 체력 유지.
3. 3성 스킬 펫으로 야생 몹을 때리기 → 바로 1회 발동, 그 뒤 40초마다 (계속 싸울 때만). 8초 동안 안 싸우면 발동 안 함.
4. ca_bear(흡수 4칸) 전투 → 노란 하트 4칸. 해제 → 다음 발동 주기 전에 흡수 상한이 내려감.
5. 큐디치/결투 입장 → 펫이 넣어지고 다시 꺼내기 거부.
6. 펫 해제 → 체력·속도·포션(무한, 파티클 없음)·마나 수정자가 모두 사라짐. 우유를 마셔도 1초 안에 passive 포션이 다시 걸림.

## 추정한 것 / 남은 것
- "꺼낸 직후엔 쿨이 가득 찬 상태" = 바로 발동 가능으로 읽었다. 넣었다 다시 꺼내 쿨을 건너뛰지 못하게 마지막 발동 시각을 기억한다 (퇴장·서버 이동 때는 잊음).
- 즉시형 absorb(seconds 없음)는 흡수 상한(MAX_ABSORPTION)을 다음 발동 주기 동안 올려 둔다. heal 은 체력(반 하트 단위) 값으로 봤다.
- haste 는 바닐라 성급함 포션으로 봤다 (마법 가속 아님).
- 원소 위력 배율: 실제로 쓰는 곳은 tornado-event-paper `SeasonalSpellMechanics` (`climate.powerMultiplier(element)`). 이 작업 범위 밖이라 안 고쳤다.
  거기서 `climate.powerMultiplier(source, element)` 로 바꾸면 climate_immune 이 원소 위력에도 걸린다.
- MCPets 자체 메뉴(/pets)로 ca_* 펫을 몹으로 소환하는 것은 막지 않았다 (펫 창 경로만 바꿈).
