# 대화 초상화 하단 클리핑 보완 — 2026-10-11

기준 6d6801e. 개인 초상화가 대화판 위에서 수평으로 끊기는 원인은 판의 논리 상단(639)과 그림 내부의 실제 테두리 사이 투명 여백이었다. 앞선 수정은 외부 스토리 경로만 CLIP_BOTTOM=671을 적용했고, 선택 직후 공통 PlayerTurn 및 AI NPC 초상화 경로에는 639가 남아 있었다.

## 최소 변경

- PlayerTurn.drawPortrait: scissor 하단만 PANEL_TOP → CLIP_BOTTOM.
- NpcTalkScreen의 NPC 초상화: scissor 하단만 639 → CLIP_BOTTOM.
- 기존 DialogueScreen.drawExternalPortrait와 동일한 공통 상수 사용. 외부 스토리, 일반 고정 대화와 선택 직후 내 차례, AI 대화 모두 하단 기준을 공유한다.
- 머리 위치, 그림 크기·종횡비·alpha bounds·이미지/폰트·대화판 좌표는 변경하지 않았다. 서버/DB/스토리 보상 코드는 수정하지 않았다.

## 검증

호스팅 Java21에서 Fabric test/remapJar 성공. UI 테스트 226개, 실패/오류/스킵 0. 비개인 합성 실루엣으로 머리 위치·배치 불변과 6개 화면 크기 × GUI scale 1~4에서 패널의 실제 금선 아래까지 clip이 겹치는 것을 검증했다. 세 렌더 경로의 공통 상수 사용도 확인했다. 실제 게임 GPU 렌더 육안 확인은 포함하지 않는다.

기존 UI JAR과 비교해 assets/ 901개 항목 해시가 모두 동일하고, 변경된 JAR 항목은 PlayerTurn.class 및 NpcTalkScreen.class 두 개뿐이다. 기존 Story 개인 폰트 3개도 원본과 바이트 일치하며 재배포하지 않는다.

최종 remapJar SHA-256: 52648a142d3d6f3aa3fade110e53f8899fa7268cb2701fc53bc356793807ee6e

교체 대상은 개인 런처의 MagicCodex UI JAR 한 개다. 게임이 종료된 상태를 확인한 뒤 교체하며 서버 재시작은 필요 없다. 공개 Git에는 코드·비개인 회귀 테스트·이 문서만 포함한다. 개인 초상화/캐시/설정/폰트/JAR은 포함하지 않는다.
