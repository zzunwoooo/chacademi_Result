# 운빨깨굴랜드 인수인계 (2026-11-07 밤 기준)

> 「깨굴이의 위험한 초대 : 운빨깨굴랜드」 마인크래프트 서버 (Paper 1.21.4, Java 21).
> 의뢰 서버이고, 서버 운영 기간은 **11/10 ~ 11/20** 예정이에요.
> 이 문서는 다음 Claude Code 세션이 바로 이어서 작업할 수 있게 정리한 거예요. 먼저 끝까지 읽어 주세요.

---

## 0. 작업 규칙 (사용자 요청, 반드시 지킬 것)

**말투·형식**
- 답은 **한국어**로 해요.
- 쓸데없는 말은 빼요.

**작업 방식**
- 사용자에게 일을 시키지 말고 **직접** 해요. 폴더 접근이 필요하면 허용 요청을 직접 띄워요.
- 요청받은 것만 고치지 말고, **원인을 찾아서** 같은 문제가 날 수 있는 다른 곳도 스스로 판단해서 같이 고쳐요.
- **"제대로 확인하고 마무리"**: 빌드가 되는지, 파일이 실제로 들어갔는지(해시 비교), 설정 YAML이 맞는지 확인한 뒤에 끝났다고 말해요. 확인 안 한 걸 했다고 말하지 않아요.
- 사용자가 "정리만 해"라고 하면 목록에 넣기만 하고, **"패치 해"**라고 할 때 한 번에 적용해요.

**게임 안 글자 규칙**
- `&l`(굵게)은 쓰지 않아요. 커스텀 폰트라 두 겹으로 보여요.
- 서버 알림 앞에는 파란 느낌표 `{e:system}`을 붙여요. 중요한 건 흰 글씨, 덜 중요한 건 `&7`이에요.
- 오류는 빨간 느낌표 `{e:error}`에 빨간 글씨예요.
- 어디에도 **영어(계정) 닉네임이 나오면 안 돼요.** 알림이든 명령어든 전부 한글 닉네임으로 처리해요.

**UI 디자인**
- 그림은 GPT로 받아요. Claude는 배치와 연결만 맡아요.
- 디자인 프롬프트를 줄 때는 좌표가 들어간 와이어프레임을 같이 줘요.

**의뢰인에게 보내는 글**
- 의뢰인은 비전공자예요. "OO는 OO해서 OO합니다" 말투로 쓰고, 이모티콘은 많이 쓰지 않아요.
- 디스코드로 보낼 글은 디스코드 마크다운으로 써요. 메시지 하나에 2000자 제한이 있어요.

**보안**
- 디스코드 봇 토큰, Velocity 비밀값, DB 비밀번호는 저장소나 로그에 올리지 않아요.
- 봇 `config.json`은 저장소에서 빠져 있어요. `config.example.json`만 있어요.

---

## 1. 저장소 구조

저장소는 `zzunwoooo/chacademi_Result`(공개)이고, 브랜치는 **`claude/kkaegul-land`**예요.

| 폴더 | 내용 |
|---|---|
| `kkaegul-core/` | 서버 코어 플러그인 **KkaegulCore** 소스 (Paper 1.21.4) |
| `kkaegul-ui-fabric/` | 클라이언트 UI 모드 **kkaegul-ui** 소스 (Fabric 1.21.4). 고화질 UI 렌더러예요. |
| `kkaegul-discord-bot/` | 디스코드 음성 채널 자동 이동 봇 (Node) |
| `kkaegul-itemsadder*/` | ItemsAdder 콘텐츠 원본(작업본) |
| `kkaegul-assets/` | UI 그림 원본 |
| `server-config/plugins/` | 실제 서버에 들어가 있는 설정 스냅샷 (KkaegulCore, TAB, ItemsAdder kkaegul, Skript, MythicMobs) |
| `docs/` | 의뢰인 요청 목록, 개발 현황 |

