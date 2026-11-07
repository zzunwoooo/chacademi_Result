# 운빨깨굴랜드 리소스팩 (UI + 아이템)

Minecraft 1.21.4 (pack_format 46). 서버 리소스팩으로 배포하거나 클라에 넣어서 사용.

## 구성
- `assets/kkaegul/textures/ui/<그룹>/<이름>.png` — 고화질 렌더러용 UI 스프라이트 (여백 제거, 256색 압축)
  - hud, notice, roulette, gacha, gacha_machine, gacha_fx, chest, reward, fever, user_select, invite, menu
- `assets/kkaegul/items/*.json` + `models/item` + `textures/item` — 커스텀 아이템 아이콘 15종 (1.21.4 item model)
- `ui_manifest.json` — 스프라이트별 경로·크기·원본 파일·자른 영역(crop)·배율(scale)
  - 원본 geometry.json 좌표를 쓰려면: 새 좌표 = (원본 좌표 - crop 왼쪽위) × scale
  - `edited` 표시된 것은 여기서 다시 칠했으므로 원본 좌표를 쓰지 말 것

## 여기서 직접 보정/생성한 것
- 가챠 본체 3종: 같은 크기(1024폭)로 맞추고 안쪽 판을 초록 / 보라 / 차콜+금테로 칠함
- 파괴 방지권 보호막: 안쪽 반투명 보라 채움 + 진한 테두리
- 금 간 효과: 굵고 진한 선으로
- 찢어진 방지권 티켓 좌/우: 보라 방지권 티켓을 지그재그로 가름
- 메뉴: 받기 가능 보상 카드(원본이 번져 있어 받음 카드를 좌우 대칭으로 지우고 초록 테두리), 탭 8장 같은 256x256으로 통일
- 티켓 아이템 9종: 방지권 티켓을 빈 티켓으로 만든 뒤 색을 바꾸고 기존 아이콘을 문장으로 얹음

## 플러그인 연결
config.yml `items.use-item-model: true` 로 바꾸면 커스텀 아이템이 이 모델로 보임.
(ItemsAdder 를 쓰면 이 아이템 모델 대신 ItemsAdder 쪽을 사용)

## 아직 없는 그림

