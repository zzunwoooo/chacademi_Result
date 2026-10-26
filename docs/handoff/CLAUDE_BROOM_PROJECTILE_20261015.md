# 빗자루 위 직선 투사체 보정 (Claude, 2026-10-15)

브랜치: `claude/broom-projectile-20261015` (기준: `claude/reaction-vfx-20261015` @ b07c62c)
상태: **빌드·배포 안 함** — GPT 가 빌드/배포. 순수 로직 단위 테스트만 클라우드에서 스텁으로 확인.

## 문제
빗자루로 빠르게 날며 파이어볼(직선 투사체)을 쏘면 투사체가 내 뒤에서 나오는 것처럼 보임.
- 빗자루 이동은 클라이언트 모드가 계산 → 서버가 아는 위치는 핑만큼 뒤처짐 (속도 × 핑).
- 투사체 속도가 고정이라 빗자루(16~27 b/s)보다 느리면 금방 따라잡힘.

## 변경 (사용자 승인: ①속도 상속 + ②지연 보정 둘 다)
1. **속도 상속**: 비행 속도 중 쏘는 방향 성분만큼 투사체 속도 추가 (옆/뒤로 쏘면 0, 상한 8 b/t).
2. **지연 보정**: 출발점을 `비행속도 × (핑/50 + 1)틱` 만큼 앞으로 (1~8틱, 최대 8블록).
   - 판정(chabolt): 첫 틱에 그 구간을 **충돌 판정하며** 지나감 → 바로 앞 적/벽은 정상 피격. 사거리에는 미포함.
   - 연출(PortableVFX): 같은 공식, 첫 블록/대상 직전(−0.3)에서 멈춤.
- 빗자루를 안 타면 아무 변화 없음. 대상: `projectile` 앵커 직선류만 (targeted-meteor, chain-distinct-targets, ground, virtual 출발 제외).

## 파일
| 모듈 | 파일 |
|---|---|
| ChacademyMotion 플러그인 | `broom/CarrierVelocity.java`(신규, EMA α0.35, 6블록/틱 초과 이동은 순간이동으로 리셋), `BroomService.java`(세션별 샘플링·텔레포트/월드이동 리셋, `velocity(Player)`), `ChacademyMotionPlugin.broomVelocity(Player)` → `double[3]` 또는 null, 테스트 `CarrierVelocityTest` |
| magic-mythic-paper | `CarrierShot.java`(신규), `BroomLink.velocity()`(리플렉션), `BoltMech.java`(lead 구간 sweep + 속도 가산), 테스트 `CarrierShotTest` |
| PortableVFX paper | `BroomCarrier.java`(신규, 리플렉션), `CatalogVisualEngine.java`(출발점·속도 보정, moveProjectile 이 `a.speed` 사용) |

버전: ChacademyMotion plugin `0.3.0-codex.3-carrier.1`, magic-mythic `...-reaction.2-carrier.1`, PortableVFX paper `...-opacity.1-carrier.1`.
모듈 간 컴파일 의존성 없음 (리플렉션). ChacademyMotion 이 없으면 기존 동작 그대로.

## GPT 확인 요청
- **빌드/테스트**: 위 3개 모듈 `test` + jar. 배포 시 세 jar 함께 교체 (한쪽만 배포해도 안전하나 연출·판정 위치가 어긋남).
- **머지 충돌 주의**: `claude/magic-71-gameplay-20261014` 라인의 `BoltMech.java`, `magic-mythic-paper/build.gradle` 과 충돌함 (직접 적용 시도해서 확인). 두 라인 통합 시 BoltMech 의 tick 루프를 `advance(along, distance, counts)` 로 분리한 부분과 magic-71 쪽 변경을 수동 병합 필요.
- **기존 불일치(손대지 않음)**: ember_bolt 연출 속도 0.6 b/t·사거리 15 vs 판정(chabolt) 1.6 b/t·사거리 24. 이번 수정과 별개로 원래부터 어긋나 있음 — 맞출지 여부는 따로 결정.
- 실서버 확인 포인트: 핑 100~200ms 계정으로 빗자루 최고속 정면 발사 시 몸 앞에서 나가는지, 바로 앞 몹에 맞는지, 벽에 붙어 쏠 때 벽 너머로 안 나가는지.
