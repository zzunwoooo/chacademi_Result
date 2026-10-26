# Claude 인계: 대상 지정 마법 링을 몹 발밑 바닥에 (2026-10-15)

- **브랜치:** `claude/unit-target-ring-20261015`
- **기준 커밋:** `b69ea36` (`claude/broom-projectile-20261015`)
- **범위:** MagicCodex 클라이언트 `AimRenderer.java` 한 파일. 서버와 프로토콜은 변경 없음.
- **빌드와 테스트:** 미빌드, 실게임 미확인. 타입 검사 오류는 0건이다.
- **버전:** `...-followup.2-unitring.1`

## 요청

대상 지정형(UNIT) 마법, 예를 들어 빛의 낙인은 조준 링이 몹 발밑, 땅에 붙은 위치에 떠야 한다.

## 원인

선택된 대상의 링이 `p.point()`에 그려졌다. 이 값은 조준 레이가 몹 히트박스에 닿은 지점이라 몸통 높이에 해당한다. 그래서 링이 공중에 떠 있었다.

## 수정

- 선택된 대상의 링은 `target.getLerpedPos(tickDelta)`, 즉 발 위치를 기준으로 그린다.
- `ring()`은 발 아래 최대 16블록까지 땅(`AimTargeting.Ground.top`)을 찾아 그 위에 데칼로 붙인다.
  - 나는 몹도 바로 아래 땅에 링이 생긴다.
  - 16블록 안에 땅이 없을 때만 발 높이에 그린다.
- 후보 링(반투명)도 같은 규칙을 따른다.

## 머지 주의

- `light_mark`(빛의 낙인)의 조준 데이터는 `claude/magic-71-gameplay-20261014` 쪽 `spell-targeting.json`에만 있다.
- 두 라인 모두 followup 이후 `AimRenderer.java`를 건드리지 않았으므로, 이 커밋은 어느 쪽에도 충돌 없이 적용된다(cherry-pick 가능).
