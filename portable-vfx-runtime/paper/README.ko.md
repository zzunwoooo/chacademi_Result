# 현재 서버 권위 테스트 버전: 3.1.0-claude.alpha.1

서버 명령은 MagicCodexBridge 명령어 허브의 `/개발 이펙트 <재생|따라가기|마무리|정지|전부정지|준비상태|상태>`, `/테스트 마법 이펙트 <마법> [단계]`, `/서버관리 새로고침 이펙트`입니다(OP 전용. 클라이언트 쪽 디버그는 `/이펙트도구`). 카탈로그 마법 연결은 `../docs/CATALOG-PROTOCOL.ko.md`, 서버 부하 상한은 아래 "안전 상한과 수명"을 보세요. 아래 일부 설명은 기존 릴레이 시절의 내용을 보존한 것입니다.

---

# PortableVFX Paper 릴레이

Paper 1.21.4 / Java 21용 독립 서버 플러그인입니다. 이 플러그인은 위치 기반 시각 효과 요청만 호환 클라이언트에 전달합니다. 전투, 피해량, 판정, 스킬, 엔티티 생성, MythicMobs 연동이나 의존성은 없습니다.

## 설치와 연결

1. 루트에서 `./gradlew :paper:build -PskipClient`로 빌드합니다. 루트 Gradle 설정이 client 제외 속성을 지원하는 경우 사용할 수 있습니다.
2. `paper/build/libs/portable-vfx-paper-<버전>.jar`를 Paper 서버의 `plugins/`에 넣고 재시작합니다. API와 공통 코덱은 이 JAR에 포함됩니다. `common.jar`를 별도로 넣지 않습니다.
3. 플레이어는 동일 프로토콜의 PortableVFX Fabric 클라이언트와 해당 효과 리소스 팩이 있어야 합니다. 일반 클라이언트에 효과 패킷을 보내지 않습니다.
4. `/개발 이펙트 상태`에서 호환 클라이언트와 수신 가능 클라이언트 수를 확인합니다.

`portablevfx:hello`에 정확히 4바이트 big-endian 정수 `1`을 보낸 연결만 호환 상태가 됩니다. 또한 클라이언트가 `portablevfx:effect` 수신 채널을 등록해야 합니다. 서버는 effect 채널의 송신자로 등록합니다. hello는 클라이언트 능력 확인이며 플레이어 인증이나 권한 부여 수단이 아닙니다. 클라이언트가 서버에 효과 실행을 요청하는 수신 채널은 없습니다.

프록시의 사용자 정의 payload 전달 여부는 실제 구성에서 검증해야 합니다. 설정만 바꿀 때는 `/서버관리 새로고침 이펙트`를 사용하세요. 플러그인을 비활성화/재활성화(PlugMan, Bukkit `/reload`)하면 서버의 hello·capability·카탈로그 준비 상태가 사라지지만 재접속은 필요 없습니다. 활성화 다음 tick에 접속 중인 플레이어에게 채널 목록(`minecraft:register`)을 다시 보내고, PortableVFX 클라이언트는 이를 받으면 모든 hello와 `catalog_ready`를 다시 보냅니다. Paper는 로그인 때만 이 목록을 보내므로 플러그인이 CraftPlayer의 `sendSupportedChannels`를 리플렉션으로 호출합니다. 실패하면 경고를 남기며, 클라이언트의 30초 주기 재전송으로 복구됩니다. 중복 hello는 멱등이며 플레이어 객체가 아직 조회되지 않을 때 도착한 hello는 최대 200 tick 보관 후 재적용합니다.

## Effekseer 클라이언트 주의

이 배포판의 JNI ABI는 전역 tint/opacity를 제공하지 않습니다. Effekseer 클라이언트에는 rgb=FFFFFF, opacity=1만 보내세요. 다른 값은 클라이언트가 재생을 거부합니다. 색상과 알파 변화는 Effekseer 에디터에서 작성합니다. 프로토콜 v1은 기존 릴레이와 공유하지만 구형 클라이언트가 .efkefc를 지원한다는 뜻은 아닙니다.

## 명령어

모든 명령은 OP 전용입니다(명령어 허브가 대단원 단위로 검사). 콘솔에서도 사용할 수 있습니다.

