# 던전 0.3.0 반영 안내 · 주의사항 (규칙 세우기 세션용)

신기능 세션이 만든 것. **반영·재시작은 규칙 세우기 세션이 오너 승인 뒤 정기 점검 때.**

- 저장소: `zzunwoooo/chacademi_Result`, 브랜치 **`feature/dungeon-lich-20261029`**, 반영 대상 커밋 `81fd809` ~ `f8210ae` (+ 이 문서)
- 설명서: `chaca-dungeon-paper/README.md` (맨 아래 "2026-10-30 패치", "패치 2")

## 1. 지금 서버 상태 (2026-10-30 17시 확인)

| 항목 | 상태 |
|---|---|
| 학교 `plugins/chaca-dungeon-0.1.0-lich-20261029.jar` | 반영됨 (브랜치 `2053cb3` 빌드와 클래스 동일) |
| 런처 `chacademi-dungeon-0.1.0-lich-20261029.jar` | 반영됨 (distribution.json) |
| 모르바스 보스 (MythicMobs·ModelEngine·VFX `archlichmorvath`) | 반영됨 → **이번에 다시 넣지 말 것** |
| 2장 스토리 (steps.yml ch2-21·22 입장 권한) | 반영됨 → **이번에 다시 넣지 말 것** |

이 브랜치는 2장 브랜치 위에 쌓여 있어서 2장·보스 파일도 들어 있다. **브랜치를 통째로 반영하지 말고 아래 3개만.**

## 2. 이번에 반영할 것 (딱 3개)

| # | 무엇 | 어디로 |
|---|---|---|
| A | 서버 `chaca-dungeon-0.3.0-editor-20261030.jar` | 학교 `plugins/` (옛 0.1.0 jar **지우고**) |
| B | 클라 `chacademi-dungeon-0.3.0-editor-20261030.jar` | 런처 release asset + distribution.json 의 `chacademi-dungeon` 줄 교체 |
| C | 기믹 `school/chacademia/gimmick/GimmickApi.class` **한 개** | 학교 기믹 jar `chaca-gimmick-0.2.0-…-hitcontract.1.jar` 안에 **추가만** |

### 빌드된 파일 (저쪽, 2026-10-30 16:58)

```
repos\chacademi_Result\신기능\chaca-dungeon-paper\build\libs\chaca-dungeon-0.3.0-editor-20261030.jar        900995  md5 ff5a753a8ace04f7135d87828bcc6dd9
repos\chacademi_Result\신기능\client-mods\chacademi-dungeon\build\libs\chacademi-dungeon-0.3.0-editor-20261030.jar  2243614  md5 6516c7e06e91586d3755a3fcd38d0623
```

다시 빌드할 때 (저쪽은 한글 경로라 gradlew 가 안 됨 — 메모리 `build-env-jjokside`):

```powershell
$env:TEMP="C:\dgtmp"; $env:TMP="C:\dgtmp"; $env:JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=C:\dgtmp -Djdk.net.unixdomain.tmpdir=C:\dgtmp"
$g="C:\Users\Administrator\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat"
Set-Location C:\dgw;                                   & $g :chaca-dungeon-paper:build            # 서버 (+ 단위 시험 8개)
Set-Location C:\dgw\client-mods\chacademi-dungeon;     & $g build --offline                       # 클라
```
(`C:\dgw` = 신기능 폴더 연결, 샌드박스 밖에서 실행)

### C. GimmickApi 넣기 (기믹 jar 전체를 소스로 바꾸지 말 것)

```bash
# 배포된 기믹 jar 를 받아서 그 jar 기준으로 컴파일 → 클래스 하나만 추가
javac -proc:none -encoding UTF-8 -d out -cp "<paper-api 1.21.4 jar>;<adventure·gson 등>;chaca-gimmick-…-hitcontract.1.jar" gimmick-paper/src/main/java/school/chacademia/gimmick/GimmickApi.java
cp chaca-gimmick-…-hitcontract.1.jar chaca-gimmick-…-hitcontract.1.jar.before-20261030-gimmickapi
jar uf chaca-gimmick-…-hitcontract.1.jar -C out school/chacademia/gimmick/GimmickApi.class
```
- 신기능 세션이 배포 jar 기준으로 컴파일해 봄 → 오류 없음. 기존 클래스는 하나도 안 바뀜 (`// [기능:던전설정도구]`).
- 안 넣어도 던전은 돌아간다 (설정 창의 "기믹" 탭만 "연결 없음"). 넣으면 기믹 설치·조절·칸 복사 때 기믹 복사가 켜진다.

## 3. 반영 순서

1. 런처: B 를 release asset 으로 올리고 distribution.json 의 `chacademi-dungeon` 줄(url·size·MD5) 교체 → 푸시.
2. 정기 점검 시작: **유저를 서버 밖으로 먼저** (HuskSync 롤백 주의, 메모리 `husksync-restart-rollback`).
3. 학교 `plugins/`: `chaca-dungeon-0.1.0-lich-20261029.jar` **지우고** A 넣기 (같은 플러그인 이름 jar 가 두 개면 하나만 켜짐/오류).
4. 학교 기믹 jar 에 C 추가 (백업 먼저).
5. 재시작은 반드시 `hb2\dbmerge\maintenance.ps1 -Go` (메모리 `next-restart-changes`).
6. 확인 (아래 4번).

