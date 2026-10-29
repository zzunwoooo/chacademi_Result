# ArchlichMorvath — 무덤의 대현자 모르바스 보스 스킬 VFX (변환 메모)

시각 효과 전용. 피해·상태이상·소환·예고(빨간 장판)는 서버(chatelegraph)와 모드가 처리한다.
모든 시스템의 0초 = 스킬 시전 시작(= 보스 애니메이션 시작, 예고 시작). 타이밍은 보스 bbmodel 애니메이션에 맞춰져 있다.

| system | role | 놓는 곳 | 저작 크기 (vfxref) | 핵심 시각 |
|---|---|---|---|---|
| bolt / bolt_p2 | projectile | 보스 지팡이 끝 → 대상 (0.55 s 발사) | — | 해골 코어 + 회전 호 + 영혼 꼬리 |
| bolt_hit / bolt_hit_p2 | impact | 명중점 (바닥/벽/공중) | — | 섬광, 해골 파열, 문장 |
| summon / summon_p2 | static | 보스 발밑 | 반경 4 | 1.3 s 내려찍기 폭발 |
| grave / grave_p2 | static | 해골 소환 지점마다 (1.3 s 이후) | 반경 1.2 | 0.45 s 땅 갈라짐 + 불기둥 |
| curse / curse_p2 | static | 대상 발밑 | 반경 5 | 1.5 s 분출, 3.0 s 소멸 |
| storm | static | 보스 발밑 | 반경 8 | 펄스 1.0/1.6/2.2 s, 폭발 2.6 s |
| thrall_slash / thrall_slash_p2 | static | 망자 병사 발밑, 병사 정면 = +Z | — (scale 1) | 0.42 s 대각선 베기 (공격 애니 0.35→0.45 s), 명중점 약 (−0.35, 0.85, 1.25) |
| reap | static | 보스 발밑, 보스 정면 = +Z | 폭 4 × 길이 14 (vfxref 1) | 0.85 s 손 내리찍기·균열, 1.45 s 낫 베기 반경 ~6 |

- static 시스템 정면(+Z)은 서버가 넘기는 시전자 수평 방향. reap 만 방향이 중요하다.
- 크기: 저작 반경 그대로 쓰면 scale 1. 예고 반경이 다르면 scale = 예고 반경 / vfxref.
- 1페이즈(초록)와 2페이즈(진홍)는 같은 레이어·같은 텍스처, 머티리얼 색만 다르다.
- 텍스처 19장, 고유 RGBA 약 3.9 MiB (client_compat 의 WARN 8.0 MiB 는 머티리얼마다 같은 텍스처를 다시 센 값).
- high impact 기능: meshParticle (마법진 라인워크·룬띠·낫·소용돌이), bloom, orbitalVelocity (storm).


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
