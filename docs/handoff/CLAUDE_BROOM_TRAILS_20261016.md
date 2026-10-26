# 빗자루 비행 궤적 (Claude, 2026-10-16)

브랜치: `claude/broom-trails-20261016` (기준: `claude/broom-projectile-20261015` @ b69ea36)
상태: **빌드·실게임 안 함.** 궤적 패키지만 스텁으로 javac 타입 검사 + 순수 로직 JUnit 11개 통과(클라우드). Gradle 빌드·배포는 GPT.
사용자 승인: 기본·전설 궤적 미리보기(`tools/vfx-src/BroomTrails/BroomTrail_Compare.html`)를 승인했고, GPT 검토 9항목을 반영했다.

## 0. 먼저 볼 것
- **PortableVFX 코드는 바꾸지 않았다.** 기존 클라이언트 API(`play / move / basis / finish / stop / activeInstances / list`)만 쓴다. 계약은 아래 3장에 적었다.
- **서버·패킷·플러그인 변경도 없다.** 누가 어떤 빗자루를 탔는지는 기존 `chacademy:broom_state`로 이미 받는다.
- **클라이언트 배포물에 팩 `BroomTrails`를 넣어야 보인다.** 위치는 `.minecraft/config/portablevfx/effects/BroomTrails/`이고, 원본은 `portable-vfx-runtime/assets/BroomTrails/`다. 팩이나 PortableVFX가 없으면 궤적만 안 나오고, 로그를 한 번 남긴다.
- 모션 모드 버전: `0.3.0-codex.3-hud.2-trails.1`.

## 1. 변경 범위
| 위치 | 내용 |
|---|---|
| `portable-vfx-runtime/assets/BroomTrails/` | 새 팩(`vfx.json` + 텍스처 5장, RGBA 0.23 MiB, `client_compat check` OK). 시스템 4개, 전부 `role static`, `loop`: `basic`, `basic_lite`, `legend`, `legend_lite` → 효과 ID `claude:broomtrails/<id>` |
| `tools/vfx-src/BroomTrails/` | `gen_broom_glb.py`(실제 bbmodel → 미리보기 모델, BroomModel.java 와 같은 파싱·같은 솔 끝 규칙), 원본 spec(`BroomTrails.js` = 배포 팩, `BroomTrailBasic.js`·`BroomTrailLegend.js` = 승인용 비행 미리보기), 텍스처 생성기 `gen_trail_tex.py`, 미리보기용 탑승자 모델 `gen_rider.py`, `build.sh`, 비교 페이지 |
| `chacademy-motion-mod/.../broom/trail/BroomTrails.java` | 본체. 생성·이동·종료·재시도·월드 변경 처리 |
| `.../broom/trail/TrailPlanner.java` | 누구를 full/lite로 보여줄지 결정(히스테리시스·예산). MC 타입 없음 |
| `.../broom/trail/RetryGate.java` | 실패 시 재시도 간격 |
| `.../broom/trail/TrailConfig.java` | 연결표 파싱 |
| `.../broom/trail/VfxBridge.java` | PortableVFX 리플렉션(1회 탐색·캐시) |
| `assets/chacademy_motion/broom_trails.json` | 빗자루 ID → 궤적 키 → 효과 ID. 리소스팩으로 덮어쓰기 가능 |
| `ChacademyMotionClient.java` | `BroomTrails.init()`, 접속 종료 시 `BroomTrails.reset()` |
| `api/BroomApi.java` | `setTrailOverride(uuid, key)` / `clearTrailOverride(uuid)` / `clearTrailOverrides()` |
| 테스트 | `TrailPlannerTest`(8), `RetryGateTest`(3) |

## 2. 동작 (GPT 검토 항목별)
1. **재시도 제한**
   - 생성 거절이나 생성 직후 사라짐(200틱 미만)은 사람별로 간격을 20→40→80→160→320→400틱으로 늘린다.
   - PortableVFX에 없는 효과 ID는 **리소스 리로드 전까지 재시도하지 않는다**(로그 1회).
   - PortableVFX가 아직 팩을 불러오지 않았으면(목록이 비어 있음) 간격을 두고 다시 시도한다.
   - 전체 활성 효과가 96개 이상이면 새 궤적을 만들지 않는다(마법 VFX용으로 32칸 확보).
   - 효과 목록(`list()`)은 리로드 20틱 뒤나 목록이 비어 있을 때만 다시 받는다. 매 틱 받지 않는다.
