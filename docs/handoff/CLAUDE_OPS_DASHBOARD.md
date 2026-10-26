# 운영 대시보드 (Claude 2026-10-26, 메모 #92)

운영자가 휴대폰·PC 브라우저로 링크를 열어 **서버 상태·경보·AI 분석·콘솔·시작/정지·운영 기록 검색**을 하는 작은 웹 앱.
게임 서버와 **따로 도는 프로세스**라 게임 서버가 죽어도 열린다. 빌드·배포는 GPT 담당 — 이 문서는 소스 설명과 설치 안내.

**이 환경에서 한 것**: 대시보드는 JDK 21 `javac` 로 전부 컴파일하고, 가짜 서버(상태 핑·RCON)·가짜 로그로 띄워 로그인·상태·콘솔·RCON 명령·스크립트 명령·위험 명령 확인·정지 작업·작업 기록을 실제로 호출해 봤다. 화면은 데스크톱·휴대폰 크기로 캡처해 확인. 순수 로직 테스트 16개는 임시 JUnit 대체 실행기로 전부 통과 (호스팅 Gradle 로 다시 돌릴 것).
MagicCodexBridge 하트비트는 Paper API 가 없어 **문법 검사만** 했다 → 호스팅 빌드·테스트 필수.

## 1. 구조

```text
 휴대폰/PC 브라우저 ──(비밀번호 + 세션 쿠키)──▶ ops-dashboard (호스팅 PC, java -jar, 포트 8470)
                                                   │
       ┌───────────────────────────────────────────┼──────────────────────────────┐
       │ 5초마다 상태 핑(게임 접속 포트)              │ 5초마다 SELECT 1번              │ 1초마다 latest.log 끝만 읽기
       ▼                                           ▼                                ▼
  프록시·학교·야생·아레나 (켜짐/꺼짐·접속자)     공유 MariaDB codex_server_status   C:/Chacademi/network/<서버>/logs/latest.log
                                                   ▲  (10초마다 서버별 1줄 덮어쓰기)
                                    MagicCodexBridge 하트비트 (각 Paper 서버, 비동기 스레드)

  버튼: 명령 = RCON(서버별 포트·비밀번호) 또는 감독 스크립트 / 시작·정지·재시작 = 설정한 Windows 명령(Control.ps1·Stop-Network.ps1)
        AI 분석 = OpenAI Responses API (ChacaNPC 와 같은 키) / 운영 기록 = codex_audit_log 읽기 / 작업 기록 = data/ops-actions.log
```

**왜 이 방법인가**: JDK 내장 `HttpServer` + MariaDB JDBC(이미 저장소가 쓰는 3.5.10) 하나. 프레임워크·Node·외부 CDN 없음 → 호스팅 PC 에 Java 21 만 있으면 되고(이미 있음), 화면(HTML·CSS·JS)은 jar 안 한 파일이라 인터넷이 끊겨도 열린다. 게임 플러그인 안에 웹 서버를 넣지 않은 이유: 서버가 멈추거나 꺼지면 대시보드도 같이 죽기 때문 (오너 조건 "서버가 죽어도 열려야 함").

## 2. 게임 서버 렉 영향 (오너: "렉 절대 안 됨")

| 무엇 | 게임 서버가 하는 일 | 메인 스레드 비용 |
|---|---|---|
| 하트비트 (Bridge) | 10초마다 메인 스레드에서 숫자 몇 개(TPS 배열·평균 틱·접속자 수·월드별 청크/엔티티 **개수**)만 읽고, spark 조회·CPU·DB 쓰기는 **전용 스레드 1개**(최저 우선순위). 앞 쓰기가 안 끝났으면 이번 회차는 건너뜀 (DB 가 느려도 쌓이지 않음). DB 연결도 그 스레드에서 처음 연다 | 수 마이크로초 / 10초 |
| 상태 핑 | 5초마다 서버 목록 핑 1번 = 접속 화면 새로고침과 같은 것, 네트워크 스레드 처리 | 0 |
| 로그 | 대시보드가 파일만 읽음 (열고 바로 닫음, Windows 이름 바꾸기 허용) | 0 |
| 콘솔 명령 | 버튼을 누를 때만 그 명령 1개 (RCON 은 바닐라 방식대로 메인 스레드에서 그 명령만 실행) | 명령 자체 |
| spark 60초 | 버튼을 누를 때만. spark 프로파일러는 샘플링이라 가벼움 | 아주 조금 |

