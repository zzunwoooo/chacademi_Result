# 무덤의 대현자 모르바스 — 보스 스킬 세트

모델: `archlich.bbmodel`(1페이즈), `archlich_p2.bbmodel`(2페이즈) · VFX 팩: `ArchlichMorvath.zip` · 스킬 초안: `chacademi_archlich_skills.yml`
모든 타이밍은 **시전 시작 = 애니메이션 시작 = 예고 시작 = VFX 0초** 기준.

## 스킬 표
| 스킬 | 페이즈 | 애니메이션 | 예고 (chatelegraph) | 판정 시점 | VFX 시스템 (vfxref) |
|---|---|---|---|---|---|
| 영혼탄 | 1·2 | `attack` 1.2 s | 없음 (단일 대상 투사체) | 0.55 s 발사 | `bolt` + `bolt_hit` / `_p2` |
| 망자 소환 | 1·2 | `skill_summon` 2.4 s | 원 r=4, 보스 중심, 26틱 | 1.3 s 내려찍기 | `summon` (4) + 소환 지점마다 `grave` (1.2) / `_p2` |
| 저주 장판 | 1·2 | `skill_curse` 2.0 s | 원 r=5, 대상 발밑, 30틱 | 1.5 s 분출 | `curse` (5) / `curse_p2` |
| 영혼 폭풍 | 2 | `skill_storm` 3.2 s | 원 r=8, 보스 중심, 20/32/44/52틱 (4회) | 1.0·1.6·2.2 s 펄스, 2.6 s 폭발 | `storm` (8) |
| 사신의 손아귀 | 2 | `skill_reap` 2.4 s | 직선 폭4×길이14 17틱 → 부채꼴 r=6.5 150° 29틱 | 0.85 s 균열, 1.45 s 낫 | `reap` (1, 정면 +Z) |

## 잡몹: 망자 병사 (`archlich_thrall.bbmodel`)
보스가 `망자 소환`으로 부르는 해골 병사 한 종류. 녹슨 검, 보라 누더기(보스 로브 색), 초록 눈, 깨진 투구. 큐브 82개, 히트박스 0.7×1.95 (눈 1.7).
애니메이션 `spawn`(1.6 s, 무덤 VFX `grave` 0.45 s에 땅에서 솟음) · `idle` · `walk` · `attack`(1.0 s, 0.45 s 베기) · `death`(뼈 무더기). 이로치 없음.
근접 공격 VFX `thrall_slash` (1페이즈) / `thrall_slash_p2` (2페이즈에 소환된 병사): 병사 발밑에 놓고 공격 애니 시작과 함께 재생.

## 페이즈 전환
HP 50% 이하 → `phase_shift`(3 s, 1페이즈 모델) → 끝나는 순간 `archlich_p2` 모델로 교체 → `spawn`(각성 2.4 s). 교체 순간 후드·로브가 한 번에 바뀌므로 그때 폭발 VFX를 덮으면 자연스럽다 (현재 팩에는 전환 전용 VFX 없음).

## GPT에게 넘길 것
- `ArchlichMorvath.zip` 변환·등록 (client_compat 검사 통과, 고유 RGBA 약 3.9 MiB).
- 보스가 아직 `chacademia_custom.yml`의 몹이 아니므로, 엔티티 등록(히트박스 1.4×3.3, 눈 2.6) 후 yml 초안의 `STATUS_*` 자리에 상태이상 16종 중 하나를 고르고, 소환 해골 몹 id(`CA_ARCHLICH_SKELETON` 자리)를 정해 채운다.
- 영혼탄은 단일 대상이라 예고 없이 투사체로 둠 — 예고가 꼭 필요하면 직선 폭 1.6 × 길이 16, 11틱을 추가.
