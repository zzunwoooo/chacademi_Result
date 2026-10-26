# PortableVFX 카탈로그 런타임

Minecraft 1.21.4 / Java 21 / Fabric + Paper용 서버 권위 VFX 런타임입니다. 클라이언트와 서버는 `3.2.0-catalog.alpha.4`, 공통 통신 규약은 `3.2.0-catalog.alpha.1`입니다.

## 구성

- `client`: 범용 Claude JSON/PNG/GLB 렌더러. 마법 이름별 Java 로직이나 Effekseer/Unity 테스트 백엔드는 없습니다.
- `common`: 범위 제한·순서 검증·준비 상태 확인을 포함한 통신 규약.
- `paper`: 선언형 카탈로그 단계/이동/연결/일제 발사 재생. 실제 피해·획득 처리는 하지 않습니다.
- 기존 MagicCodexBridge가 `/마법 <영문ID>`와 HUD의 마나/쿨다운을 한 번만 처리합니다. VFX 플러그인은 승인받은 표시만 실행합니다.

기본 클라이언트 캐시는 전달된 97개 팩/235개 시스템을 시작 로딩에서 준비합니다. 첫 시전에 동기식 PNG 디코딩·업로드를 수행하지 않습니다. 서버가 필수 효과의 GPU 준비 완료를 확인하지 못하면 시전을 거부하고 마나/쿨다운이 환불됩니다. 메모리 예산과 실측 비용은 `docs/LOAD-LIMITS.ko.md`를 읽으세요. 전체 팩의 유효성 검사가 실제 Minecraft/Iris/멀티플레이 실기 검증을 대신하지는 않습니다.

## 명령

- `/마법 <영문ID>`: 기존 서버 브리지의 일반 시전
- `/이펙트도구`: 로컬 효과 ID/준비 상태/설정 오류 확인
- `/개발 이펙트`: 관리자(OP)용 범용 서버 VFX 진단
- `/codexsounddebug <영문ID>`: 기존 HUD 효과음만 확인

정상 시전 성공은 UUID나 디버그 채팅을 출력하지 않습니다. 피해·죽음·채굴·접촉처럼 후속 게임플레이가 소유하는 이벤트는 별도 이벤트 인터페이스와 디버그 명령으로 구분합니다. 준비되지 않은 효과를 다른 그림으로 대신하지 않습니다.

## 리소스와 복구

VFX 팩은 `.minecraft/config/portablevfx/effects/<Pack>/vfx.json` 및 상대 경로 리소스입니다. 마법 YML은 `.minecraft/config/magiccodex/spells`에 둡니다. 기존 `chacademia-spells` 리소스팩의 원본 아이콘 경로를 사용하며 없는 아이콘은 빈칸입니다. 원본 아이콘을 다시 생성하거나 축소하지 않았습니다.

설치·재시작·기존 파일 백업 절차는 배포물의 설치 안내를 따르세요. 원본 alpha5 소스 및 기존 HUD 입력 소스는 수정 전 아카이브/별도 입력 트리에 보존했고, 제거된 레거시 실행 파일은 새 런타임에 포함하지 않습니다. 외부 에셋과 서버 의존성의 라이선스는 별개입니다.

## 빌드

JDK 21과 Gradle 8.12를 사용합니다. 일반 환경에서는 `./gradlew :common:protocolTest :client:test :client:remapJar :paper:test :paper:jar`를 실행합니다. 제한된 클라우드 재현 방법은 `docs/CLAUDE-BUILD.ko.md` 및 검증 기록을 참고하세요.

현재 수정판: 클라이언트 alpha.4 / 서버 alpha.4 (공통 통신 규약 alpha.1 유지)
전체 VFX bloom 기본 기여도를 0.65로 줄였습니다. config/portablevfx/bloom.properties에서 조절하고 재시작할 수 있습니다.
메테오는 지면 목표를 고정하고 목표 위의 원본 오프셋에서 낙하합니다. 표식은 충돌 시 종료합니다.

### 기존 서버 설정 병합 (falling_star)

기존 서버의 `plugins/PortableVFX/spell-bindings.yml`은 플러그인이 덮어쓰지 않습니다. 시작 시 번들 기본값과 다른 마법이 있으면 `spell-bindings.yml differs from the bundled default for N spell(s): [...]` 경고가 한 번 출력됩니다(설명용 `evidence`/`review`/`*-provenance` 키는 비교 제외). 파일을 백업한 뒤 새 파일로 교체하거나, `bindings.falling_star`에 아래 값을 모두 병합하세요.

| 위치 | 키 | 값 |
| --- | --- | --- |
| `falling_star` | `trajectory` | `targeted-meteor` |
| `falling_star` | `range` | `20` |
| `fallingstar/mark` 단계 (role `static`, anchor `target`) | `finish-after-ticks` | `-1` (충돌 시 impact의 stop-effects로 종료) |
| `fallingstar/meteor` 단계 (role `projectile`) | `offset` | `[-3, 24, 19]` (목표 지점 기준 낙하 시작 위치) |
| `fallingstar/meteor` 단계 | `stop-effects` | `[claude:fallingstar/chant]` |
| `fallingstar/impact` 단계 (role `impact`) | `stop-effects` | `[claude:fallingstar/mark]` |

병합 후 `/서버관리 새로고침 이펙트`로 다시 읽고, 경고 목록에서 `falling_star`가 사라졌는지는 다음 서버 시작 로그에서 확인합니다. `spell-catalog.yml`도 같은 방식으로 백업 후 교체하세요.

투사체 사거리 종료/수명 만료 시 로컬 몸체만 제거하고 월드 잔상을 보존합니다. 늦은 렌더 프레임도 기록된 FINISH 시각에 맞춰 꼬리 수명을 계산합니다.
기본 클라이언트 80개(선택 fixture 3개 제외), 서버 69개, 통신 규약 31,753 assertions 및 최종 Mesa OpenGL bloom/depth6402 검사가 통과했습니다. 별도 구형 WaterElemental fixture 2개는 현재 에셋 구조/개수 기대값 차이로 실패했습니다. 실제 Minecraft/Iris/Paper 화면 검증은 미실시입니다.
HUD 및 원본 효과팩은 이 런타임 수정에서 변경하지 않았습니다.

### 플러그인 재활성화와 패킷 예산

PlugMan·`/reload` 등으로 PortableVFX 플러그인을 비활성화/재활성화해도 재접속은 필요 없습니다. 플러그인이 활성화 직후 접속 중인 플레이어에게 채널 목록(`minecraft:register`)을 다시 알리면 클라이언트가 모든 hello와 `catalog_ready`를 즉시 다시 보냅니다. 이 경로가 막힌 서버에서도 클라이언트가 30초마다 hello를 다시 보내므로 최대 약 30초 뒤 시전이 복구됩니다. 중복 hello는 서버에서 무시되는 멱등 처리입니다.

클라이언트는 새 단계 PLAY/IMPACT, POSE/ORIENT 스트림, STOP/CLEAR/FINISH 제어를 서로 다른 예산으로 받습니다. 많은 투사체의 POSE가 새 PLAY나 IMPACT를 막지 않으며, 서버는 플레이어별로 각 예산보다 낮게 보냅니다. 세부 수치는 `common/PROTOCOL.md`의 Per-viewer budgets 표를 보세요.
