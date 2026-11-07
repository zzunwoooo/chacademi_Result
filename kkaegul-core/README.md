# KkaegulCore — 깨굴이의 위험한 초대 : 운빨깨굴랜드

Paper **1.21.4** / Java 21 코어 플러그인. UI는 없고, 화면 데이터는 전부 `kkaegul:ui` 채널 JSON으로 고화질 렌더러에 보냅니다 → [docs/UI_PROTOCOL.md](docs/UI_PROTOCOL.md)
렌더러가 없는 클라이언트에서는 타이틀/액션바/채팅으로 대체 출력되기 때문에, UI 없이도 전 기능을 테스트할 수 있습니다.

## 빌드

- 보통: `./gradlew build` (Gradle wrapper 가 없으면 `gradle wrapper` 한 번) → `build/libs/KkaegulCore-0.1.0.jar`
- 오프라인: `./build-offline.sh` — `stubs/` 의 컴파일 전용 API 스텁으로 빌드 (스텁은 Paper 1.21.4 실제 시그니처와 대조 검증됨). 서버에 넣는 jar 에는 스텁이 포함되지 않습니다.

## 설치 후 할 일 (순서대로)

1. `plugins/` 에 jar 넣고 서버 시작 → `plugins/KkaegulCore/` 에 `config.yml`, `messages.yml`, `quiz.yml` 생성
2. 게임 안에서 위치 저장: `/kg setloc <이름>`
   - 필수: `lobby`, `start`(첫 접속 스폰), `jail`
   - 이동: `village`, `gacha`, `wild`(없으면 랜덤 야생)
   - 광산: `mine.treasure`, `mine.limited1` … + config `mine.regions` 에 구역 좌표
   - 미니게임: `pepero` (또는 `pepero.1.a`/`pepero.1.b` …), `quizdoor.start`, `quizdoor.1`~`5`, `invite-chair.1`~`N`, `invite-drop`, `treasure-start`, `treasure-chest.1`~`N`
   - 확인: `/kg locs`, `/kg game check`
3. NPC 연결: 엔티티(주민/아머스탠드 등)를 바라보고 `/kg npc bind <동작>`
   - Citizens 등은 NPC 명령으로 콘솔 실행: `kg npc run <동작> {player}`
   - 감옥 출구는 NPC 2개: `jail-exit-lobby`, `jail-exit-return`
   - 월드 전광판: `/kg setloc board.donation`(제출소 옆), `board.farm`, `board.increased`, `board.breaks`, `board.deaths`, `board.remaining`
   - 동작: `profile, armor, realtor, fortune, wild, wild-random, jail-exit, jail-game, submit, submit-random, submit-board, gacha-1, gacha-2, gacha-3, sell, coin-shop, farm, mail, playtime, hall, trash, lobby, village, gacha-shop, titles, card-draw`
4. 후원 API 연결: 후원 연동 플러그인/봇이 콘솔에서 실행
   - 개인: `kapi <마크닉> <번호>` (예: `kapi 준우 500`)
   - 섭주: `ksapi <번호>` (예: `ksapi 1289`)
   - 번호가 겹치는 333 / 500 / 1000 은 명령어가 달라서 문제없음
   - 오프라인 대상은 접속 시 실행, 모든 호출은 `api-log.txt` 에 기록

## 들어간 기능

