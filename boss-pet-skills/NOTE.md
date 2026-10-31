# feature/boss-pet-skills-20261031 — 보스펫 스킬 (바닐라 보스 4 · 모르바스 · 하베스트)

신기능 세션 작업. 서버·런처 반영은 규칙 세우기 세션. 바탕 = `origin/source`. **게임 안에서는 시험하지 않았다** (세 서버 jar 기준 컴파일만).

## 오너 결정 (2026-10-31)
- 스킬은 보스가 살아 있을 때 쓰던 것만, 새 스킬 없음. 바닐라 보스는 마인크래프트 원래 공격을 MythicMobs 기존 기능으로.
- 모르바스 망자 병사 = 주인에게 묶인 전투 하수인, 겹쳐 소환 안 됨, 최대 2마리.

| 펫 | 스킬 (전투 중 40초마다) | 만든 방법 |
|---|---|---|
| 엘더 가디언 | 레이저 빔 → 피해 4 + 둔화 3초 | `effect:guardianbeam` (펫 몸 → 상대) + chatelegraph |
| 엔더 드래곤 | 드래곤 화염구 → 숨결 웅덩이 (2 × 6번) | projectile + dragon_breath 파티클 + chatelegraph pulses |
| 위더 | 위더 해골 → 피해 4 + 회복봉인 3초 | projectile `bulletType=DISPLAY;material=WITHER_SKELETON_SKULL` + chatelegraph |
| 워든 | 소닉 붐 (1초 모으기) → 피해 4 + 실명 2초 | `effect:particleline{p=sonic_boom}` + 소리 + chatelegraph |
| 모르바스 | 셋 중 하나: 기존 가호(흡수 8칸+저항) · 영혼탄(피해 4 + 마나 연소) · **망자 병사 소환** | 영혼탄 = 보스 BOLT 그대로(펫 피해). 병사 = 서버 코드 |
| 하베스트 (새 펫) | 수확 베기 · 호박 폭탄 (각 피해 4) | 보스 p01·p04 그대로 (같은 VFX) |

피해는 다른 펫 스킬 최고치(4)에 맞춤. 모두 적에게만, 빨간 예고 없음.

## 서버 코드 (MagicCodexBridge) — 재시작 필요
- `server-jar-add/` 를 5대 `magic-codex-bridge-final-20261024-*.jar` 에 (`jar uf <jar> -C server-jar-add .`).
  - `PetCompanions`: 배포 jar 셋(학교·야생·아레나)이 서로 같고 소스 컴파일과 javap 완전 동일 확인 → 통째로 교체. (`PetBridge` 는 서버마다 달라서 안 건드림 — MCPets 는 새 클래스가 직접 리플렉션)
  - `BossPetSkills*`: 새 클래스. 세 jar 기준 컴파일 결과 바이트 동일.
  - 표시: `// [기능:boss-pet-skills 시작/끝]`.
- 하는 일
  - MCPets 보스펫을 꺼낸 사람도 전투 판정 → `pet-companions.yml mcpets-skills` 의 스킬을 펫 몸 위치에서 시전 (동행 펫과 같은 3성 주기·사거리·경기 규칙).
  - 스킬 이름 `summon:<몹>:<최대>` = 하수인 소환. 최대 수가 찼거나 그 몹이 서버에 없으면 같은 펫의 다른 스킬. 하수인은 주인이 싸우는 상대만 노림, 주인↔하수인은 서로 못 때림, 30초·전투 끝·펫 넣기·퇴장·32블록 이상 떨어지면 사라짐, 서버에 저장 안 됨.
  - 동행 펫에 skills 와 special(combat)이 같이 있으면 그중 하나를 고름 (전에는 skills 만 썼음 — 지금 둘 다 있는 펫은 모르바스뿐).

## 설정 파일
1. `all-servers/plugins/MythicMobs/Skills/chacademi_boss_pet_skills.yml` → 5대 → `/mm reload`.
2. `school/plugins/MythicMobs/Mobs/chacademi_boss_pet_mobs.yml` → **학교만** (모델이 학교에만 있음). 야생에서는 모르바스가 병사 대신 영혼탄.
3. `pet-companions-add.yml` 내용을 5대 `MagicCodexBridge/pet-companions.yml` 에 반영 (mcpets-skills 새 칸 · 모르바스 skills/info · 하베스트 펫) → `/펫 새로고침` (서버 jar 교체 뒤).
4. `school/plugins/MagicCodexBridge/taming.d/boss_pet_harvest.yml` → 학교 (하베스트 1% 교화, 모르바스와 같은 설정).
5. `all-servers/plugins/MCPets/Pets/ChAcademia{Taming,Shiny}/{elder_guardian,ender_dragon,wither,warden}.yml` → 5대 (펫 창 설명에 스킬 한 줄만 추가, 5대 원본이 모두 같음 확인).
6. 런처: `launcher/config/magiccodex/pets/models/ca_pumpkin_reaper.bbmodel` (학교 ModelEngine `pumpkin_reaper.bbmodel` 그대로) → 펫 모델 묶음(pets-models)에 추가해야 하베스트 펫이 보인다.

## 확인 못 한 것
- 게임 안 시험 전부 (빔·해골·소닉 붐 연출 위치, 병사 추적, 피해 수치).
- MythicMobs 옵션 이름은 배포 MythicMobs 5.9.0 jar 클래스에서 확인 (guardianbeam d/fo/syo/tyo, particleline db/fo, projectile bulletType/material/fo/hitTargetOnly). `/mm reload` 콘솔 경고 확인 필요.
- 병사가 잡은 몹은 플레이어가 잡은 게 아니라 전리품·쪽지가 안 나올 수 있음.
