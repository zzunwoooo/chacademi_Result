# MagicCodex 장비창 빗자루 칸 — 변경 제안 (GPT 검토용, 2026-10-12)

**상태: 제안만. MagicCodex 코드는 아직 바꾸지 않았다.** 사용자 규칙대로 GPT 가 검토·승인한 뒤 적용한다.
빗자루 데이터·장착·3D 모델 그리기는 차카데미 모션 모드(`chacademy-motion`, 0.3.0)가 맡고, MagicCodex 는 화면만 그린다.

참고 자료 (사용자 제공): `Chacademi_broom_UI_PNG.zip`
- `broom_equip_slot.png` (2172x724, 유효 영역 41,9 ~ 2111,674) — 캐릭터와 닉네임 사이 가로 장착 칸
- `broom_selection_card.png` (845x1860, 유효 51,0 ~ 844,1860) — 선택 카드 (같은 카드 반복)
- `broom_selection_panel.png` (1672x941, 유효 0,0 ~ 1643,941) — 선택창 배경
- 빗자루 그림·문구는 없음 → 모델은 아래 API 로 3D 렌더, 글자는 기존 장비 UI 글꼴(FONT)·버튼·선택 강조 재사용
- 레퍼런스 화면 2장(장비창, 빗자루 선택)과 Claude 목업 `broom_ui_mockup.png` 첨부 (목업은 배치·동작 설명용, 최종 디자인 아님)

## 1. 화면 변경

### 장비창 (`EquipmentScreen`)
- 캐릭터(`InventoryScreen.drawEntity`, 현재 574,174 ~ 1100,632)와 닉네임 칸 사이에 `broom_equip_slot.png` 를 넣는다.
  README 대로 **겹치지 않게 장비창 세로 여백을 늘린다**(캐릭터 영역을 조금 줄이거나 닉네임·하단 수치 줄을 아래로).
- 칸 안: 왼쪽에 "✦ 빗자루" 라벨, 가운데 장착 빗자루 3D 모델(가로, 손잡이 왼쪽·솔 오른쪽), 그 아래 이름.
  - `renderGui(ctx, id, cx, cy, 길이≈칸 너비의 45%, yaw 90, pitch 14, spin 0)`
  - 장착이 없으면(`equipped()` 빈 문자열) 흐린 실루엣 대신 "연습용 빗자루 (10초)" 문구.
- 칸 클릭 → 빗자루 선택창. `available()` 이 false(모드·서버 플러그인 없음)면 칸을 숨긴다.
- 장비창을 열 때 `refresh()` 한 번 호출(권한이 바뀌었을 수 있음).

### 빗자루 선택창 (새 화면, 장비창 위에 뜸)
- 배경 `broom_selection_panel.png`, 제목 "빗자루 선택", 닫기 X.
- **보유한 빗자루만** 카드로 보인다 (`owned()` 순서 = 기본 → 전설). 한 줄 5장, 6장 이상이면 좌우 화살표로 페이지(최대 10장 = 2페이지).
- 카드: `broom_selection_card.png`, 안에 세로 3D 모델 `renderGui(ctx, id, cx, cy, 카드 높이의 60%, yaw 0, pitch 90, spin)`, 아래 이름판에 이름.
- 장착 중 카드: 기존 선택 강조(금색 테두리 glow) + "장착 중" 배지.
- 카드 클릭 = 선택(강조), 아래 [장착] [취소] 버튼. [장착] → `equip(id)` → 서버가 확인 후 목록을 다시 보냄(`version()` 증가) → 배지 갱신.
- **마우스를 올리면 능력치 게이지 팝업** (사용자 요청: "한눈에"):
  - 제목 = 이름 + 등급(`grade(id)`), 그 아래 5줄: 라벨(`gaugeLabels()`) / 막대(`gauges(id)` 0~1) / 값(`gaugeTexts(id)`)
  - 막대 색은 값 구간별로 (예: 0.75↑ 초록, 0.5↑ 파랑, 그 아래 주황). 장착 중 빗자루와 비교 표시(얇은 마커)도 있으면 좋음.
  - 올린 카드의 모델은 천천히 회전(spin 을 시간에 따라 증가)시키면 "살아 있는" 느낌.
