# kkaegul-ui-fabric — 운빨깨굴랜드 고화질 UI 클라이언트 모드

Fabric 1.21.4 (yarn 1.21.4+build.8, loader 0.16.14, Fabric API 0.119.4+1.21.4, Java 21).
서버 플러그인 **KkaegulCore** 와 짝. 서버가 `kkaegul:ui` 로 보내는 JSON 을 받아 그리고, 버튼은 `kkaegul:action` 으로 보낸다.
모드가 채널을 등록하면 서버는 자동으로 "렌더러 있음"으로 보고 채팅/타이틀 대체 출력을 멈춘다.

## 빌드

```
cd kkaegul-ui-fabric
./gradlew build          # → build/libs/kkaegul-ui-0.1.0+mc1.21.4.jar
./gradlew preview        # 게임 없이 화면 PNG 미리보기 → build/preview/*.png
```

## 그리는 것

| 종류 | 화면 | 서버 메시지 |
| --- | --- | --- |
| 상시 | 왼쪽 위 HUD (깨굴이·운빨 게이지·남은/보유 다이아·코인·뱃지, 채팅 열고 마우스 올리면 툴팁) | `update hud/gauge/diamond/fever` |
| 상시 | 가운데 멘트 배너, 아래 토스트 | `center`, `toast` |
| 연출 | 다이아 증감 룰렛 (4칸 숫자 슬롯) | `roulette` |
| 연출 | 상자 오픈 / 피버 시작 / 초대장 / 졸업 | `fx box_open, fever_*, invite_letter, graduate` |
| 창 | 가챠 기계 (3칸 슬롯·잭팟·파괴·방지권) | `open gacha` + `fx gacha_*` |
| 창 | 메뉴 (내 정보 / 접속 보상 / 칭호 / 명예의 전당), 기본 키 **M** | `open menu` |
| 창 | 유저 텔레포트 선택 | `open player_select` |
| 창 | 한글 닉네임 설정 (첫 접속은 닫기 불가) | `open nickname` |
| 이름표 | 머리 위 이름 = 칭호 + 한글 닉네임 | `names` |

## 구조

- `kr.kkaegul.client.ui` — 화면 코드 (마인크래프트 의존 없음). `Canvas` 하나로 게임과 미리보기가 같은 코드를 그린다.
  - 좌표는 세로 1080 기준 가상 해상도. 그림 id = `textures/ui/` 아래 경로 (`hud/gauge_frame`).
  - 글자는 Pretendard TTF 를 최종 픽셀 크기로 직접 래스터화 (`TextRaster`) → 한글이 또렷함. 색코드 `&a`/`§a` 지원.
- `kr.kkaegul.client.mc` — 마인크래프트 연결: `Gfx`(알파 곱한 밉맵 텍스처, 차카데미 HudTextureCache 방식), `McCanvas`, `PanelScreen`, 패킷, `KkaegulClient`.
- `kr.kkaegul.client.mixin.PlayerNameMixin` — 머리 위 이름표를 서버 닉네임으로.
- `src/test/.../preview` — AWT 미리보기 (`./gradlew preview`).

## 그림 교체

`src/main/resources/assets/kkaegul/textures/ui/<그룹>/<이름>.png` 를 같은 이름으로 바꾸면 끝. 비율이 달라지면 위치 비율(코드의 % 표)만 조정.
닉네임 전용 그림: `textures/ui/nickname/` (GPT 시안 layout.json 배치 그대로 적용됨).

## 글꼴

주 글꼴: 배민 주아 Jua (SIL OFL 1.1, `font/JUA_LICENSE.txt`). 주아에 없는 기호(·, → 등)만 Pretendard (SIL OFL 1.1, `font/PRETENDARD_LICENSE.txt`).

## 확인 상태

- 화면 코드: 미리보기로 22장 렌더 확인 (build/preview).
- 마인크래프트 연결부: 차카데미 magic-codex-fabric 과 같은 API 호출만 썼고 시그니처 스텁으로 컴파일 확인. **실제 Loom 빌드/인게임 확인은 아직** (작업 환경에서 Fabric maven 이 막혀 있음).