2. **보간·솔 끝**
   - `END_CLIENT_TICK`의 늦은 단계(`chacademy_motion:broom_trails_late`, 기본 단계 다음)에서 `move`/`basis`를 부른다.
   - PortableVFX는 틱 시작에 직전 위치를 저장하고, 렌더 때 직전 → 이번 위치를 같은 tickDelta로 보간한다. 그래서 방출점이 BroomRenderer의 빗자루와 같은 부분 틱으로 움직인다.
   - 이 순서가 바뀌면(우리가 먼저 move) 보간 없이 20Hz 계단이 된다. **실게임 확인 1순위**(4장).
   - 솔 끝 = 모델 뒤끝(+Z 최대)에서 4px 띠 안의 모든 꼭짓점 중심(x,y), z = 뒤끝 − 1px. broom_2처럼 솔 장식이 한쪽으로 더 길게 뻗은 모델에서 한쪽 끝을 잡지 않게 하려는 규칙이다(최대면만 보면 broom_2는 옆으로 0.47m 치우쳤음). 이 점을 BroomRenderer.draw와 같은 변환으로 옮긴다: `SEAT_Y` + YP(180−yaw)·XP(−pitch)·ZP(bank+spin)·(1/16)·(−seat).
   - 팩 원점이 솔 끝이라 위치 오프셋 오차가 없다. basis는 틱 단위로 바뀌고, 입자의 초기 속도 방향에만 쓰인다.
   - world 공간 입자는 생긴 자리에 남고 방출점만 따라간다. 로컬 레이어는 전설의 솔 끝 빛 1개뿐이다.
   - 소환 연출(`age < 10`) 동안은 궤적을 켜지 않는다.
3. **종료 구분**
   - 일반 종료(내림·낙마·범위 밖·예산): `finish` → onStop 꼬리 0.1~0.15초.
   - 순간이동(한 틱에 솔 끝이 8블록 이상 이동): `stop`(즉시 제거) 후 새 인스턴스. 새 인스턴스라 이동 거리 누적이 새로 시작되고 순간이동 경로에 선이 생기지 않는다.
   - 월드·차원 변경: PortableVFX `synchronizeWorld`가 전부 지우므로 우리 상태만 잊는다(finish 안 함 → 잔상 없음).
   - 접속 종료: `reset()`으로 덮어쓰기까지 정리한다.
   - 효과 교체(빗자루 변경·덮어쓰기·full↔lite): 옛 것은 `finish`, 새 것은 생성.
   - 10분 지속 한도(loop = 12000틱) 전인 11800틱에 갈아탄다.
4. **경계 깜빡임**
   - 진입은 40블록·순위 24 이내, 이탈은 48블록 또는 순위 28을 넘은 상태가 20틱 이어질 때다. 64블록을 넘으면 즉시 끈다.
   - 나 자신은 항상 포함한다(full). **카메라 시야는 보지 않는다**(고개를 돌려도 유지).
   - full↔lite는 가까운 3명이 full로 진입하고, 5명·26블록을 넘으면 이탈한다. 바꾼 뒤 60틱 동안은 유지한다.
5. **입자 예산** — 예상 생존 입자 = Σ(블록당 비용 × 속도 b/s + 고정). 비용은 팩에서 계산했다.

   | 효과 | 블록당 | 20 b/s | 39 b/s(최고+부스트) |
   |---|---|---|---|
   | basic | 4.57 | 91 | 178 |
   | basic_lite | 1.3 | 26 | 51 |
   | legend | 14.09 (+4.2) | 286 | 554 |
   | legend_lite | 2.59 | 52 | 101 |

   - 합계 예산은 **2000**이다(PortableVFX 프레임 한도 8192의 약 1/4).
   - 넘으면 먼 사람부터 lite로 내리고, 그래도 넘으면 먼 사람부터 숨긴다. 승격은 승격 후 1600 이하일 때만 한다.
   - 테스트에는 24명 전원 전설 최고속 부스트(예산 안으로 줄어듦)와 일반 순항 24명(전원 표시) 경우가 있다.
   - 겹침(오버드로)은 레이어가 가늘고 수명이 짧아 제한적이다. 다만 실측은 아니다.