같은 저장소의 다른 브랜치들:

| 브랜치 | 용도 |
|---|---|
| `claude/kkaegul-ui-20261105` | UI 모드 **원래 작업 브랜치**. 저장소 루트의 `kkaegul-ui-fabric/` 폴더가 모드 소스예요. 여기에 푸시하면 CI가 jar를 빌드해요. |
| `claude/kkaegul-ui-build` | 위 CI의 결과물 (`kkaegul-ui-0.1.0+mc1.21.4.jar` + 미리보기 png) |
| `claude/kkaegul-launcher` | 깨굴랜드 런처 소스 (Helios 포크) |
| `claude/kkaegul-launcher-build` | 런처 설치 파일. 90MB씩 쪼개져 있어서 `cat *.part* > KkaegulLand-setup-1.0.0.exe`로 합치고 `SHA256.txt`로 확인해요. |
| `claude/kkaegul-launcher-distro` | 런처가 읽는 **배포 정보**(`distribution.json`)와 모드 파일 |

> **브랜치 규칙:** 이 저장소에서는 `claude/`로 시작하는 브랜치에서만 작업해요. 다른 브랜치는 GPT/Codex가 써요.
> 이 세션에서는 저장소를 새로 만들 수 없어서(권한 막힘) 이미 공개된 이 저장소에 브랜치로 올렸어요.

---

## 2. 빌드 · 배포

### 2-0. CI (이 브랜치)
- `claude/kkaegul-land`에 푸시하면 `.github/workflows/kkaegul-land.yml`이 **플러그인과 모드를 둘 다 빌드**해요.
- 결과물 `KkaegulCore-0.1.0.jar`와 `kkaegul-ui-0.1.0+mc1.21.4.jar`는 **`claude/kkaegul-land-build`** 브랜치에 올라가요. 11/08 00:04에 빌드가 성공하는 것까지 확인했어요.
- 앞으로는 **이 브랜치 하나에서 작업하는 걸 권장해요.** 예전 모드 브랜치 `claude/kkaegul-ui-20261105`는 지금 내용이 이 브랜치와 같아요(630cda8 기준). 둘 중 한쪽만 고치면 서로 달라지니 주의해요.
- 빌드 확인:
  ```
  gh api "repos/zzunwoooo/chacademi_Result/actions/runs?branch=claude/kkaegul-land&per_page=1"
  ```

### 2-1. 서버 플러그인 (KkaegulCore)
- **인터넷이 될 때:** `cd kkaegul-core && gradle build`. 결과는 `build/libs/KkaegulCore-0.1.0.jar`예요.
- **오프라인/샌드박스:** `./build-offline.sh`. `stubs/src`의 손으로 쓴 Paper API 스텁으로 컴파일해요.
  - 새 Bukkit API를 쓰면 스텁에도 그 메서드를 추가해야 해요. 예: `AsyncPlayerChatEvent.setMessage`.
  - 스크립트 안의 gson 경로(`/opt/gradle-8.14.3/lib/gson-2.10.jar`)는 환경에 맞게 바꿔요.
