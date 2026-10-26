# 고화질 머리 위 명찰 구현·검증

운영 배포 없이 SSH 호스트에서 구현과 빌드·회귀 검증을 완료했다. 검토용 후보이며 실제 인게임 시각 QA는 남아 있다.

## 소스 및 보존

- 요청 저장소: `zzunwoooo/chacademi-magiccodex-source`.
- 전용 호스트 작업 트리: `C:/Chacademi/staging/nameplates-code-20261022-task7`.
- 브랜치: `feat/hires-nameplates-20261022`.
- 실제 공개 main `e2d00d79`는 초기 공개 소스 이력이며 현재 통합 브랜치와 unrelated history였다. 최신 통합 인계 `claude/fixes-integrated-story-20261021`의 `e73ddb26`을 기능 기준으로 선택했다.
- 검증된 NPC 보존 후보 `7ff347ff`를 통합한 `8ee4304b`, 현행 Story/채팅 소스 작업 트리의 `2e4e48a1`까지 보존한 `f58dd022`가 이번 명찰 변경 직전 기준이다. 병합 충돌은 인계 인덱스 문서 한 파일에만 있었으며 현행 문서 기준으로 정리했다.
- 기존 build-workspace 및 다른 작업 트리의 미커밋 변경을 보존했다. 운영 DB·설정·JAR·런처·실제 클라이언트를 수정하거나 서버를 중지/재시작하지 않았다.
- Story 클라이언트 `0.1.4-quest.4-visual.1`, 채팅 `1.6.0-commands.1` 소스를 보존했다. 해당 모듈·툴팁·UI 스타일/레이아웃은 명찰 변경 diff에 없다.
- 별도 NPC 모델 작업 `5cd4e26 / 0.1.1-npcmodel.2` 작업 트리와 배포물을 변경/빌드/게시하지 않았다.

## 구현

- 서버 S2C 전용 `magiccodex:nameplates` 전체 스냅샷. 플레이어 기숙사는 `SchoolBridge.house`를 사용하는 `questHouse`, 닉네임은 기존 `DisplayNames` 권위 데이터를 재사용한다. 클라이언트 입력 경로가 없다.
- 주변 48블록, 가장 가까운 최대 64개, 초당 1회, 최대 30,000바이트. 엔티티 네트워크 ID와 UUID를 함께 확인한다. 자기 자신은 서버와 클라이언트에서 제외한다. 연결·월드 전환·리로드에서 초기화하며 3초 이상 갱신되지 않으면 바닐라로 복귀한다.
- 소속 없는 플레이어와 미설정 NPC는 기존 이름표를 유지한다. 실제 대체 데이터와 정상 리소스가 있는 대상만 기존 TamingNameMixin 경로에서 바닐라 이름을 숨긴다. 기존 펫 이름 숨김 동작을 보존했다.
- 전체 너비 1.1블록의 원근 빌보드. 원본 종횡비 유지, 교수는 2046 너비를 사용한다. 발 위치는 `getLerpedPos(tickDelta)`, 머리 높이는 플레이어/엔티티 실제 높이이며 배경 하단을 머리 위 0.10블록 이상에 둔다. 위치를 별도 틱으로 누적하거나 보간하지 않는다.
- 선택적 모델 연동: 실제 `5cd4e26` 소스에서 확인한 `kr.chacademi.npcmodel.NpcModelClient.modelTopHeight(Entity)` 정적 API를 리플렉션으로 호출한다. 양수·유한 높이만 채택하며 없으면 엔티티 높이를 사용한다. 필수 모드 의존 또는 모델 모드 번들링은 없다.
- 실제 Minecraft 1.21.4 바닐라 LivingEntityRenderer/EntityRenderer 바이트코드로 정책을 대조했다. 자기 자신/현재 카메라 대상, 숨긴 HUD, 사망, 투명, 관전자, 탑승자가 있는 엔티티를 숨긴다. 팀 NEVER/HIDE_FOR_OTHER_TEAMS/HIDE_FOR_OWN_TEAM을 반영하고 팀 없는 관찰자 규칙도 보존한다. 웅크림은 32블록 미만, 일반은 48블록까지다.
- 벽 가림은 깊이 테스트가 적용된다. 바닐라의 옅은 벽 투과 패스를 사용하지 않는 의도적 정책이다. 웅크림 명찰도 벽 너머로 보이지 않는다.
- 기존 `HudTextureCache`의 알파 premultiply·밉맵·삼선형 필터·비동기 디코딩을 재사용한다. `CodexTypography` 및 `CodexTextRasterizer`의 기존 Pretendard Bold 실제 폰트 측정·고해상도 래스터 캐시를 재사용한다.
- 이름 안전영역은 원본 픽셀 x=660..1830, y=205..505. 이름은 전체 그림이 아닌 오른쪽 사각형 중심(약 x=1245)에 정렬한다. 학생 기본 250px, 교수 210px, 직책 96px이며 실제 글꼴 advance에 맞춰 축소한다. 이름 최대 너비 1100px, 직책 1080px. UTF-16 문자 수로 크기를 정하지 않는다.