```text
/개발 이펙트 재생 <효과> <월드> <x> <y> <z> [크기] [틱] [요] [피치] [롤] [색] [불투명도] [반경]
/개발 이펙트 따라가기 <효과> <엔티티UUID|나> [크기] [틱] [기준점] [x오프셋] [y오프셋] [z오프셋]
/테스트 마법 이펙트 <마법>                 (플레이어 전용: 마나 없이 카탈로그 연출만 시험)
/테스트 마법 이펙트 <마법> <단계>          (플레이어 전용: 특정 트리거 단계만 미리보기)
/개발 이펙트 마무리 <핸들>
/개발 이펙트 정지 <핸들>
/개발 이펙트 전부정지
/개발 이펙트 상태
/서버관리 새로고침 이펙트
```

- `status`: 호환/수신 가능 클라이언트, 활성 핸들, 종료 추적(draining) 핸들, PLAY 재시도 대기, 진행 중 시전 수, 오류로 취소된 시전/건너뛴 단계 수, 자신의 카탈로그 준비 진단과 최근 거부 사유를 보여 줍니다.
- `reload`: `config.yml`, `spell-catalog.yml`, `spell-bindings.yml`을 다시 읽습니다. 마법은 하나씩 검증하며 잘못된 마법만 건너뛰고(ID와 사유를 명령 결과와 서버 로그에 표시) 나머지는 적용합니다. 파일 자체를 읽을 수 없거나 구조가 잘못되면 **이전 카탈로그와 실행 중인 효과를 그대로 두고** 실패를 알리며, 이때는 `config.yml` 한도만 적용합니다. 성공하면 기존 효과 정리를 요청합니다.

- `effect`: 소문자 namespace:path 형식. 예: `portablevfx:aurademo`. 최대 128 ASCII 바이트. 서버는 클라이언트 리소스 팩 목록을 보유하지 않으므로 ID 문법만 검증합니다. 실제 리소스가 없는 ID는 클라이언트에서 안전하게 무시됩니다.
- `world`: 로드된 Bukkit 월드 이름. 예: `world`. 없는 월드는 거부합니다. 패킷에는 해당 월드의 namespaced key가 전달됩니다.
- `x y z`: 유한한 절대 좌표. 각 축 절댓값은 최대 30,000,000입니다. `~` 상대 좌표는 지원하지 않습니다.
- `scale`: 기본 1, 0 초과 64 이하.
- `durationTicks`: 기본 40 또는 더 작은 설정 상한, 1~12000 tick(`limits.max-duration-ticks`). 설정 상한을 넘으면 거부합니다. 1200 tick을 넘는 효과는 `stop_intent` 지원 클라이언트에만 전송합니다. 20 TPS에서 20 tick은 약 1초입니다.
- `yaw pitch roll`: 기본 0, 유한한 도 단위 회전값. 회전/색상/투명도 적용은 클라이언트 렌더러가 담당합니다.
- `rgbHex`: 기본 FFFFFF. 정확히 6자리 16진수이며 앞의 `#`는 선택 사항입니다.
- `opacity`: 기본 1, 0~1.
- `radius`: 기본 설정값 64, 0 초과 256 이하. 설정된 `view.max-radius`보다 크면 실제 전송 반경은 그 값으로 제한됩니다.

선택 인수는 순서대로 제공해야 합니다. 반환되는 handle은 서버가 생성한 UUID입니다. 실제 전송이 0명인 경우 UUID는 출력되지만 활성 핸들로 보관하지 않습니다. 전송 수는 패킷 전송 호출 성공 수이며 클라이언트의 실제 렌더링 성공을 확인한 값은 아닙니다.

```text
/개발 이펙트 재생 portablevfx:aurademo world 0 70 0
/개발 이펙트 재생 portablevfx:aurademo world 0 70 0 1.5 60 90 0 0 FFFFFF 1 96
```

실제 데모 팩에 등록된 effect ID는 루트 문서의 목록을 사용하세요. 위 `portablevfx:aurademo`는 API 설명용 예시 ID입니다.

## 공개 Java API

소비자 플러그인은 서버 JAR를 `compileOnly`로 참조하고 `plugin.yml`의 `depend: [PortableVFX]` 또는 `softdepend: [PortableVFX]`를 사용합니다. 소비자 JAR에 API 클래스를 복제하거나 shade하지 마세요. `ServicesManager`는 클래스 동일성을 사용합니다.

```java
import dev.portablevfx.paper.api.EffectRequest;
import dev.portablevfx.paper.api.PlayResult;
import dev.portablevfx.paper.api.PortableVfxService;

PortableVfxService vfx = getServer().getServicesManager()
        .load(PortableVfxService.class);
if (vfx == null) {
    // PortableVFX가 설치/활성화되지 않음. 소비자 플러그인의 정책에 따라 처리.
    return;
}

// 반드시 Bukkit 메인 서버 스레드에서 호출.
PlayResult result = vfx.play(new EffectRequest(
        "portablevfx:aurademo", "world", 0, 70, 0,
        1.0f, 60, 0, 0, 0, 0xFFFFFF, 1f, 64));

// 이후 원하는 시점에 메인 스레드에서:
boolean existed = vfx.stop(result.handle());
```