6. **API 계약 확인 결과** (PortableVFX client 소스 2026-10-15)
   - `play(Identifier, Vec3d, float, int)` → `Optional<UUID>` **인스턴스 핸들**(WORLD 앵커)이다. `move/basis/finish/stop`은 이 UUID를 받는다. 그래서 같은 `basic`을 여러 명이 써도 섞이지 않는다.
   - durationTicks는 **틱** 단위다. 0이면 팩 기본값이고, loop 시스템은 `VfxProtocol.MAX_DURATION_TICKS` = 12000이다.
   - 생존 확인은 `activeInstances()`다. 렌더러가 늦게 거절하면(준비 안 됨·장면 한도) 인스턴스가 조용히 사라지므로, 이 목록으로 감지해 재시도 간격을 적용한다.
   - `finish(id)` → `EffectRuntime.finish(id,false)` → 렌더러 `finishEmissionAt` → `ClaudeSimulation.stopEmission(false)` → 레이어 `onStop.jumpsToTail()`. **onStop이 실제로 적용된다.**
   - `basis`는 정규직교·det≥0.99999만 받는다. ClaudeFrames와 같은 규칙(right = up × forward)으로 만든다.
   - `move`는 WORLD 앵커와 플레이어 256블록 안에서만 받는다.
7. **리플렉션**
   - 처음 쓸 때 클래스·메서드를 한 번만 찾아 캐시한다. 형식이 다르거나 호출이 예외를 던지면 **궤적 기능만 끄고 로그는 한 번** 남긴다.
   - ResourceLocation/Vec3는 실행 시 Identifier/Vec3d와 같은 클래스(intermediary)라 그대로 넘긴다.
   - 컴파일 의존은 없지만 위 메서드 형식에는 의존한다. PortableVFX API를 바꾸면 이 문서 6장을 같이 확인해야 한다.
8. **매핑·hook**
   - `broom_10`(장인의 빗자루)과 `broom_7`(발걸이 경주용 빗자루)이 `brooms.yml`에서 grade 전설인 것을 확인했다. 이 둘은 `legend`, 나머지 8종은 `basic`이다. **임시 배정**이고, 빗자루별 궤적이 나오면 json만 바꾼다.
   - hook은 설정·해제·전체 해제를 지원하고, 접속 종료 때 자동 해제된다. 차원 이동에는 유지된다.
   - 비행 중 바뀌면 옛 것을 finish하고 새 것을 생성한다.
9. **한계 (남아 있음)**
   - **내 궤적/남의 궤적 투명도 분리 불가.** PortableVFX 로컬 효과는 owner를 지정할 API가 없어서 모든 궤적이 "내 이펙트 투명도"(`VfxPreferences own`)를 따른다. 나누려면 PortableVFX API 추가가 필요하다(이번엔 보류, 사용자 결정 필요).
   - **덮어쓰기 hook은 이 PC 화면에만 적용된다.** 아티팩트 궤적을 다른 사람에게도 보이게 하려면 서버가 주변 클라이언트에 알려 주는 연동(예: `broom_state` 확장)이 따로 필요하다.

## 3. 팩 메모
- 2026-10-16 사용자 요청으로 궤적 입자 수명을 승인 미리보기의 ×0.75로 줄였다(꼬리 길이 약 25% 감소, 솔 끝 빛 제외).
- 레이어 수치는 승인 미리보기와 같고, 위치만 솔 끝 기준으로 옮겼다(원점 = 솔 끝, +Z = 빗자루 앞, +Y = 빗자루 위).
- 미리보기의 역할 `attached`(발 + 몸 방향)를 `static`으로 바꿨다. 남의 몸 방향이 빗자루 방향과 달라서다(BroomRiders는 이동 방향으로 빗자루 yaw를 추정한다).
- `*_lite`는 중심 줄기(+전설은 별 일부)만 있고, 입자는 약 1/4~1/5이다.
- 이 팩의 `preview.html`은 정지 상태라 솔 끝 빛만 보인다. 비행 모습은 `BroomTrail_Compare.html`(같은 레이어)에서 본다.

## 4. 확인할 것 (실게임)
1. 고속 비행·급선회에서 궤적 시작점이 솔 끝에 붙어 부드럽게 움직이는지 본다. 계단처럼 끊기면 틱 단계 순서 문제다(2장 2번).
2. 내릴 때 꼬리가 0.15초 안에 정리되는지, 낙마해서 날아가는 빗자루에는 궤적이 없는지 본다.
3. `/tp`·차원 이동 때 긴 선이나 잔상이 없는지 본다.
4. 여럿이 날 때 경계(40~48블록)에서 깜빡임이 없는지, 마법 VFX가 밀리지 않는지 본다.
5. PortableVFX가 없는 클라이언트에서 오류 없이 궤적만 생략되는지 본다.
