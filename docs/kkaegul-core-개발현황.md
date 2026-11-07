# KkaegulCore 개발 현황 (2026-11-07 오후 기준 — 밤 패치는 HANDOFF.md 5번 참고)

- Paper/Gale 1.21.4 / Java 21 코어 플러그인 `kr.kkaegul.core`, KkaegulCore-0.1.0
- 화면 데이터는 플러그인 메시지 `kkaegul:ui`(서버→클라 JSON), 버튼은 `kkaegul:action`(클라→서버). 렌더러 없으면 타이틀/액션바/채팅 대체.
- 다이아 모델: "남은(목표) 다이아(debt)" 장부 (밤 패치로 시작 500~1500 랜덤). 증가 API = 늘어남, 감소·제출 = 줄어듦, 0 = 졸업. 주운 다이아 = 보유 다이아(화폐), /인출 /입금. 판매는 소수 다이아(예: 금 1개 0.08).
- 후원: donation-bukkit 2.0.2 이벤트 자동 연동. 섭주(OP가 받은 후원) → 서버 API, 그 외 → 개인 API.
- 운빨 게이지: **서버 공용** (global.yml), 누가 바꾸면 전체 알림.
- 명령어: `/깨굴`(= /kg) 한글 하위 명령 + 칸마다 한글 자동완성, 한글 닉네임으로도 지정 가능. 예) `/깨굴 다이아 이름 추가 10`, `/깨굴 위치 저장 race-start`, `/깨굴 게임 시간 poker 2h`, `/깨굴 초기화 <닉> 장비|부동산|운세|닉네임|전부`, `/깨굴 전체지급 [개수]`(손에 든 아이템 → 접속자 모두, 넘치면 우편함), `/깨굴 게임 일정 [위험한초대|보물찾기|카지노 분]`.
- 채팅 명령: `/공지`(운영자, 배너+소리) · `/귓 <한글닉> <말>` · `/답` · `/도움 <말>`(운영자만 보임, 30초 쿨) · `/수락`(카지노 초대).
- 탭 목록 이름: TAB 플러그인 `groups.yml` `customtabname: %kkaegul_display%` (칭호+한글닉). 정렬 `%kkaegul_debt_raw%` 많은 순.
- 머리 위 이름표: 지금은 모드 이름표(`nickname.client-nameplate: true`). UnlimitedNametags 1.6.9 는 creative-server 라이브러리를 못 받아 꺼짐 → 최신판 교체 필요.
- 알림: 채팅은 파란 느낌표(system 이모트) + 흰(중요)/회색 글씨, 오류는 빨간 느낌표. 중요 알림은 모드 화면 위 배너(금/파랑/보라).
- 데이터: `plugins/KkaegulCore/players/<UUID>.yml` + `global.yml` + `lands.yml`, 시작 때 메모리로 올리고 바뀐 것만 저장.
- NPC: Citizens `/npc command add kg npc run <동작> <p>` (콘솔) 또는 엔티티 바라보고 `/깨굴 npc 연결 <동작>`. 가챠 기계는 블록: 기계 앞에 서서 `/깨굴 위치 저장 gacha-machine.1~3` → 2칸 안 블록 우클릭.

## 미니게임 일정 (게임마다 따로, 동시 진행 가능, 한 사람은 한 게임만)
- OP·`kkaegul.minigame.exempt`·감옥·광산·게임 중인 사람은 초대 안 됨. `minigame.auto.from-day` 일차부터 자동.
- 위험한 초대: 30분마다, 의자 수(4명), 거부 불가, 지난 판 사람 제외, 1분 전 회색 예고, 앉은 뒤 20초 대기, 맨몸 입장.
- 보물찾기: 3시간마다 최대 30명, 거부 불가. `treasure-start` 로 이동 → 30초 대기(반경 15칸만) → 60초. 끝나면 원래 자리로 + 그 월드 바뀐 블록 전부 복구(`restore-<월드>.txt`).
- 카지노(홀덤·섯다 번갈아): 3시간마다, 보물찾기와 1시간 30분 어긋남. 보유 다이아 50+ 인 사람에게 초대장 → 60초 안에 `/수락`. 판돈 1다이아, 최대 15분. 디코 음성 이동.
- 빼빼로: 꺼둠(이벤트 한정).

## 땅 (차카데미 땅문서 방식, 청크 단위)
- 땅문서 = 토지 구매권(`land_ticket`). 들면 주변 3×3 청크 경계 벽 + 화면 위 가운데 상태 이름표. 들고 우클릭 = 서 있는 청크 구매.
- 내 땅 안: 얇은 경계 + 오른쪽 위 땅 카드 → 관리 창(들어온 사람 허용 / 허용 명단 빼기). 회수는 밤 패치로 막음(`land.allow-release`).
- 보호: 주인·허용 명단만. 폭발·불·피스톤·흘러드는 물/용암 차단. 우회 `kkaegul.land.bypass`.

## 카지노 · 기타
- 개구리 경주: 10분마다 자동(밤 패치), 건 사람끼리 나눠 갖기(수수료 10%), 못 맞히면 이월. `race-start`/`race-finish`.
- 개구리 점프: 85%×1.1 … (어디서 정산해도 기댓값 < 1).
- 디스코드 보이스 봇: `이깨굴/디스코드봇` (Node).
- ModelEngine 장식 모델: `arcade_station`, `gashapon_station` (MythicMobs `깨굴_기계.yml`).

## 클라이언트 UI 모드 (kkaegul-ui-fabric)
- 글씨: 잉크 기준 가운데 맞춤, 그림의 글자 판은 PNG 를 직접 재서 맞춤. `&l`(굵게)는 무시. `{tr:item.minecraft.x}` 는 게임 언어 이름으로 바뀜.
- HUD ▼ / M 키 = 드롭다운(내 정보·우편함·인출·이동·랭킹·디코 연동).

## 깨굴랜드 런처 (헬리오스 포크)
- 포크 소스: `claude/kkaegul-launcher` → CI(windows) → 설치 파일 조각 `claude/kkaegul-launcher-build`.
- 이름 KkaegulLand / 데이터 폴더 `%APPDATA%/.kkaegulland` / 자동 업데이트 끔.
- 배포 정보: `claude/kkaegul-launcher-distro`. 서버 주소는 `tools/kkaegul.json` → `python3 tools/build_distro.py` → 푸시.