**A 와 B 는 꼭 같이.** 메시지 형식이 1판 → 3판으로 바뀌었다. 하나만 바뀌면 입장창·던전 메뉴가 안 뜬다 (클라 로그 "bad version").

## 4. 재시작 뒤 확인

콘솔 (학교):
- `기록 DB: MariaDB …` / `던전 1개 읽음` / 경고 없음
- 처음엔 `doors-ledger.yml` 이 없어서 문 관련 로그 없음 (정상)
- 시작 로그 거부·skip 점검 (메모리 `feedback-check-startup-skips`)

게임 안 (운영진):
- `/던전관리 설정도구` → 블록 좌·우클릭하면 하늘색 상자, 허공 우클릭하면 설정 창
- 설정 창 "보상·기타" 탭 맨 아래 **점검**에 칸별 빠진 것이 나오는지
- (C 넣었으면) "기믹" 탭에 모델 목록이 나오는지

## 5. 주의사항 (오류 안 나게)

1. **dungeons.yml 은 서버 것을 그대로 둘 것** (jar 안 기본 파일로 덮지 말 것). 새 형식은 자동으로 읽는다:
   - `rewards.repeat` → 기본 보상으로 읽음 (설정 창에서 한 번 저장하면 `base` 로 바뀜).
   - 첫 클리어 보상은 이제 **기본 보상에 더해서** 준다 (오너 확인 완료).
   - `natural-regen` 이 없으면 false = 던전 월드에서 배고픔으로 체력이 안 찬다 (오너 결정). 운영진도 dungeon1 월드에서는 그렇다.
2. **1번 칸 입구 방의 예전 몹 자리 12곳(spawns)·가두기(lock)는 더 이상 안 읽는다.** 반영 직후엔 입구 방에서 몹이 안 나오고 바로 "방 정리"가 끝난다.
   오너가 설정 창에서 "이 칸 비우기" → 다시 설정하기로 했다 (오너 OK). **오너가 다시 설정할 때까지 유저 입장은 막아 두는 게 안전**
   (예: 2장이 아직 ch2-21 전인 유저만 있으면 그대로, 아니면 오너에게 확인).
3. 문 = 판이 시작될 때 구역의 빈칸을 **배리어**로 채우고 끝나면 지운다. 놓은 자리는 `plugins/ChacaDungeon/doors-ledger.yml` 에 적힌다.
   서버가 갑자기 꺼져도 다음 시작 때 자동으로 걷는다. **배리어를 손으로 지우거나 ledger 파일을 지우지 말 것.** 칸이 쉬는 동안엔 문이 열려 있다 (맵 작업 방해 없음).
4. 설정 도구 = 이름이 "던전 설정 도구"인 반짝이는 **윈드 막대** (아이템 태그로 구분). 일반 윈드 막대는 영향 없음. 권한 `chacademi.dungeon.admin` (기본 OP).
5. 예전 설정 명령(`/던전관리 선택·시작점·구역·방·방몹·보스·지점·기믹·포탈·출구`)은 없어졌다 → 설정 창으로. 남은 명령: `설정도구 · 보상 · 설정확인 · 현황 · 칸초기화 · 강제퇴장` (+ 기믹 플러그인의 `소환·도구·목록·초기화`).
   기믹 플러그인과 같은 `/던전관리` 뿌리라 이름이 겹치지 않게 했다.
6. 보스 단계는 `CA_Boss_Archlich` (이미 반영됨). 몹 구역 몹 이름은 설정 창이 MythicMobs 목록에서 고르게 한다.
7. 클라 모드는 스토리 모드의 퀘스트 그림(포탈 링 등)과 MagicCodex 글꼴을 빌려 쓴다 — 둘 다 이미 설치돼 있으니 따로 할 일 없음.
8. 이 브랜치를 다른 브랜치에 합칠 때 겹칠 수 있는 파일: `settings.gradle`(include 한 줄), `NOTE.md`(맨 위 안내), `story-ch2/…/steps.yml`(이미 반영된 내용). 나머지는 새 폴더라 충돌 없음.

## 6. 확인한 것 / 못 한 것

- ✔ 서버·클라 빌드, 단위 시험 8개 (대기열·칸 배정·메시지 왕복·보상 묶음·칸 복사 이동·이름 규칙)
- ✔ GimmickApi 를 배포 기믹 jar 기준으로 컴파일
- ✔ 입장창 배치는 같은 좌표 목업으로 겹침 없음 확인
- ✘ 게임 안 확인 안 함: 설정 창 동작, 문 배리어·홀로그램, 몹 구역 소환, 칸 복사, 기믹 설치 미리보기, 입장창 새 디자인