`world-counts: false` (server-heartbeat.yml) 로 청크/엔티티 개수 세기도 끌 수 있다.
대시보드 프로세스: `-Xmx256m -XX:+UseSerialGC`, 대부분 잠자는 스레드.

## 3. 빌드 (호스팅)

```powershell
cd C:\Chacademi\build-workspace
.\gradlew.bat --no-daemon --offline :ops-dashboard:test :ops-dashboard:jar
# 결과: ops-dashboard\build\libs\chacademi-ops-dashboard-0.1.0.jar (MariaDB 드라이버 포함, Main-Class 있음)
.\gradlew.bat --no-daemon --offline :magic-codex-paper:test :magic-codex-paper:build   # 하트비트가 든 Bridge
```

`settings.gradle` 에 `include 'ops-dashboard'` 를 넣어서 루트 `verifyServer`/`assembleServer` 에도 포함된다 (Paper 의존 없음). `Build-Server.ps1` 은 4개 플러그인만 artifacts 로 복사하므로 대시보드 jar 는 손으로 복사한다.

## 4. 설치 순서 (호스팅, GPT)

1. **폴더**: `C:\Chacademi\ops-dashboard\` 만들고 `chacademi-ops-dashboard-0.1.0.jar`, `ops-dashboard/scripts/Start-OpsDashboard.ps1`, `ops-dashboard/scripts/Install-OpsDashboardTask.ps1` 복사.
2. **설정**: `ops-dashboard/ops-dashboard.properties.example` → `C:\Chacademi\ops-dashboard\ops-dashboard.properties` 로 복사하고 ★ 값 채우기 (§6). **경로는 `/` 로** (이 형식에서 `\` 는 사라짐). 파일 권한은 실행 계정·관리자만 읽게.
3. **비밀번호**: 오너가 정한 비밀번호를 환경변수 `CHACA_OPS_PASSWORD` (실행 계정 사용자 변수, `[Environment]::SetEnvironmentVariable('CHACA_OPS_PASSWORD','…','User')`) 또는 설정 파일 `password.sha256=` (`java -jar chacademi-ops-dashboard-0.1.0.jar --hash 비밀번호` 출력값). 평문 `password=` 도 되지만 비권장. 8자 이상.
4. **RCON** (명령 보내기를 RCON 으로 할 때): 각 Paper 서버 `server.properties`
   ```properties
   enable-rcon=true
   rcon.port=25576          # 학교. 야생 25577, 아레나 25578 … 서버마다 다르게 (★)
   rcon.password=<긴 임의 값>  # 서버마다 다르게, 대시보드 설정/환경변수 CHACA_OPS_RCON_SCHOOL 등과 같게
   broadcast-rcon-to-ops=false
   ```
   백엔드가 `server-ip=127.0.0.1` 이면 RCON 도 127.0.0.1 에만 열린다 (확인). **RCON 포트는 방화벽·공유기로 절대 열지 않는다.** 서버 재시작해야 적용.
   RCON 을 안 쓰고 기존 감독 콘솔(`Control.ps1 -Action Console`)로 보내려면 `server.<id>.console=script` (설정 예시에 줄 있음). Velocity 는 RCON 이 없어 `script` 또는 `none`.
5. **Bridge 하트비트 배포**: 새 MagicCodexBridge jar 를 학교·야생·아레나(+튜토리얼) `plugins/` 에. 처음 켜지면 `plugins/MagicCodexBridge/server-heartbeat.yml` 이 생긴다. `server-id` 를 비워 두면 서버 폴더 이름(school·wild·arena·tutorial…)이 되고, 대시보드 `servers=` 이름과 같아야 한다. 확인: `/서버관리 상태` 에 "하트비트 server_id=school · N초 전 기록 (spark|bukkit)" 줄. 각 서버 `database.properties` 가 공유 MariaDB 여야 켜진다.
6. **spark**: Paper 1.21.4(Purpur 2416)에 spark 가 내장돼 있으면 그대로, 아니면 spark 플러그인(학교·야생·아레나, 프록시는 Velocity 용) 설치. 하트비트는 spark 가 없으면 Bukkit TPS/평균 틱 + JVM CPU 로 자동 대체 (`source` 칸 `bukkit`).
7. **방화벽**: 관리자 PowerShell
   ```powershell
   New-NetFirewallRule -DisplayName 'Chacademi Ops Dashboard' -Direction Inbound -Protocol TCP -LocalPort 8470 -Action Allow
   ```
   집 밖(휴대폰 LTE)에서 열려면 공유기 포트포워딩 8470 → 호스팅 PC (호스팅 업체 방화벽이 있으면 그쪽도). 링크 = `http://211.53.196.231:8470/` (★ 실제 주소·포트).