- 화면 좌표계는 기존 `CodexLayout.codexFit` 1672x941 기준을 그대로 쓰면 패널 PNG 가 1:1 로 맞는다.

## 2. 모션 모드 API (리플렉션, 컴파일 의존 없음)

클래스 `kr.chacademy.motion.api.BroomApi` (모두 static, 클라이언트 스레드):

| 메서드 | 반환 | 설명 |
|---|---|---|
| `available()` | boolean | 서버에 빗자루 플러그인이 있고 모드가 있음 |
| `refresh()` | void | 서버에 목록 다시 요청 |
| `owned()` | String[] | 보유 빗자루 ID |
| `equipped()` | String | 장착 ID (없으면 "") |
| `name(id)` / `grade(id)` | String | 표시 이름 / 등급 |
| `gaugeLabels()` | String[5] | 최고 속도, 가속, 선회, 제동, 안정성 |
| `gauges(id)` | float[5] | 0.15~1 (10종 범위 기준) |
| `gaugeTexts(id)` | String[5] | "27 b/s", "50", "10.5°", "4.0%", "0.82" |
| `mastery()` | float | 비행 숙련도 0~1 |
| `equip(id)` | void | 장착 요청 |
| `version()` | long | 목록이 바뀔 때마다 증가 |
| `renderGui(Object drawContext, String id, float cx, float cy, float length, float yaw, float pitch, float spin)` | boolean | 3D 모델 렌더 (현재 행렬 기준, z+200, 자체 조명·flush 포함) |

```java
// MagicProtectionLink 와 같은 방식 추천
static final Class<?> API = find("kr.chacademy.motion.api.BroomApi");
static Method m(String n, Class<?>... t) { ... API.getMethod(n, t) ... }
m("renderGui", Object.class, String.class, float.class, float.class, float.class, float.class, float.class, float.class)
    .invoke(null, context, id, cx, cy, 300f, 90f, 14f, 0f);
```
- Yarn `DrawContext` 와 모장 `GuiGraphics` 는 실행 중 같은 클래스라 그대로 넘기면 된다.
- 모델은 모드 안 `assets/chacademy_motion/brooms/<ID>.bbmodel` (리소스팩으로 교체 가능), 게임 속 비행 빗자루와 같은 모델·텍스처.

## 3. 서버 쪽 (이미 구현, ChacademyMotion 플러그인 0.3.0)

- **보유 = 권한** `chacademy.broom.own.<ID>` (예 `chacademy.broom.own.broom_7`), `chacademy.broom.own.*` = 전부. OP 는 전부 보유.
- 장착 = 플레이어별 저장(`broom-players.yml`). 장착이 없거나 권한이 빠지면 가진 것 중 가장 좋은 빗자루, 하나도 없으면 연습용(10초).
- 패킷: `chacademy:broom_list` (S2C: 장착 ID, 숙련도, [ID, 이름, 등급, 수치 6개]…), `chacademy:broom_req` (C2S: 0 새로고침 / 1 장착 + ID). 접속 1초 뒤 자동 전송.
- `/빗자루 장착 <ID>` (본인), 관리자 `/빗자루 선택 <ID> <플레이어>`.

## 4. 확인할 것

1. 장비창 세로 여백 조정이 기존 장비 슬롯·스탯 줄 클릭 영역과 충돌하지 않는지.
2. `renderGui` 는 `drawSpecial` + `flush` 로 그린다. 장비창의 다른 3D(캐릭터)와 z 가 겹치면 z 값(200) 조정 필요할 수 있음.
3. 모드가 없는 클라이언트: `Class.forName` 실패 → 빗자루 칸 숨김.