## 관리자 명령

기존 MagicCodex 명령 허브에 `/NPC관리 명찰` 가지를 추가했다. 기존 루트의 OP 정책과 `magiccodex.nameplates.admin` 권한(default op)을 적용한다. 인게임 관리자만 사용하며 구형 NPC관리 명령을 재등록하지 않는다.

```text
/NPC관리 명찰 설정 근처 "엘레나" "교수"
/NPC관리 명찰 설정 "기존 NPC 이름 또는 명찰 이름" "표시 이름" "직책"
/NPC관리 명찰 조회 근처
/NPC관리 명찰 조회 "이름 또는 후보 ID"
/NPC관리 명찰 초기화 "이름 또는 후보 ID"
```

직책을 비우려면 `""`를 사용한다. 설정은 표시 이름과 직책을 함께 저장한다. 대상·이름·직책에 공백이 있으면 따옴표로 묶는다. 대상 자동완성에 `근처`, 원래 이름, 설정한 이름, 영속 ID가 나온다.

- 대상은 같은 월드의 24블록 구 안에 있어야 한다. `근처`는 최단거리 NPC이며 동률이면 후보를 반환한다. 이름/닉네임 중복은 임의 선택하지 않고 후보 ID를 안내한다.
- 이름 1~48 Unicode code point, 직책 0~24 code point. 잘못된 surrogate·제어·방향 제어·서식 문자를 거부한다. 부적절한 길이·대상 없음·권한·저장 실패를 명확히 안내한다.
- Citizens NPC는 ChacaNPC의 영속 `chacanpc-id`를 우선 사용한다. 없다면 Citizens 고유 UUID, 그 외 명시적 NPC(`NPC` 메타데이터 또는 `magiccodex_npc` 태그)는 엔티티 UUID에서 발급한 영속 PDC `magiccodexbridge:nameplate_id`를 사용한다. 일반 몹을 임의로 NPC로 간주하지 않는다.
- 설정은 Bridge 데이터 폴더의 `nameplates.yml`에 원자 교체 방식으로 저장한다. 실패 시 메모리·이전 파일을 유지한다. 재접속·청크 언로드·재시작은 안정된 키로 복원된다. ChacaNPC 캐릭터 연결을 유지한 재생성은 엔티티/Citizens UUID가 바뀌어도 유지된다. 캐릭터 키 없는 NPC를 삭제 후 새로 생성하거나 일반 몹 PDC를 잃으면 동일성을 자동 추정하지 않으며 재설정 또는 명시적 PDC 복원이 필요하다.
- 잘못된 저장소는 원본을 덮어쓰지 않고 명찰 초기화만 실패시켜 기존 이름표로 폴백한다. 나머지 MagicCodex 서비스를 중단하지 않는다.

## 리소스 및 검증

승인된 Library 원본 5종과 배치 레퍼런스를 호스트에 직접 내려받았다. 원본 디자인 변경/재제작/알파 평탄화 없이 패키징했다. 학생 2048×768, 교수 2046×768, RGBA8. 학생 실제 최대 알파는 254, 교수는 255이며 모두 최소 알파 0이다. 안전영역 최소 알파 251~252. 원본·소스·JAR 내부 PNG SHA256이 일치한다. 상세 수치/해시는 `asset-audit.json`과 `verification.json`에 있다.