8. **자동 시작**: `pwsh -NoProfile -File C:\Chacademi\ops-dashboard\Install-OpsDashboardTask.ps1` → 계정 비밀번호를 한 번 물음 → 작업 `Chacademi-OpsDashboard` (PC 켜지고 30초 뒤, 로그온 없이, 실패 시 1분마다 재시도, 시간 제한 없음). 지금 켜기: `Start-ScheduledTask -TaskName Chacademi-OpsDashboard`. `Start-OpsDashboard.ps1` 이 대시보드가 죽으면 10초 뒤 다시 켠다. 실행 계정은 게임 예약 작업 `Chacademi-Network` 와 같은 `chacademi-codex` 권장 (시작·정지 스크립트 권한·로그 파일 읽기 권한이 같아야 함).
   - Windows 서비스로 하고 싶으면 NSSM/WinSW 로 `pwsh -File Start-OpsDashboard.ps1` 을 감싸면 되지만, 추가 프로그램이 필요해 작업 스케줄러를 기본으로 했다.
   - 작업 우선순위는 일부러 "보통": Windows 는 "보통 미만" 프로세스가 띄운 자식에 그 우선순위를 물려주므로, 시작 버튼으로 띄운 것이 느려지지 않게.
9. **확인**: 브라우저로 링크 → 비밀번호 → 상태 카드가 초록 "켜짐", TPS·MSPT 숫자가 보이면 끝. 문제 보기: `C:\Chacademi\ops-dashboard\data\dashboard-out.log`·`dashboard-err.log`·`dashboard-run.log`. `http://127.0.0.1:8470/healthz` → `ok`.

HTTPS (선택): PKCS12 인증서를 `tls.keystore`·`tls.password` 로 주면 HTTPS 로 연다 (쿠키에 Secure). 오너 결정은 "링크+비밀번호면 충분"이라 기본은 HTTP — 공용 와이파이에서 쓰면 비밀번호가 평문으로 갈 수 있다는 점만 오너에게 알려 둠.

## 5. 설정 항목 (`ops-dashboard.properties`, 전체 예시는 `ops-dashboard/ops-dashboard.properties.example`)