공개 API는 `play(EffectRequest)`, `stop(UUID)`, `clear()`, `status()`입니다. `EffectRequest.at(id, world, x, y, z)`는 scale=1, duration=40, white, opacity=1, radius=64 기본값을 만듭니다. 설정 최대 duration이 40보다 작은 서버에서는 명시적 생성자를 사용하세요.

비동기 작업에서 호출해야 한다면 소비자 플러그인의 `Bukkit.getScheduler().runTask(...)`로 메인 스레드에 전달하세요. 모든 service 메서드는 메인 스레드 및 활성 상태를 검사합니다. 잘못된 값/월드는 `IllegalArgumentException`, play/handle 예산 초과는 `RejectedExecutionException`, 비동기/비활성 호출은 `IllegalStateException`입니다. null은 허용하지 않습니다.

`PlayResult`는 handle, 실제 송신 대상 수(`recipients`), 예산 때문에 다음 tick 재시도 큐에 넣은 대상 수(`deferred`), 예산 때문에 제외한 대상 수(`skippedRateLimited`), 적용된 반경을 제공합니다. 셋 다 0이면 받을 수 있는 시청자가 반경 안에 없다는 뜻입니다. 반경 내 클라이언트가 나중에 들어와도 기존 효과는 다시 전송하지 않습니다. 처음 play를 보낸 사람만 해당 handle의 stop 대상입니다. stop은 대상이 멀리 이동했더라도 보낼 수 있습니다.

## 안전 상한과 수명

`config.yml`의 `limits.*` 값입니다. 고정 안전 상한 안으로 제한되며 실제 적용값은 시작/reload 로그(`Relay limits: …`)에 남습니다.

| 키 | 예전 기본값 | 현재 기본값 | 범위 | 의미 |
| --- | --- | --- | --- | --- |
| `max-active-handles` | 512 | 4096 | 1–16384 | 아직 끝나지 않은 효과 핸들 수. FINISH/IMPACT로 끝난 핸들은 세지 않습니다. |
| `packets-per-tick` | 1024 | 8192 | 1–32768 | 서버 전체 tick당 패킷. POSE/ORIENT는 이 중 최대 50%만 사용합니다. |
| `packets-per-player-per-tick` | 32 | 32 (변경 없음) | 1–128 | 시청자 한 명에게 tick당 보내는 패킷 합계. |
| `play-requests-per-tick` | 128 | 1024 | 1–4096 | tick당 PLAY/IMPACT 요청 수. |
| `max-duration-ticks` | 12000 | 12000 (변경 없음) | 1–12000 | 효과 한 단계의 최대 수명. |
| `finish-drain-ticks` | (고정 1200) | 100 | 0–1200 | 끝난 핸들을 hard STOP 대상으로 더 추적하는 시간. |
| `play-retry-ticks` | (없음) | 10 | 0–40 | 예산 때문에 못 보낸 PLAY를 다시 보내는 기한. |
| `max-active-casts` | (고정 128) | 512 | 1–8192 | 서버 전체 진행 중 카탈로그 시전 수. 512보다 크게 올리면 시청자 100명 기준 tick 시간이 눈에 띄게 늘어납니다. |
| `max-casts-per-player` | (고정 8) | 12 | 1–64 | 플레이어 한 명의 진행 중 시전 수. |
| `max-pending-phases` | (고정 256) | 256 | 16–4096 | 시전 하나에 예약해 둘 수 있는 단계 수. |

서버에 이미 있는 `config.yml`은 덮어쓰지 않습니다. `config-version` 줄이 없는 예전 파일에 예전 기본값(512 / 1024 / 128)이 그대로 있으면 플러그인이 현재 기본값을 대신 쓰고 WARNING을 남깁니다. 예전 값을 일부러 유지하려면 `config-version: 2`를 추가하세요.

