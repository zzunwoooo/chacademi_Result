# 빗자루 배럴롤 회피 거리 증가 (Claude, 2026-10-17)

브랜치: `claude/broom-roll-20261017` (기준: `claude/broom-30-20261016` @ 10e288e — 빗자루 코드가 가장 최신인 브랜치)
상태: **빌드·배포 안 함**. 모드(클라이언트)만 변경, 서버 플러그인 변경 없음.

## 요청
A·A / D·D 배럴롤(옆 회피)이 균형(안정성)을 쓰는 것에 비해 이동 거리가 너무 짧음 → 더 멀리 회피.

## 변경 (`chacademy-motion-mod/.../broom/BroomPhysics.java`)
- `ROLL_IMPULSE` 0.66 → 0.85
- 새 상수 `GRIP_ROLL = 0.02`: 구르는 동안(14틱)은 옆미끄러짐 감쇠를 평소 0.10 대신 0.02로 → 끝까지 밀려 나감
- 속도 상한(최고 속도 × 1.1)은 그대로 유지 → 서버 SpeedGuard(최고 속도 × 1.45 + 0.6) 여유 안.
- 균형 소모(14)·무적(0.5초)·쿨다운(60틱)은 **변경 없음**.

시뮬레이션 (W 유지, 최고 속도에서 롤):

| 최고 속도 (b/t) | 0.7초 옆 이동 (전 → 후) | 전체 옆 이동 (전 → 후) |
|---|---|---|
| 0.545 | 3.4 → 4.9 | 4.4 → 7.1 |
| 1.036 | 4.7 → 7.8 | 6.0 → 11.8 |
| 1.65 | 5.1 → 10.0 | 6.6 → 15.6 |

## 테스트
- `BroomPhysicsTest.barrelRollDodgesFarSideways` 추가 (0.7초 7블록 초과, 전체 16블록 미만, 매 틱 속도 상한 유지).
- 기존 `BroomPhysicsTest`, `BroomEnduranceTest`(rollRemainsCollisionBounded 포함) 통과 — 클라우드에서 JUnit 대용으로 확인. GPT 가 `gradle test` 로 다시 확인 필요.

## 배포
- 모드 버전 `0.3.0-codex.3-hud.2-trails.2-brooms30.1-roll.1` → 런처 모드 jar 교체만.
- 주의: `claude/kyudichi-20261017` 등 최신 큐디치/하우징 라인은 broom-30 이전 빗자루 코드라 이 브랜치를 포함하지 않음. 통합 시 broom-30 라인과 함께 머지 (BroomPhysics 의 배럴롤 두 줄은 두 라인에서 동일해서 이 변경 자체는 충돌 없음).