| 키 | 기본 | 뜻 |
|---|---|---|
| `http.bind` / `http.port` | 0.0.0.0 / 8470 | 웹 주소 |
| `password` · `password.sha256` | (없음) | 비밀번호. 환경변수 `CHACA_OPS_PASSWORD` 가 먼저 |
| `session.hours` | 12 | 로그인 유지 |
| `login.max-failures` / `login.lock-minutes` | 5 / 15 | IP 마다 틀림 횟수 제한. 전체로 10분에 50번 틀리면 잠깐 모두 잠금. 틀릴 때마다 0.6초 지연 |
| `http.trust-forwarded-for` | false | 앞에 리버스 프록시를 둘 때만 |
| `tls.keystore` / `tls.password` | (없음) | HTTPS |
| `data-dir` | data | 작업 기록 폴더 (설정 파일 옆) |
| `db.properties-file` | — | **게임 서버 database.properties 를 그대로 읽기 (권장)** |
| `db.host/port/name/user/password` | — | 위가 비었을 때. 비밀번호 환경변수 `CHACA_OPS_DB_PASSWORD` |
| `db.status-table` | codex_server_status | 하트비트 표 |
| `servers` | — | 화면 순서. 예 `proxy,school,wild,arena` |
| `server.<id>.label/type/host/port/log/heartbeat-id` | id / paper(proxy·velocity 는 proxy) / 127.0.0.1 / 25565 / — / id | 서버별 |
| `server.<id>.console` | paper=rcon, proxy=none | rcon · script · none |
| `server.<id>.rcon.port/rcon.password` | 25575 / — | 비밀번호 환경변수 `CHACA_OPS_RCON_<ID>` |
| `server.<id>.console.script` | — | `{server}` `{command}` 가 인자 하나로 바뀜 |
| `server.<id>.start/stop/restart` | — | 비우면 그 버튼이 안 나옴. restart 가 비면 stop → 꺼짐 확인 → 3초 → start |
| `server.<id>.spark-command` | paper `spark profiler start --timeout 60`, proxy `sparkv profiler start --timeout 60` | spark 버튼 |
| `control.script-timeout-seconds / stop-wait-seconds / start-wait-seconds` | 180 / 180 / 300 | 조작 대기 |
| `control.output-charset` | Windows 기본(MS949) | 스크립트 출력 글자 |
| `alarm.tps-below / mspt-above / heap-above-percent / heartbeat-stale-seconds / errors-per-minute` | 15 / 50 / 90 / 30 / 10 | 규칙 경보 |
| `alarm.host-cpu-above-percent / host-memory-above-percent` | 95 / 95 | 호스팅 PC (노란 경보) |
| `ai.enabled / api-key / base-url / model / reasoning-effort / max-output-tokens` | true / — / OpenAI / gpt-6-luna / low / 900 | 키: `CHACA_OPS_OPENAI_KEY` → `CHACANPC_OPENAI_KEY` → 파일 |
| `ai.max-per-day` | 40 | 하루 횟수(버튼+자동) |
| `ai.auto-on-alarm / auto-min-interval-minutes` | false / 15 | 새 빨간 경보 때 자동 분석 (기본 꺼짐 = 버튼만) |
| `audit.table / audit.col.* / audit.time-unit / audit.max-rows` | codex_audit_log / 아래 / auto / 300 | 운영 기록 검색 |
| `spark.link-pattern` | `https://spark\\.lucko\\.me/[A-Za-z0-9]+` | 로그에서 결과 링크 찾기 |
| `timezone` / `host.disk-path` | Asia/Seoul / C:/Chacademi | |

`audit.col.*` 기본: time=`created_at`, server=`server_id`, uuid=`player_uuid`, name=`player_name`, kind=`kind`, message=`summary`, detail=`detail`. **감사 로그 표를 만드는 작업과 열 이름이 다르면 설정만 바꾸면 된다** (없는 열은 빈칸). 시각 열이 BIGINT 면 밀리초/초를 값 크기로, DATETIME/TIMESTAMP 면 그대로 알아챈다.

## 6. GPT 가 채울 값

| 값 | 어디 | 메모 |
|---|---|---|
| 대시보드 비밀번호 | 환경변수 `CHACA_OPS_PASSWORD` (오너에게 받기) | 8자 이상 |
| 공개 포트·주소 | `http.port`, 방화벽, 공유기 | 기본 8470. 오너에게 링크 전달 |
| DB | `db.properties-file` 경로 확인 (학교 Bridge database.properties) | 읽기 전용 계정을 따로 만들면 `db.*` 로 |
| 프록시 로그 경로 | `server.proxy.log` | 예시는 `C:/Chacademi/network/velocity/logs/latest.log` |
| 아레나 게임 포트 | `server.arena.port` | 예시 25568 (★ network.json 확인) |
| RCON 포트·비밀번호 | 각 server.properties + `server.<id>.rcon.*` / `CHACA_OPS_RCON_<ID>` | 또는 console=script |
| 프록시 시작/정지 명령 | `server.proxy.start/stop` | 프록시만 켜고 끄는 스크립트가 있으면. 없으면 비움 (버튼 안 나옴) |
| 감독 콘솔 명령 형식 | `server.<id>.console.script` | `Control.ps1 -Action Console -Server {server} -Command {command}` 그대로 되는지 |
| OpenAI 키 | `CHACA_OPS_OPENAI_KEY` 또는 기존 `CHACANPC_OPENAI_KEY` 를 실행 계정에도 | 모델명은 ChacaNPC config 와 같게 |
| 감사 로그 열 이름 | `audit.col.*` | codex_audit_log 표가 생기면 맞추기 |
| 튜토리얼 서버 | `servers=` 에 추가 + 블록 주석 해제 | 서버를 만든 뒤 |

## 7. 사용법 (화면)

