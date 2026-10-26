# 대상 체력바 HUD v4 + 상태이상 아이콘 v2 (Claude, 2026-10-19, 미빌드)

브랜치: `claude/status-hud-polish-20261019` (기준: `codex/operating-status-hud-20261019` @ `c5a2ccf`, 배포본)

오너 결정(2026-10-19): HUD v4 레퍼런스대로 디자인을 바꾸고, 디버프 아이콘도 새 16종으로 교체합니다. 가독성을 우선하고, 고화질 렌더러(HudTextureCache)를 씁니다.

## 바뀐 것 (클라 모드만, 서버·프로토콜 변경 없음)

- **에셋** `textures/gui/target_hud/`
  - 사용자 제공 `chacademi_hud_v4_frame_gauges_png`에서 가져왔습니다.
    - `frame.png`(01), `track.png`(02), `hp_normal.png` / `hp_rare.png` / `mana.png`(textures 2048px), `title_divider.png`(07)
  - 마나가 없는 대상(바닐라 몹 등)용으로 두 장을 새로 만들었습니다.
    - `frame_hp.png`: 01에서 HP·MP 사이 금색 구분선(y49~60, x54~1992)만 지움
    - `track_hp.png`: HP 트랙을 y19~67로 늘림
    - 이 경우 HP 게이지가 칸 전체(높이 48)를 채웁니다.
  - 예전 `title_decoration.png`와 세그먼트 프레임은 지웠습니다. `heart.png`는 그대로입니다.
  - 모든 PNG는 원본 해상도 그대로 `HudTextureCache`(알파 보정 트릴리니어 밉맵)로 그립니다.
- **상태이상 아이콘** `textures/status/*.png`: 사용자 제공 `CHACADEMI_STATUS_16_COLOR_V2`(256px, 이미 가운데 정렬)로 교체했습니다.
- **`TargetHudClient`**
  - 그리는 순서(뒤→앞): 트랙 → HP(비율만큼 잘라 그림, 압축 없음) → MP → 금색 프레임.
  - 좌표는 `layout.json` 기준입니다: HP x54 y19 1940×36, MP x54 y57 1940×10.
  - 아래 줄
    - 왼쪽: 하트, 그 아래 교화 %
    - 오른쪽: 디버프 아이콘(링 없음)을 오른쪽부터 채우고, 원소 표식은 그 왼쪽에 둡니다. 아이콘마다 아래에 남은 시간("3초", 60초 이상은 "N분", 무한이면 표시 없음)을 씁니다.
  - 가독성
    - 모든 글자를 검은 외곽선 위에 색을 채워 그립니다(`label()`).
    - 이름·숫자·아이콘은 레퍼런스보다 크게 했고, HUD 폭은 화면 46%(260~620 GUI px)입니다.
    - 아이콘 뒤에 아주 옅은 어두운 원을 깔아 밝은 하늘에서도 보이게 했습니다.
- **`StatusIcons`**: 링·원호를 없애고 심볼만 그립니다. 마지막 1초에는 깜빡입니다. 머리 위 아이콘과 내 디버프(오른쪽 위)도 같은 방식이고, 내 디버프는 아래에 남은 시간을 씁니다. 마우스 팝업은 그대로입니다.
- **`TargetHudLayout`**: 6칸 세그먼트 계산은 지우고 `width`와 `iconX`만 남겼습니다. 테스트도 같이 고쳤습니다.

## 확인 부탁

- 빌드 후 `/대상체력바미리보기 일반 · 이로치 · 보스 · 플레이어`로 확인해 주세요.
- `HudMesh.disk`와 `HudTextureCache.drawPart` / `drawStretched`는 기존 API입니다.
