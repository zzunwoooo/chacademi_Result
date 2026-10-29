# 무덤의 대현자 모르바스 (리치 보스) + 망자 병사 — 등록본

오너가 준 보스 세트(모델·애니·VFX·스킬 초안, 2026-10-29)를 게임에 넣을 수 있게 확정한 것. 리치의 지하 연구실(ChacaDungeon) 보스 단계에서 쓴다.
신기능 세션 작업 — **서버·런처 반영은 안 했다** (규칙 세우기 세션, 정기 점검).

## 들어 있는 것

| 폴더 | 내용 | 넣는 곳 |
|---|---|---|
| `modelengine/` | `ca_archlich`(1페이즈), `ca_archlich_p2`(2페이즈), `ca_archlich_thrall`(병사) — ModelEngine 4 Generic | 학교 `plugins/ModelEngine/blueprints/` → `/meg reload` → 리소스팩 병합(`/iazip`) → 런처 통합 팩 |
| `mythicmobs/Mobs/chacademia_archlich.yml` | `CA_Boss_Archlich`, `CA_Mob_ArchlichThrall` | 학교 `plugins/MythicMobs/Mobs/` |
| `mythicmobs/Skills/chacademi_archlich_skills.yml` | 스킬 28개 | 학교 `plugins/MythicMobs/Skills/` → `/mm reload` |
| `vfx/ArchlichMorvath/` | PortableVFX 팩 (vfx.json 1.21.0 + 텍스처 19장, 2.1MB) | 런처 `config/portablevfx/effects/ArchlichMorvath/` (distribution.json File 줄) + 학교 `plugins/PortableVFX/effects/ArchlichMorvath/` |
| `source-models/` | 원본 Modded Entity bbmodel + 히트박스 (참고용, 게임에는 안 씀) | — |
| `ORIGINAL_README.md` | 오너가 준 원래 설명 (스킬 표·타이밍) | — |

## 초안에서 바꾼 것 (★ 확인 필요 항목 확정)

1. **피해**: 초안은 `chatelegraph{…;flat=true}` 였는데 `flat` 이 피해량이라 0 피해였다 → 제안 수치로 넣음.
   소환 10(+넉백) · 저주 12 · 폭풍 4×3 + 폭발 12(+넉백) · 손아귀 균열 14 → 낫 12 · 영혼탄 8/10 · 병사 베기 5. (기존 몹 스킬 2~13 범위)
2. **직선·부채꼴 이름**: `w/l` → `width/length`, 부채꼴 `angle` 그대로 (TelegraphMech 실제 이름).
   손아귀 균열 VFX 는 `vfxref=14` (길이 14 = 만든 크기 1배; 초안 `vfxref=1` 이면 4배로 커짐).
3. **상태이상 (16종)**: 영혼탄 `mana_burn` 2초 · 저주 장판 `recovery_seal` 3초 · 손아귀 균열 `root` 1.5초 · 폭풍 폭발 `fear` 1.5초.
4. **chavfx** (예고 없이 VFX 만): 새 MythicMobs 메커닉. MagicMythic 배포 jar 가 소스와 달라 건드리지 않고 **ChacaDungeon 플러그인이 등록**한다
   (`chaca-dungeon-paper/.../VfxMechanic.java`, PortableVFX API 는 MagicMythic ReactionVfx 와 같은 방식).
5. **팩에 크기 모듈이 없음** (`"scale": null`) → VFX 는 만든 크기로만 재생. 페이즈 전환 폭발은 축소판 대신 원래 크기 폭풍(r8).
6. **보스바 제목·색 바꾸기**(`setbossbartitle/color`)는 MythicMobs 에 없는 메커닉이라 뺐다. (원하면 barCreate 로 따로 만들어야 함)
7. **2페이즈 병사 색**: 초안은 world 변수(`world.archlich_phase`) → 같은 월드에 던전 칸이 3개라 다른 칸과 섞임 → 병사 자신의 변수 `caster.p2`.
8. **병사 베기**: `damage @EntitiesInCone` (보스·다른 병사도 맞음) → 예고 없는 `chatelegraph{shape=cone;warn=false}` (플레이어만).
9. **2페이즈 조건**: `health{h=<50%}` → `health{h=<900}` (Health 1800 의 절반, 절대값). Health 를 바꾸면 이것도 바꿀 것.
10. `lookat{}` → `look{}` (같은 메커닉의 기본 이름).

영혼탄 투사체 VFX(`bolt`, 움직이는 효과)는 아직 서버 연결이 없어 초안대로 바닐라 파티클 + 명중 VFX(`bolt_hit`).

## 반영 순서 (규칙 세우기 세션)

1. 학교 `plugins/ModelEngine/blueprints/` 에 `modelengine/*.bbmodel` 3개 → `/meg reload` → ItemsAdder 병합 `/iazip` → 런처 통합 리소스팩 갱신.
2. 학교 MythicMobs 에 Mobs·Skills yml → `/mm reload` (콘솔에 모르는 메커닉·조건 경고 없는지).
3. VFX: 런처 distribution.json 에 `config/portablevfx/effects/ArchlichMorvath/**` File 줄 추가 + 학교 PortableVFX effects 에 같은 폴더.
4. ChacaDungeon(새 플러그인) 이 있어야 `chavfx` 가 동작 (없으면 그 줄만 "모르는 메커닉" 경고, 나머지는 동작).
5. 시험: `/mm m spawn CA_Boss_Archlich` (dungeon1 빈 곳) → 1페이즈 스킬·병사 소환·HP 900 이하 전환·2페이즈 스킬·사망 시 병사 무너짐.
   던전에서는 `/던전관리 보스 lich CA_Boss_Archlich` 로 칸마다 자리 등록.

## 확인한 것 / 못 한 것

- ✔ yml 두 개 문법(SnakeYAML) · 메커닉·조건·타게터 이름을 배포 MythicMobs 5.9.0 jar 에서 확인 (setAI, state/model=ModelEngine, randomskill, projectile, summon, look,
  RandomLocationsNearCaster, MobsNearOrigin, MobsInRadius, mobsinradius, health, variableequals). chatelegraph·chastatus 인자 = 배포 MagicMythic 와 같음.
- ✔ 모델 3개: 애니 이름이 스킬과 맞음 (1페이즈 idle·walk·attack·skill_summon·skill_curse·death·spawn·phase_shift / 2페이즈 + skill_storm·skill_reap / 병사 spawn·idle·walk·attack·death), hitbox 본 있음.
- ✔ ChacaDungeon 빌드 (chavfx 포함).
- ✘ 게임 안 시험 안 함 (스킬 타이밍·예고·VFX·모델 교체·밸런스). 보스 체력 1800·피해 수치는 오너 확인 필요.
- ✘ VFX 팩의 클라이언트 호환 검사는 만든 쪽(client_compat OK) 결과를 믿음 — 런처 반영 뒤 실제로 보이는지 확인.
- ✘ ModelEngine 큰 본 크기 제한은 /meg reload 콘솔에서 확인 (보스 3.3블록·지팡이 3.4블록).