- **상태**: 위에 빨간 "지금 문제" (규칙 경보: TPS<15·MSPT>50·힙>90%·하트비트 30초 끊김·ERROR 1분 10줄 이상·꺼짐·DB 실패; 노란 줄 = 호스팅 PC CPU/메모리, DB 설정 없음). 탭 제목에도 경보 수. 서버 카드 = 상태(켜짐/꺼짐/응답 없음)·TPS·MSPT·CPU·메모리·접속자·켜진 지·10분 그래프.
  - "응답 없음" = 접속 포트는 살아 있는데 하트비트가 끊김 → 메인 스레드가 멈췄을 가능성 (또는 Bridge/DB 문제).
- **spark 60초**: 확인 후 `spark profiler start --timeout 60` 를 보낸다. 끝나면 spark 가 콘솔에 올린 결과 링크를 로그에서 찾아 카드에 보여 준다.
- **AI 분석**: "전체 분석" 또는 카드의 "AI 이 서버". 하트비트 값·1시간 추이·경보·최근 경고/오류 로그 줄·spark 링크를 보내 "어디에서 문제가 발생했습니다 / 근거 / 지금 할 일" 을 받는다. 하루 횟수 표시. 키가 없으면 버튼이 꺼진다 (규칙 경보는 그대로).
- **콘솔**: 서버 고르기 → latest.log 실시간 (1.5초), "경고·오류만" 거르기, 명령 입력. `stop`·`op`·`ban`·`lp`·`whitelist`·`reload`·`kill`·`execute`… 같은 위험 명령은 한 번 더 확인.
- **시작·정지·재시작**: 시작은 확인 1번, 정지·재시작은 **서버 이름을 그대로 적어야** 실행 (프록시는 "모든 접속자 끊김" 경고). 진행 상황·스크립트 출력이 카드에 나오고, 켜짐/꺼짐을 상태 핑으로 확인한다. 서버마다 한 번에 하나.
  - **정지는 반드시 `Stop-Network.ps1 -Server <id>`** (설정 예시대로). `Control.ps1 -Action Stop` 은 `-Server` 를 무시하고 프록시까지 전부 끈다 (LIVE_SOURCE_HANDOFF 주의).
- **운영 기록**: 닉네임(또는 UUID)·종류·서버·기간·글자로 `codex_audit_log` 검색, 최근 300건까지 표 (휴대폰은 카드 모양).
- **작업 기록**: 대시보드에서 누른 것 (로그인 성공/실패·명령·spark·시작/정지·AI·검색) = 시각·IP·작업·대상·내용·결과. 파일 `data/ops-actions.log` (탭 구분).

## 8. 하트비트 표 `codex_server_status` (Bridge 가 자동 생성)

| 열 | 뜻 |
|---|---|
| `server_id` PK | server-heartbeat.yml server-id (기본 서버 폴더 이름) |
| `state` | running / stopped (플러그인 끌 때 마지막으로 stopped 를 씀) |
| `updated_at` · `started_at` | epoch 밀리초 |
| `source` | spark / bukkit |
| `tps_10s, tps_1m, tps_5m, tps_15m` | TPS (bukkit 이면 10s = 1분 값) |
| `mspt_mean, mspt_max, mspt_p95` | 최근 10초 틱 시간 ms (bukkit 이면 평균만, 나머지 -1) |
| `cpu_process, cpu_system` | % (spark 10초, 없으면 JVM) |
| `heap_used_mb, heap_max_mb` | 힙 |
| `online, max_players, worlds, chunks, entities` | 개수 (world-counts=false 면 -1) |
| `version` | 서버 이름·버전 |

-1 = 모름. 서버마다 한 줄을 덮어쓰므로 표가 커지지 않는다. 추이(1시간)는 대시보드 메모리에만 있다 (대시보드 재시작하면 처음부터).

## 9. 보안 정리

- 비밀번호 하나 + 세션 쿠키(HttpOnly·SameSite=Strict, 12시간), IP 별 5번 틀리면 15분 잠금, 전체 10분 50번 상한.
- 모든 버튼(POST)은 `X-Ops: 1` 헤더가 있어야 받음 + SameSite 쿠키 → 다른 사이트가 몰래 버튼을 누르게 하는 요청(CSRF) 차단. 화면은 CSP·X-Frame-Options DENY.
- 비밀값(비밀번호·RCON·DB·OpenAI 키)은 환경변수 또는 저장소 밖 설정 파일. 작업 기록·로그에 비밀값을 쓰지 않는다. 화면에 DB 주소·비밀번호를 보여 주지 않는다.
- SQL: 표·열 이름은 설정에서 `[A-Za-z0-9_]` 만 허용, 검색 값은 전부 `?` 인자.
- 대시보드는 **읽기 + 설정한 명령 실행**만. DB 에 쓰지 않는다.