- **배포**
  - 사용자 PC의 서버 폴더 `C:\Users\matil\Desktop\일본\이깨굴\plugins\`에 jar를 넣어요.
  - 11/07 밤에 사용자가 **서버를 다른 곳으로 옮겼어요.** 그래서 플러그인 패치는 `plugins/` 구조 그대로 zip으로 만들어서 보내 줘요. 사용자가 그대로 덮어써요.
  - 설정만 바뀐 경우: `/깨굴 리로드`. jar가 바뀐 경우: 서버 재시작.

### 2-2. 클라이언트 UI 모드 (kkaegul-ui)
1. 이 브랜치의 `kkaegul-ui-fabric/` 폴더를 고쳐서 푸시하면, jar가 `claude/kkaegul-land-build`에 올라가요. 예전 방식은 `claude/kkaegul-ui-20261105` 브랜치에 푸시하는 것이었고, 그때는 jar가 `claude/kkaegul-ui-build`에 올라갔어요.
2. 빌드 확인:
   ```
   gh api "repos/zzunwoooo/chacademi_Result/actions/runs?branch=claude/kkaegul-ui-20261105&per_page=1"
   ```
3. jar를 깨굴랜드 런처에 배포하는 방법:
   ```
   # claude/kkaegul-launcher-distro 를 체크아웃한 폴더에서
   cp <새 jar> mods/kkaegul-ui-0.1.0+mc1.21.4.jar
   python3 tools/build_distro.py      # distribution.json 의 MD5·크기 갱신
   git commit -am "distro: ..." && git push origin HEAD:claude/kkaegul-launcher-distro
   ```
4. 런처는 실행할 때마다 `distribution.json`을 받아서 MD5가 다른 모드를 다시 내려받아요.
   - GitHub raw 캐시 때문에 푸시한 뒤 5~10분은 이전 버전이 나갈 수 있어요.
   - 사용자 PC의 `이깨굴\모드\` 폴더에도 최신 jar를 넣어 둬요.
5. **빠른 확인 (CI 없이)**
   ```
   javac -cp gson.jar -d out src/main/java/kr/kkaegul/client/ui/*.java src/test/java/kr/kkaegul/client/preview/*.java
   ```
   - ui 패키지는 마인크래프트 없이 컴파일돼요.
   - `PreviewMain.shot(...)` + `AwtCanvas`로 창을 PNG로 그려서 직접 눈으로 확인할 수 있어요.

### 2-3. 깨굴랜드 런처
- **소스:** `claude/kkaegul-launcher` (Helios 포크)
  - 이름 KkaegulLand, 데이터 폴더 `%APPDATA%\.kkaegulland`, 자동 업데이트는 꺼져 있어요.
- **서버 주소:** `211.53.196.231:31898`. 바꾸려면 distro 브랜치의 `tools/kkaegul.json`을 고치고 `build_distro.py`를 다시 돌려요. 런처를 다시 빌드할 필요는 없어요.
- `options.txt`(기본 키, 음성 V, 깨굴 메뉴 M)와 `servers.dat`(리소스팩 자동 허용)는 처음 한 번만 깔려요.
- GitHub 릴리스는 이 세션에서 만들 수 없었어요. 사용자가 직접 만들었어요.
- **주의:** 사용자 PC에는 다른 "통합런처"(`%APPDATA%\.zzunwoo`, instance `launcher_v2-1.21.4`)도 있어요. 그쪽에는 깨굴 모드가 자동 업데이트되지 않아요. 깨굴랜드 테스트는 반드시 깨굴랜드 런처(`.kkaegulland`)로 해야 해요.

### 2-4. 디스코드 봇
- 서버 PC `이깨굴\디스코드봇` 폴더에서 `start-bot.bat`으로 실행해요.
- `config.json`에는 토큰이 들어 있어서 저장소에는 없어요.
- KkaegulCore `discord.secret`과 봇 `secret`을 같게 맞춰요. 지금은 `discord.enabled: false`예요.

---

## 3. 서버 구성 (사용자 PC: `C:\Users\matil\Desktop\일본\이깨굴`)

- **Paper 1.21.4.** 월드는 `lobby`, `world`(야생), `tresure`(보물찾기, 오타 그대로), `kkaegul_mine`(보물광산, 자동 생성) 등이에요.
- **주요 플러그인**
  - KkaegulCore, ItemsAdder, ModelEngine, MythicMobs, Citizens
  - TAB, PlaceholderAPI, UnlimitedNametags (1.6.9는 creative-server 라이브러리 오류로 꺼짐 → 최신판 필요)
  - Skript, VoidGen, donation-bukkit(Colla-Bot mc-donation 2.0.2), FAWE, CoreProtect
- **클라이언트 모드(배포본):** fabric-api, sodium, iris, journeymap, plasmovoice, koreanpatch, nochatreports, authme, blurserveraddress, donation, keyeventmod, wi_zoom, kkaegul-ui
- **데이터**
  - `plugins/KkaegulCore/players/<UUID>.yml`: 개인
  - `global.yml`: 서버 공용 (게이지, 장소, 경주 멈춤 등)
  - `lands.yml`: 땅
- **위치 저장:** `/깨굴 위치 저장 <이름>`. 지금 저장된 위치는 `lobby`, `start`, `jail`, `gacha`, `wild`, `race-start`, `race-finish`, `gacha-machine.1~3`, `frog-jump-machine.1`, `invite-chair.1~4`, `treasure-start`예요. 보물광산 위치는 플러그인이 자동으로 저장해요.
  - **`village`가 아직 없어요.** 마을 맵이 나오면 저장해야 마을 안전구역(PK 금지 등)이 켜져요.

---

## 4. 코어 플러그인 핵심 개념 (kkaegul-core)

- **통신**
  - 서버 → 클라: 플러그인 채널 `kkaegul:ui`로 JSON을 보내요. 예: `{"type":"open|update|fx|taunt|land|names...","screen":..,"data":..}`
  - 클라 → 서버: `kkaegul:action`으로 보내요. 예: `{"action":"gacha.spin","machine":"2"}`
  - 모드가 없는 클라이언트는 타이틀, 액션바, 채팅으로 대신 보여줘요.
  - 자세한 건 `kkaegul-core/docs/UI_PROTOCOL.md`, `ACTIONS.md`, `UI_LIST.md`에 있어요.
- **다이아 모델**
  - "남은 다이아"(debt) 장부가 있어요. 0이 되면 졸업이에요.
  - 시작 다이아는 500~1500 랜덤이에요 (`diamond.starting-debt-min/max`).
  - 주운 다이아는 "보유 다이아"(held)라는 화폐로 들어가요. `/인출`로 꺼내고, 인벤토리가 가득 차도 흡수해요.
  - 판매가는 아이템 1개당 다이아 소수예요. 소수점은 쌓였다가 1이 되면 다이아 1개가 돼요.
- **후원 API:** donation-bukkit 이벤트로 받아요. 섭주 효과는 **OP가 받은 후원**일 때만 나가고, 나머지는 개인 API예요. 콘솔 명령 `kapi <닉> <번호>`, `ksapi <번호>`도 있어요.
- **운빨 게이지:** 서버 공용이에요.
- **명령어:** `/깨굴`(= `/kg`). 한글 하위 명령이 있고 칸마다 자동완성돼요. 한글 닉네임으로도 대상을 지정할 수 있어요.
  - `/깨굴 리로드`
  - `/깨굴 괴롭히기 확률`
  - `/깨굴 경주 멈춤|재개`
  - `/깨굴 위치 저장 <이름>`
  - `/깨굴 npc 연결 <동작>`
  - `/깨굴 게임 일정 ...`
  - `/깨굴 초기화 <닉> 장비|부동산|운세|닉네임|전부`
  - `/깨굴 전체지급 [개수]`
- **채팅 명령:** `/공지`, `/귓 <한글닉>`, `/답`, `/도움`, `/수락`. `/msg`, `/tell`, `/r` 같은 명령도 가로채서 한글 닉네임으로 보여줘요.

### 시스템별 위치
| 기능 | 파일 |
|---|---|
| 가챠 (555/999/151515) | `gacha/GachaService.java`, `Pickaxe.java` |
| 괴롭히기 (퀴즈·WASD·하늘50·피버·전체감옥·로비소환·몬스터) | `harass/*` |
| 감옥 | `jail/JailService.java` |
| 미니게임 (위험한 초대·보물찾기·빼빼로·카드뽑기) | `minigame/*` |
| 포커·섯다 (엘시 경제서버에서 이식) | `casino/holdem`, `casino/seotda` |
| 개구리 경주·점프 | `casino/FrogRaceService`, `FrogJumpService` |
| 땅 (청크 단위, 땅문서=토지 구매권) | `land/*` |
| 보물광산 (자동 생성 + 재생성) | `mine/MineService.java` |
| 닉네임·이름표 | `npc/NicknameService`, `title/TitleService` |
| 시작 장비·프로필 | `npc/StarterService` |
| 상점·판매·코인 | `economy/EconomyService` |
| 제출 (일반·랜덤) | `submit/SubmitService` |
| 안전구역 (로비·마을 PK 금지·아이템 유지·몬스터 금지) | `KkaegulCore.safeZone()` + `CoreListener` |

---

## 5. 11/07 밤에 한 패치 (요약)

전체 목록은 `docs/클라이언트-요청-대기목록-1107.md`에 있어요. ✅ 표시가 패치한 것이에요.

**가챠·아이템**
- 가챠가 더하기 대신 **나온 숫자로 바뀌어요.** 기계별 최대는 5/9/15예요.
  - 세 칸 모두 최대면 잭팟이고, 그 상태에서는 다시 못 돌려요.
  - 기계 최대보다 높은 칸이 있는 곡괭이(예: 9/15/9)도 돌릴 수 있어요. 예전에는 모드에서 버튼이 막혀서 "999 멈춤" 버그가 났어요.
- 파괴 방지권은 곡괭이가 부서지기 직전에 소모돼요.
- 콜라는 사과(APPLE)로 바뀌어서 64개씩 쌓여요.
- 보물광산
  - `mine.auto-setup`으로 빈 월드, 바닥, 벽, 스폰을 자동으로 만들어요.
  - 구역은 `treasure`(다이아만)와 `treasure_iron`(철 많음 + 다이아 조금) 중 랜덤이에요.
  - 캔 블록은 아이템이 안 떨어져요.

**괴롭히기·감옥·졸업**
- 괴롭히기 확률: WASD 35 / 퀴즈 35 / 하늘 20 / 로비소환 6 / 피버 3 / 전체감옥 1. 전체감옥 뒤에는 모두에게 랜덤 상자 1개를 줘요.
- 감옥은 또 걸리면 횟수가 쌓여요. 감옥 안 사람에게는 텔포가 막혀요.
- 졸업하면 30초 뒤 퇴장하고, 졸업 뒤에는 다이아가 늘어나지 않아요.

**미니게임**
- 위험한 초대: 대기 20초, 키 하나당 3초, 맨몸 입장(인벤토리를 맡겼다가 돌려줌), 실패하면 날아간 뒤 사망(아이템 유지).
- 개구리 경주: 10분 주기, 배팅 최대 100, 멈춤·재개 명령.
- 퀴즈 문제를 25개 늘렸어요.

**땅·마을**
- 로비·마을 안전구역.
- 남의 땅에 들어가면 주인이 표시돼요.
- 땅 회수는 막았어요.
- 최초 장비에서 곡괭이를 뺐어요.

**UI·글자**
- 모드의 `TauntView`가 놀리는 메시지를 화면 가운데에 크게 띄워요.
- 괴롭히기 타이머는 화면 아래 가운데에 나와요.
- 귓속말에는 한글 닉네임만, 탭 목록은 남은 다이아가 많은 순이에요 (TAB `%kkaegul_debt_raw%`).
- **한글 자모 쪼개짐 ("ㅈㅜㄴㅇㅜ")**
  - 원인: 모드 닉네임 창이 키를 누를 때마다 조합을 끝냈어요. 키 눌림이 글자 입력보다 먼저 오기 때문이에요.
  - 모드 쪽 수정: 위 원인을 고쳤어요. `TextFieldJamoMixin`은 채팅 같은 바닐라 입력칸에서 자모를 합쳐요. 플레이어 검색창도 자모를 합쳐요.
  - 서버 쪽 수정: `Txt.jamo()`가 채팅, 명령어, 닉네임의 자모를 합쳐요. "ㅋㅋ"처럼 자음만 있는 건 그대로 둬요.
  - `/프로필 이름`도 닉네임 규칙으로 검사해요. 이미 자모로 저장된 닉네임은 접속할 때 고쳐져요.
- 가챠·제출·상점·경주에서 서버가 거절하면 창이 바로 풀려요. 예전에는 5초 동안 "..."로 멈춰 있었어요.

**아직 실제 게임에서 확인 못 한 것** (서버를 옮겨서): 위 패치 전부. 서버 재시작 뒤 확인해야 해요.
- 보물광산 월드 자동 생성 (VoidGen)
- 위험한 초대 맨몸 입장과 아이템 복구
- 졸업 퇴장

---

## 6-A. 사용자 검수 필요 (서버 재시작 + 깨굴랜드 런처로 접속한 뒤)

11/07 밤 패치는 서버를 옮기는 바람에 **실제 게임에서 하나도 확인하지 못했어요.** 사용자(준우)가 직접 보고 OK/NG를 알려 줘야 해요.

| # | 확인할 것 | 어떻게 |
|---|---|---|
| 1 | 닉네임 창에서 한글이 쪼개지지 않는지 | 닉네임 창에 "준우" 입력 (모드 630cda8 이상) |
| 2 | 채팅·명령어에서 자모가 합쳐지는지 | 서버 jar가 바뀐 뒤 채팅으로 "ㅈㅜㄴㅇㅜ" → "준우" |
| 3 | 예전에 자모로 저장된 닉네임이 고쳐지는지 | 재접속 |
| 4 | 가챠: max를 넘는 곡괭이(9/15/9)도 돌아가는지, 세 칸 모두 max면 막히고 이유가 뜨는지 | 2번 기계 |
| 5 | 가챠 파괴 방지권이 곡괭이 부서질 때 소모되는지 | 방지권 들고 곡괭이 닳게 하기 |
| 6 | 보물광산 이동권으로 `kkaegul_mine` 월드가 만들어지고 이동되는지, 캔 블록이 안 떨어지는지 | 이동권 사용 (VoidGen 필요) |
| 7 | 위험한 초대: 맨몸 입장, 끝난 뒤 아이템 복구, 실패하면 날아간 뒤 사망(아이템 유지), 키당 3초 | `/깨굴 게임` 으로 강제 시작 |
| 8 | 졸업 30초 뒤 퇴장 + 카운트다운 | 테스트 계정 다이아 0 만들기 |
| 9 | 감옥 횟수 누적, 감옥 안 사람에게 텔포 막힘 | |
| 10 | 놀리는 메시지가 화면 가운데에 크게 나오는지, 괴롭히기 타이머가 화면 아래에 있는지 | |
| 11 | 탭 목록이 남은 다이아 많은 순인지 | `/tab reload` 후 |
| 12 | 콜라가 사과 모양·64개 묶음인지 | `/iazip` 후 |
| 13 | 로비 PK 금지, 죽어도 아이템 유지 | 마을은 `village` 위치를 저장한 뒤 |
| 14 | 개구리 경주 10분 주기, `/깨굴 경주 멈춤`·`재개` | |
| 15 | 가챠·제출·상점에서 거절됐을 때 버튼이 바로 풀리는지 | 코인 없이 돌리기 |

## 6-B. 사용자 의견·결정 필요

| # | 무엇 | 지금 상태 |
|---|---|---|
| 1 | 졸업 유예 60초 (의뢰인 답변) | 졸업 뒤 증가 무시만 적용 |
| 2 | 감옥 돌림판 규칙 (의뢰인 답변) | 안 만듦 |
| 3 | 777 랜덤 치장상자 내용 (모자/날개 아이템 목록) | 안 만듦 |
| 4 | 보물광산 2곳 따로 vs 랜덤 | 두 구역 중 랜덤 |
| 5 | 카드 뽑기 게임 규칙 (홀덤 대신) | 틀만 있고 꺼져 있음 |
| 6 | 개구리 경주 연출 (레일, 개구리 모델) | 바닐라 개구리 |
| 7 | UI 화려하게 (상자 오픈, 게이지 등) → GPT 그림 필요 | 기존 그림 |
| 8 | 야생 랜덤 장거리 텔포를 이동 메뉴에 넣을지, 비용·쿨타임 | 안 넣음 |
| 9 | 머리 위 이름표: 모드 이름표 유지 vs UnlimitedNametags 최신판 교체 | 모드 이름표 |
| 10 | `tutorial_3` 그림 교체 (곡괭이 지급이 빠졌음) | 그대로 |
| 11 | 기존 유저의 시작 다이아 300을 그대로 둘지 | 그대로 |
| 12 | 저장소 쓰기 권한을 줄 GitHub 아이디 | 없음 |
| 13 | 런처 GitHub 릴리스 (이 세션에서는 권한이 막힘) | 사용자가 직접 |

## 6. 남은 일

**의뢰인 답변 대기**
- 졸업 유예 60초 (그 안에 증가가 오면 졸업 실패). 지금은 "졸업 뒤 증가 무시"로만 해 뒀어요.
- 감옥 돌림판 (×0 ~ ×10) 규칙 설명.
- 777 랜덤 치장상자 (모자/날개 상자 → 랜덤).
- 보물광산이 2곳인지 랜덤인지. 지금은 두 구역 중 랜덤이에요.

**맵 작업 (사용자/의뢰인)**
- 마을 맵: 구역 4~6칸으로 판매. 땅 구매권을 야생에서 못 쓰게 하는 건 마을 맵이 나온 뒤에 해요.
- 감옥 뒤 덩굴 위에 투명 블록.
- 로비 명예의 전당, 도박장 분리, 가챠머신 뒤 건축.
- 보이스·땅·장비 NPC를 한곳에 모으기.

**나중에 (개발)**
- 홀덤 대신 카드 뽑기 게임. 기본 틀은 `minigame/CardDrawService`에 있고 꺼져 있어요.
- 야생 랜덤 장거리 텔포를 이동 메뉴에 넣기.
- 개구리 경주 연출 (레일, 다른 모델).
- UI 전반, 상자 오픈, 게이지 더 화려하게. 그림은 GPT로 받아요.
- 위험한 초대 실패 모션 원인 찾기. `ModelEngineHook.play`가 예외를 로그로 남기니까 로그를 확인해요.
- 인수인계 메모: 151515 졸업자 동상 세우는 방법, 칭호 주는 방법 (의뢰인 질문).

**사용자에게 안내할 것**
- `tutorial_3` 그림에 곡괭이가 그려져 있는데, 이제 곡괭이는 지급하지 않아요.
- 기존 유저의 시작 다이아(300)는 그대로예요.

---

## 7. 자주 쓰는 확인 방법

- **서버 로그:** `이깨굴\logs\latest.log`
- **사용자 게임 로그 (깨굴랜드 런처):** `%APPDATA%\.kkaegulland\instances\kkaegulland-1.21.4\logs\latest.log`
- **배포된 모드 확인:** `distribution.json`의 kkaegul-ui MD5와 `.kkaegulland\common\mods\fabric\kr\kkaegul\kkaegul-ui\...jar`의 md5를 비교해요.
- **YAML 확인:** `python3 -c "import yaml;yaml.safe_load(open('config.yml',encoding='utf8'))"`
- **config 수정:** 서버 파일을 받아서 바로 바꾸지 말고, 백업(`config.yml.bak-날짜`)을 만든 뒤 python으로 정확한 줄만 바꿔요.
