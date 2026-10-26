# 원소 반응 VFX 15종 (Claude, 2026-10-15)

브랜치: `claude/reaction-vfx-20261015` (기준: `claude/artifact-codex-20261015` @ `10c7e42`, 원소 반응·아티팩트 도감 포함)

## 0. 상태

- **빌드 안 됨.** `ReactionVfx`는 Bukkit 스텁으로 javac 타입 검사만 했습니다. Gradle·실게임은 확인하지 못했습니다.
- VFX 팩은 툴킷 `client_compat.py check` **OK**입니다(클라이언트 로더 규칙 통과).
  - 실제 텍스처는 14장, RGBA 2.5 MiB입니다(로더는 같은 텍스처를 한 번만 올림).
  - 검사기의 WARN 6.7 MiB는 재질마다 중복으로 센 값입니다.
- 오너 요청: 레퍼런스 15종(2026-10-14 수정표)대로, 과하지 않고 짧게, 바로 연결.

## 1. 구성

| 위치 | 내용 |
|---|---|
| `portable-vfx-runtime/assets/ElementReactions/` | 클라이언트 팩(`vfx.json` + `textures/` 14장). 클라 설치 위치 `.minecraft/config/portablevfx/effects/ElementReactions/` |
| `tools/vfx-src/ElementReactions/` | 원본 spec(`elementreactions.js`)·텍스처 생성기(`gen_er.py`). 고칠 때 anime-vfx-maker 툴킷의 make_bundle → client_compat fix/check |
| `magic-mythic-paper/.../ReactionVfx.java` | PortableVFX `PortableVfxService.play(EffectRequest)`를 **리플렉션**으로 호출(컴파일 의존 없음, `softdepend: PortableVFX` 추가) |
| `Reactions.java` | 반응이 일어날 때 VFX 재생(아래 표). 시각 효과만, 게임 로직은 그대로 |
| `config.yml` | `reactions.vfx.enabled / pack / range` |

효과 ID: `claude:elementreactions/<반응>`. 시스템 15개는 모두 `impact` 역할이고 이름은 다음과 같습니다.
`steam, wildfire, magma, flare, ember_curse, shock, mud, rainbow, abyss, sandstorm, flash, void_gust, crystal, bind, eclipse`

## 2. 재생 위치

| 반응 | 위치 / 크기 | 길이 |
|---|---|---|
| 증기 폭발, 모래 회오리, 일식 | 대상 발밑, 배율 1 (실제 반경 2/3/3블록으로 제작) | 0.3 / 0.5 / 0.5초 (일식은 0.45초에 터지는 링 → 서버 피해 0.5초와 맞춤) |
| 무지개 | **시전자** 발밑, 배율 1 (회복 범위 4블록 링) | 0.5초 |
| 불씨 확산 | 대상 + 번진 대상마다 | 0.2초 |
| 용암·플레어·낙인·진흙·심연·섬광·수정화·매장 | 대상 발밑, 배율 = 키/1.8 (0.5~1.5) | 0.15~0.4초 |
| 공허 균열 | 반응한 자리(복귀 지점), 보스는 끌려오지 않으므로 생략 | 1초 |
| 연쇄 번개 | 튈 때마다: 이전 대상 가슴 → 다음 대상 가슴, yaw로 방향, 배율 = 수평 거리/3, 0.15초 간격 | 0.2초 |

## 3. 확인 부탁

1. **클라이언트 배포:** 런처/모드 배포물에 `ElementReactions` 팩을 넣어야 보입니다. 팩이 없으면 서버는 그냥 아무것도 안 보이고 오류도 없습니다.
2. **방향:** 연쇄 번개는 `EffectRequest` yaw로 로컬 +Z를 돌립니다(MC yaw: 앞 = (-sin, 0, cos)). 실게임에서 번개가 다음 대상을 향하는지 봐 주세요. 반대면 `ReactionVfx.bolt()`의 yaw에 180을 더하면 됩니다.
   - 높이 차는 반영하지 않습니다(수평 번개).
3. **재생 한도:** PortableVFX 틱당 재생 한도에 걸리면(RejectedExecutionException) 그 연출만 조용히 건너뜁니다. 다른 오류는 한 번만 경고합니다.
4. `/마법미몹 상태`에 `반응 VFX claude:elementreactions/*` 또는 `반응 VFX 대기 (PortableVFX 없음)`가 표시됩니다.
5. 버전 `...-reaction.2`