호스트 산출물: `C:/Chacademi/artifacts/nameplates-20261022-task7`.

| 모듈 | 테스트 | 실패/오류 | 건너뜀 |
|---|---:|---:|---:|
| MagicCodexBridge | 446 | 0 | 2 |
| MagicCodex Fabric | 452 | 0 | 0 |
| ChacaNPC | 31 | 0 | 0 |
| Housing | 9 | 0 | 0 |
| 현행 Story plugin | 81 | 0 | 0 |
| 합계 | 1019 | 0 | 2 |

기존 실제 DB 조건부 테스트 2개만 건너뛴다. 명찰 신규 21개 테스트는 패킷 손상·Unicode 길이·최악 패킷 크기, 실제 Citizens API 기반 영속 키/UUID 변경/중복 대상, 권위 기숙사·닉네임과 자기 제외, PDC 복원, 저장 실패/손상 파일, 권한, 실제 기존 글꼴의 폭과 팀/거리/가시성 정책을 포함한다.

실제 Bridge JAR와 remap된 Fabric JAR를 서로 다른 ClassLoader에 로드해 동일 Unicode 스냅샷 교차 decode·동일 바이트 encode를 검증했다. `protocol-compatibility.txt` 참조.

빌드 명령(호스트 Java 21):

```powershell
# 저장소 루트
./gradlew.bat --no-daemon --console=plain --max-workers=2 :magic-codex-paper:test :magic-codex-paper:jar :chaca-npc-paper:test :housing-paper:test
# magic-codex-fabric
../gradlew.bat --no-daemon --console=plain --max-workers=2 test build
# chacademy-story/plugin
../../gradlew.bat --no-daemon --console=plain --max-workers=2 test jar
```

최종 로그는 호스트 `C:/Chacademi/staging/nameplates-20261022-task7/server-final-2.log`, `server-final.log`, `client-final.log`, `story-regression.log`이며 JUnit XML은 각 모듈 build/test-results/test에 있다. 첫 서버 실행의 시작 모의 경계 누락 3건은 테스트에 새 서비스를 포함해 수정했다. 실제 Citizens API 시그니처도 런타임 JAR로 대조했다. 최종 빌드에서 실패 없음. 기존 Gradle deprecation, SnakeYAML semver 경고는 남아 있다.

`layout-preview.png`는 실제 원본 PNG와 실제 기존 폰트의 CPU 합성 확인용 이미지이며 게임 화면이 아니다. 원본 알파/이름 영역과 긴 이름 축소를 눈으로 확인했다.

## 배포 전 남은 검증

이 결과는 운영 설치/재시작/런처 게시/실클라이언트 교체를 포함하지 않는다. 새 JAR 두 개를 호스트 검토 폴더에만 준비했다.

운영 반영을 별도 승인받을 때 Bridge와 MagicCodex UI의 새 프로토콜을 함께 반영해야 한다. 이 후보는 최신 통합 수정/Story 보존 기준을 포함하므로 실제 운영 모듈·overlay가 그 통합 기준과 일치하는지 먼저 비교해야 한다. 이전 인계가 요구하는 MagicMythic/PortableVFX/overlay 동시성도 재확인하고, 본 작업을 근거로 무관한 모듈을 자동 교체하지 않는다. NPC 모델 0.1.1-npcmodel.2와 Story/채팅/툴팁/런처 설정을 유지한다. `nameplates.yml`은 관리자가 첫 설정할 때 생성된다.

남은 실제 게임 QA: 두 계정으로 자기 제외/네 기숙사와 권한 변경, F5·카메라 회전·장거리/웅크림·팀 규칙·투명/벽 가림, NPC 일반/모델별 실제 머리 높이와 보간 흔들림, 4글자/긴 한글/라틴/이모지, NPC 재생성/청크 언로드/서버 이동/재시작 영속성, Iris/셰이더 및 GPU 텍스트/알파/밉맵·대규모 NPC 프레임 성능. CPU·단위 테스트로 실제 GPU나 운영 영속성 관찰을 대신 주장하지 않았다.