| 영역 | 내용 |
| --- | --- |
| 다이아 장부 | 남은 다이아(증가=늘어남, 감소·제출=줄어듦, 0=졸업). **1~2일차 500개 미만 감소 차단**(500 아래로도 안 내려감), 오픈 전 테스트 중에도 적용(`protection.*`) |
| 룰렛 | 증가/감소/빅/소소/작은 룰렛, 플레이어별 대기열, 연출 시간 후 반영, 게이지 1+1·1+2, 피버 가속 |
| API | 개인 13종 / 섭주 6종, 전부 config 액션으로 수정 가능 |
| 상자 | 일반/고급/행운 아이템, 세트 1111/5555, 상자 피버 1+1, 행운상자 전체 이벤트 8종 |
| 피버 | 다이아 증감 피버(룰렛 빨라짐+1+1, 랜덤 3배 피버 1+2), 상자 피버 |
| 감옥 | 웅크리기 40~80회, 탈출 방지, 무적, 출구 NPC (로비 / 50% 복귀 "아 까비ㅋ" · 실패 "못돌아가쥬~"), 다이아 증가 게임 꽝/x1/x2/x3/x5 |
| 괴롭히기 | 개인 6종(떨구기는 가중치 0으로 제외), 섭주 6종(거탐 퀴즈 3종, WASD, 하늘 50칸, 0.5% 피버, 0.5% 전원 감옥, 1% 로비 초대) |
| 거탐 퀴즈 | 채팅으로 답, 정답=소소 감소 / 오답=소소 증가 (초반 보호 자동 적용) |
| 경제 | 돈, 광물 판매, 코인 구매(실패10/정상79/2배10/3배1), 작물 시세 1시간 변동 |
| 제출소 | 일반 제출, 랜덤 제출(70/10/10/10 + 멘트), 기부 랭킹 TOP10 |
| 운빨 게이지 | 0~99, 4구간 효과(추가 드랍/블록 드랍/파괴확률↓/1+1/1+2/고수치↑), 변동권 2종 |
| 곡괭이 가챠 | 3칸 슬롯: 기계 555/999/151515 = 행운·효율·내구성 각 1~5/1~9/1~15를 새로 뽑아 덮어씀, 세 칸 모두 최대면 잭팟, 코인 5/9/15, 파괴, 방지권 자동 사용 |
| NPC | 프로필, 갑옷 뽑기(멘트 7종, 구리=구리색 가죽), 부동산업자(70%/개똥손), 오늘의 운세(하루 3번, 10분 버프·디버프, 합계 15 상한), 야생/랜덤 텔포 |
| 접속 보상 | 30분/3시간/8시간 수선 뽑기, 꽝 종이 5장 → 고급상자 |
| 칭호 | 가챠 7/7/7 [LUKY] 행운+1, 9/9/9 [운이 좋군] 행운+2, 15/15/15 [님 개쩔어요]+동상 훅, 깜빵 50/100회, 채팅·탭 표시 |
| 명예의 전당 | 다이아 누적, 곡괭이 파괴, 즉사, 남은 다이아, 기부, 깜빵 |
| 보물광산 | 하루 3번, 90% 제한맵(도구 제한)/10% 보물광산, 시간 후 복귀, 구역 자동 재생성 |
| 미니게임 | 3시간마다 랜덤 게임 + 랜덤 강제 초대(초대장 연출): 빼빼로, 퀴즈맵(문 섞기·문 위 텍스트), 위험한 초대(의자+WASD), 보물찾기 |
| 편의 | 우편함, 쓰레기통, 이동, 내 정보 |

## 테스트 명령

```
/kg day                       서버 일차, 초반 보호 상태
/kg run 준우 roulette increase
/kapi 준우 500   /ksapi 1000
/kg diamond 준우 set 3000
/kg coins 준우 +100   /kg gauge 준우 95
/kg give 준우 premium_box 3
/kg jail 준우   /kg harass all   /kg quiz trivia   /kg wasd
/kg game start pepero 준우 친구   /kg game check
/kg ui gacha.spin machine=3   (UI 버튼 시뮬레이션)
/kg protection off            (초반 보호 끄고 감소 테스트)
```

## 아직 안 된 것 / 확인 필요

- **UI**: 만들 화면은 docs/UI_LIST.md 의 10개. 렌더러에서 docs/UI_PROTOCOL.md 대로 그리면 됨
- **경주, 개구리 점프**: 규칙 미정이라 미구현. **카드뽑기**는 임시 규칙(코인 3개, 5장 중 1장, 결과 가중치)으로 만들고 기본 꺼둠 (`cards.enabled`)
- **땅 시스템**: 땅 구매권 지급과 "구역 랜덤 배정 + 명령 실행" 훅까지만. 실제 보호구역은 땅 플러그인 명령을 `land.*` 에 연결
- **보이스**: 프로필에 보이스 값 저장 + `profile.on-complete-commands` 훅만 있음 (어떤 보이스 플러그인인지 미정)
- **151515 전용 동상**: `titles.list.amazing.statue-commands` 에 동상 생성 명령 연결 필요
- **config 의 ★ 값**: 기획서에 숫자가 없어서 임시값 (가챠 파괴확률, 소소 룰렛 범위, 상자 가중치, 코인 가격, 판매가 등)
- **리소스팩**: 콜라캔/티켓 모델 준비 후 `items.use-item-model: true`
- 실서버 구동 테스트는 아직 못 함 (빌드 + API 시그니처 검증까지 완료)