- 같은 월드이며 요청 중심에서 3차원 반경 내에 있는 호환 연결만 play 대상입니다.
- 시청자별 종류 상한은 고정입니다: 새 PLAY/IMPACT는 48개 버스트 + tick당 8개, POSE/ORIENT는 tick당 16개, STOP/CLEAR/FINISH는 tick당 10개. 모두 클라이언트 수신 예산(PLAY 64/초당 200, 스트림 128/초당 640, 제어 256/초당 256)보다 낮습니다.
- 전역 예산은 종류별로 나뉩니다. POSE/LINK_POSE/ORIENT는 `packets-per-tick`의 50%까지만 쓰고 나머지 50%는 PLAY/IMPACT/FINISH/STOP/CLEAR 몫이라, pose가 많아도 새 시전과 종료가 막히지 않습니다.
- 시청자 예산 때문에 PLAY를 보내지 못하면 버리지 않고 그 시청자의 재시도 큐(최대 64개)에 넣어 다음 tick부터 `play-retry-ticks` 안에 보냅니다. 패킷에는 원래 시작 tick이 들어 있어 클라이언트가 경과 시간을 따라잡습니다. 기한이 지나거나 그 사이 단계가 끝나면 버립니다. 월드 시간을 알 수 없거나 확장 PLAY를 지원하지 않는 클라이언트는 재시도 없이 제외(`skippedRateLimited`)됩니다.
- 카탈로그 시전은 시청자가 예산 때문에 밀렸을 뿐이면 성공입니다. 시전 직후 단계를 받을 수 있는 시청자가 한 명도 없을 때만(`no eligible viewer in range`) 거부합니다. 진행 중인 시전의 이후 단계에 시청자가 없거나 그 단계가 실패해도 시전 전체를 취소하지 않습니다.
- POSE는 대기열에 넣거나 늦게 재전송하지 않습니다. 예산이 차서 건너뛴 시전은 다음 tick에 가장 오래 갱신되지 못한 것부터 먼저 보냅니다. opcode 13 LINK_POSE는 `stop_intent` 클라이언트에만 보내고 나머지에는 같은 위치를 opcode 7로 보냅니다.
- FINISH/IMPACT로 끝난 핸들은 즉시 활성 상한에서 빠지고, `min(원래 만료, 지금 + finish-drain-ticks)`까지만 따로(최대 `max(1024, max-active-handles × 4)`개) 추적합니다. 이 추적은 잔여 입자를 `stop(handle)`으로 즉시 지우는 데만 쓰입니다. FINISH 패킷 자체는 제어 큐가 따로 보관하므로 추적 시간과 무관하게 전달됩니다. 추적이 끝난 뒤의 잔여 입자는 클라이언트 자체 수명으로 끝납니다. 기본 100 tick(5초)은 카탈로그의 종료 꼬리(finish 이후 남는 수명)가 대부분 이 안에 끝나고, 시전 취소(사망·월드 이동·접속 종료)로 잔여 입자를 지워야 하는 경우가 종료 직후에 몰리기 때문입니다. 예전처럼 1200 tick을 붙잡으면 초당 8~9개 단계만 끝나도 512개 상한이 차서 새 시전이 실패했습니다.
- 카탈로그 시전 엔진도 같은 값으로, 끝난 단계를 `finish-drain-ticks` 뒤에 내려놓습니다. 충돌한 투사체가 원래 수명(최대 60초 이상) 동안 플레이어당 시전 칸을 차지하지 않습니다.
- STOP/CLEAR는 새 play보다 우선합니다. 예산이 없으면 다음 tick으로 미룹니다. 플레이어별 미전송 stop이 256개를 넘으면 한 clear로 합칩니다.
- `clear`와 성공한 `reload`는 기존 핸들을 제거하고 영향을 받은 호환 클라이언트에 clear를 요청합니다. clear 패킷은 수신 클라이언트의 PortableVFX 런타임 전체를 정리합니다.
- 플레이어별 제어 큐는 접속 종료 시 폐기하며 월드 변경 시 clear로 합칩니다. 채널 등록 취소 시 호환 상태와 대상 추적도 정리합니다.
- duration이 끝나면 서버 핸들을 제거합니다. 클라이언트에도 동일한 유한 TTL이 있으므로 추가 stop 패킷 없이 종료됩니다.
- hello 채널은 플레이어별 토큰 버킷(32개 버스트, tick당 1개), `catalog_ready`는 플레이어별 3개 버스트 후 20 tick당 1개로 제한합니다. 제한에 걸린 readiness는 최신 것 하나만 보관했다가 적용하고, 직전에 수락한 것과 같은 payload는 디코드 없이 무시합니다. 서버 요구 목록보다 512개 넘게 많은 ID를 선언한 readiness는 디코드 전에 거부합니다.
- 시전 거부는 건별로 로그를 남기지 않습니다. 사유별 횟수와 표본 1건(플레이어·마법·사유·클라이언트 진단)을 약 10초에 한 줄(`Catalog VFX: refused casts=…`)로 남기고, 실행 중 오류로 취소된 시전과 건너뛴 단계 수도 같은 줄에 포함합니다.
- 조준·투사체·연쇄 대상에서 시전자가 볼 수 없는 엔티티(vanish/숨김), 관전자, marker ArmorStand, 이 플러그인의 가상 앵커(`portablevfx_virtual_anchor` 태그), 죽었거나 무효인 엔티티는 제외합니다.
- `out-and-back` 궤적(귀환 칼날)은 돌아오는 구간에서 블록을 통과해 시전자에게 돌아오며, 따라잡지 못하면(왕복 예상 시간의 2배 + 40 tick) 그 자리에서 귀환을 끝내고 `external:return_to_caster` 단계를 시전자 위치에서 재생합니다.
- 종료(onDisable) 시 가상 앵커 엔티티를 먼저 지우고, task·listener·service·채널과 내부 핸들을 단계별로 예외를 격리해 정리합니다. 비활성 상태에서는 플러그인 메시지를 보낼 수 없으므로 종료 clear는 보내지 않습니다. 남은 클라이언트 효과는 각자의 TTL 또는 월드/연결 초기화로 종료됩니다.
- 서버의 `spell-bindings.yml`은 덮어쓰지 않습니다. 시작 시 번들 기본값과 다른 마법 ID 목록을 한 번 WARNING으로 출력합니다. 병합할 키는 루트 `README.ko.md`의 falling_star 표를 보세요.
- 시작할 때 카탈로그 파일을 읽지 못하면 SEVERE 로그를 남기고 카탈로그 없이 동작합니다(모든 카탈로그 시전 거부). 단계 수명이 `max-duration-ticks`보다 긴 마법은 마법별 WARNING으로 알립니다.

