# 대상 체력바: 내 에임 기준으로 띄우기 (2026-10-19, 오너: "띄우는 건 내 에임 기준으로, 내 플레이어 시선 말고")

기준: `claude/status-hud-polish-20261019` @ `7163262`. 빌드·실게임 안 함 (protocol 만 javac + 왕복 확인).

## 바뀐 것
- 어깨 시점 카메라는 머리 옆에 있어서, 서버가 쓰던 "눈 → 머리 방향" 광선과 화면 십자선이 어긋남 → 십자선 위 몹이 아니라 머리가 보는 몹이 떴음.
- **프로토콜** `TargetHudProtocol`: 새 C2S 채널 `magiccodex:target_aim` (21바이트: version, 카메라-눈 위치 차 dx/dy/dz, yaw, pitch). 위치 차 8블록 넘으면 거부.
- **클라** `TargetHudClient.sendAim`: 2틱마다 확인, 0.5° 또는 5cm 움직였을 때만 전송(안 움직이면 1초에 한 번). 어깨 시점이면 `AimView.camera()`, 아니면 눈 위치(=기존과 동일).
- **서버** `TargetHudBridge.look`: 1.5초 안에 받은 에임이 있으면 그 광선(카메라 위치 + 방향)으로 `TamingBridge.lookTarget(p, origin, direction, 25)` — 기존 길들이기 광선(ModelEngine 서브 히트박스 포함)을 출발점만 바꿔 그대로 사용. 에임이 없거나 오래되면(옛 클라) 예전 눈 광선.
- 전투 중 대상(10초)·보스 우선 규칙은 그대로. 바뀌는 건 "바라보는 몹" 고르는 기준뿐.

## GPT 확인
1. 빌드 (magic-codex-protocol / fabric / paper), 서버 Bridge + 클라 모드 같이 배포 (옛 클라는 예전 방식으로 계속 동작).
2. 실게임: F5 어깨 시점에서 십자선을 몹에 올렸을 때 그 몹이 뜨는지, 1인칭은 예전과 같은지.