## 10. 파일

| 파일 | 내용 |
|---|---|
| `ops-dashboard/build.gradle` | 모듈 (Main-Class, MariaDB 드라이버 포함, `magic-codex-database` 소스 공유) |
| `ops-dashboard/src/main/java/school/chacademi/ops/` | `OpsDashboard`(시작) · `Config` · `Auth` · `Web`(HTTP·API) · `Monitor`(핑·하트비트·추이) · `Alarms`(규칙) · `LogTail` · `Rcon` · `McPing` · `Control`(명령·시작/정지) · `AiAnalyst` · `AuditSearch` · `ActionLog` · `Db` · `Json` (ChacaNPC 것 복사) |
| `ops-dashboard/src/main/resources/ops/index.html` | 화면 한 파일 (CSS·JS 인라인, 외부 CDN 없음, 다크·모바일) |
| `ops-dashboard/src/test/java/school/chacademi/ops/OpsLogicTest.java` | 순수 로직 16개 |
| `ops-dashboard/ops-dashboard.properties.example` | 설정 예시 (★ = 채울 값) |
| `ops-dashboard/scripts/Start-OpsDashboard.ps1` · `Install-OpsDashboardTask.ps1` | 실행·자동 시작 등록 |
| `magic-codex-paper/.../ServerHeartbeat.java` · `SparkProbe.java` · `resources/server-heartbeat.yml` | Bridge 하트비트 |
| `magic-codex-paper/src/test/.../ServerHeartbeatTest.java` | 하트비트 순수 로직 |

## 11. 확인 못 함 / 확인 필요

- **spark API 리플렉션**: `me.lucko.spark.api.Spark` 의 `tps()/mspt()/cpuProcess()/cpuSystem()` 와 `StatisticWindow$TicksPerSecond.SECONDS_10` 등 이름을 기억으로 적었다. 실제 서버에서 `/서버관리 상태` 의 하트비트 줄이 `(spark)` 인지 확인. 못 읽으면 로그 한 줄 남기고 `(bukkit)` 으로 동작 (기능은 유지).
- **Paper 내장 spark** 여부 (Purpur 2416): 내장이면 서비스 등록/클래스로더로 찾게 했지만 실제로 잡히는지 미확인.
- **spark 결과 링크가 latest.log 에 찍히는지**: spark 는 끝날 때 결과를 명령 보낸 쪽 + 권한 있는 쪽(콘솔 포함)에 알리는 것으로 알고 있다. RCON 으로 시작하면 RCON 연결은 이미 닫혀 있으므로 콘솔(로그)에 찍혀야 링크가 잡힌다. 안 찍히면 콘솔 탭에서 직접 `spark profiler stop` 등으로 확인.
- **감독 스크립트 콘솔**(`console=script`): `Control.ps1 -Action Console` 이 한글 명령·긴 명령을 그대로 넘기는지 (예전 한글 깨짐은 감독기 stdin UTF-8 로 고쳐졌다고 기록됨). 출력 글자 인코딩은 `control.output-charset`.
- **재시작**: restart 명령을 안 주면 stop → 상태 핑이 꺼질 때까지 → 3초 → start. 감독 프로세스가 "비정상 종료"로 보고 스스로 다시 켜는 경우 start 와 겹칠 수 있다 → Stop-Network 로 끈 서버를 감독기가 다시 켜지 않는지 확인 (켜면 restart 칸에 감독기용 명령을 따로 넣기).
- **프록시**: Velocity 는 하트비트가 없어 상태 핑(켜짐·전체 접속자·응답 시간)만. CPU·메모리는 호스팅 PC 전체 값으로 본다. 프록시 시작/정지 스크립트 이름은 모름 (★).
- **codex_audit_log 열 이름**: 다른 작업이 만드는 중이라 추측 (§5). 다르면 설정만 고침.
- **AI 모델 이름** `gpt-6-luna` 는 ChacaNPC 설정 값을 따름. reasoning 을 안 받는 모델이면 자동으로 빼고 한 번 더 보냄.
- 힙 90% 경보는 G1 이 늦게 비우는 서버에서 잠깐씩 뜰 수 있다 → 자주 뜨면 `alarm.heap-above-percent` 를 95 로.