## 테스트 범위

`RelayEngineTest`는 실제 공통 코덱과 가짜 전송 계층으로 handshake/channel gating, 월드/반경, 실제 recipient stop, 예산(전역 pose 몫, PLAY 재시도 큐), 종료 추적, stop/clear 우선순위, TTL, 연결/월드 정리, reload, hello/readiness 속도 제한, 전송 실패 및 상한을 검증합니다. `CatalogCastBehaviourTest`는 가짜 서비스와 월드로 시전 상한, 핸들 해제, 무시청자 단계, 귀환 궤적, 대상 필터, 종료 정리를 검증합니다. `EffectRequestTest`는 공개 요청 입력 검증을 확인합니다. 이 테스트는 Paper 프로세스와 실제 Minecraft 네트워크/렌더러를 띄우지 않습니다. 실제 Paper/Fabric 접속 검증 여부는 루트 검증 문서를 확인하세요.

## 근거 API 문서

- [Paper plugin messaging](https://docs.papermc.io/paper/dev/plugin-messaging/)
- [Paper 1.21.4 Player API](https://jd.papermc.io/paper/1.21.4/org/bukkit/entity/Player.html)
- [Paper 1.21.4 ServicesManager API](https://jd.papermc.io/paper/1.21.4/org/bukkit/plugin/ServicesManager.html)


## Dual runtime: seed / 시각 부착 확장

기존 `/개발 이펙트 재생`와 `PortableVfxService.play(EffectRequest)`는 그대로 사용합니다.
클라이언트의 리소스 정의가 Effekseer/Unity-style backend를 선택합니다.
새 클라이언트에만 `portablevfx:play_ext_hello` capability 확인 후 seed/startTick이
포함된 opcode 6을 보냅니다. 구형 클라이언트는 기존 바이트를 받습니다.

- `/개발 이펙트 따라가기 <효과> <엔티티UUID|나> [크기] [틱] [기준점] [x오프셋] [y오프셋] [z오프셋]`
- anchor: `entity`, `head`, `left_shoulder`, `right_shoulder`
- 명시적 고정 seed/부착 API: `play(EffectRequest, PlaybackOptions)`
- `PlaybackOptions.world(0)`는 고정 seed 0, `PlaybackOptions.attached(uuid, EffectAnchor.HEAD, 42)`는 head 부착
- 세부 옵션 생성자: `PlaybackOptions(UUID, EffectAnchor, offsetX, offsetY, offsetZ, seed, startTick)`
- startTick은 월드 game time이며 `-1`은 즉시 재생입니다. 미래 예약 기능은 아닙니다.
- 부착은 엔티티 눈높이·너비를 이용한 근사 위치입니다. 실제 애니메이션 bone socket은 아닙니다.
- 명시적인 새 옵션 요청은 확장 지원 클라이언트에만 전달합니다. 기존 수신자에 임의로 바꾸어 보내지 않습니다.

정확한 wire 형식·좌표 정의·검증 범위: `common/EXTENDED-PLAY.md`.
