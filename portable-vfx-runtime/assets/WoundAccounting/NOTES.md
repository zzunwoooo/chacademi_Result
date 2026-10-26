# WoundAccounting (상처 회계) — converter notes

## 시스템과 시작 시점 (모드가 시작)
| system | role | 시작 | 위치/기준 |
|---|---|---|---|
| gather | attached (시전자) | 시전 시 | 시전자 발 + 수평 facing. 손 위치 = 시전자 기준 (0.3, 1.35, 0.55) |
| projectile | projectile | gather 시작 0.4 s 뒤, 손 위치에서 | 매 틱 위치 + 진행 방향 (대상 추적/속도는 모드) |
| contact | impact | 투사체 도착 | hitPoint, surfaceNormal (엔티티·공중 = −incomingDirection) |
| absorb | attached (대상) | 대상 적중 시 | 대상 발 + 수평 facing (대상을 따라감), 0.6 s |

- projectile 은 loop + 20 s 단일 몸체 파티클(flipbook cycles 44). 도착 시 SCHEMA 규칙대로 local 레이어 제거, world 꼬리는 방출 중지 → 꼬리가 0.2 s 안에 스스로 정리됨.
- 벽/바닥에서 막히는 경우는 contact 만 재생하는 예시(미리보기 ‘벽’·‘바닥’)일 뿐, 차단 여부는 모드 규칙. 대상이 사라진 경우 = ‘공중’.

## 런타임 입력
- `scale` (§5.3, input targetHeight, reference 1.8, min 0.5, max 2.0): **absorb 인스턴스에만 대상의 바운딩 박스 높이를 넘긴다.** gather / projectile / contact 는 값을 넘기지 않음(→ reference = 크기 1).
  (클라이언트에 `scale.systems` 가 없어 시스템별 지정 대신 인스턴스별 입력으로 처리)

## 게임 로직 (이 팩에 없음 — 외부 기능)
- 무작위 디버프 선택·적용, 디버프별 내장 지속시간, 아이콘·지속시간 표시 = 공통 상태이상 시스템. 이 팩은 디버프 아이콘/지속시간을 만들지 않음.
- 상태별 연출은 실제 선택된 상태 이벤트에 연결 (이 팩과 별개). 디버프 적용 시점(적중 순간 / 스며드는 t≈0.35 s)은 모드가 정함.
- 마나 80, 쿨타임 30 s, 획득 조건: 모든 디버프에 걸려보기.
- 미정(임의 확정 안 함): 대상에게 적용할 수 없는 디버프의 재추첨/제외 처리, 투사체 차단 여부.
- 기존 피해 누적·추가 피해 폭발 연출은 폐기됨 (이 팩에 없음).

## 룩
- 무광: 모든 재질 alpha blend, tint 1 (HDR·가산 없음 → 블룸에 걸리지 않음). 검남색 면 + 얇은 청회색 가장자리는 텍스처에 구움.
- 꼬리/덩어리는 테두리 없는 채움 텍스처(T_InkLumpFill)라 겹쳐도 하나의 덩어리로 읽힘(비늘처럼 보이지 않게). 눈·입·비늘 없음, 상시 광선 없음, 폭발·보라 연기·마법진 없음.
- 몸 감기: 커스텀 나선 리본 메시 2개(wrapHelix, Y 회전) + 2x8 띠 와이프 플립북(감겨 들어옴 → 가늘어지며 침식).

## 2026-10-25 재제작 (시스템·역할·타이밍 그대로)
- 시스템 id/역할/길이 그대로: gather(attached 시전자, 0.45 s, 방출 +0.4 s) · projectile(projectile, loop 20 s 단일 몸체) · contact(impact, 0.5 s) · absorb(attached 대상, 0.6 s). scale(targetHeight 1.8, 0.5~2.0)도 그대로 absorb 인스턴스에만 쓴다.
- 서버 바인딩 그대로: external:gather → gather, +8 t 에 서버가 `claude:woundaccounting/projectile` 을 손 앞(시전자 중심 +0.55, 높이 1.35)에서 띄우고 매 틱 이동, 도착 시 close → external:contact(가슴) + external:absorb(대상).
- 손 위치: gather 의 모이는 점을 서버의 실제 투사체 시작점(가운데, x 0)으로 옮김 (예전 x 0.3 → 0). 시간은 같음.
- 룩 변경 (오너 지시 "부실" → 어둠 팔레트 재작업): 잉크 덩어리는 어두운 몸체 + 보라 림(알파, HDR tint) 2겹이 서로 다른 속도로 돌고, 작은 덩어리가 끓어오르며, 림이 빛나는 잉크 방울 3개가 궤도를 돌고, 점선 궤도선과 잉크·보라 호가 감김. 꼬리는 테두리 없는 잉크 안개 덩어리, 가는 보라 선, 처지는 방울, 모트. gather는 잉크가 단계적으로 손에 모이고 사각 라인 프레임, 초승달, 발밑 소원진이 뜨며, +0.4 s에 이중 링·섬광·불꽃이 나감. contact는 섬광, 잉크 왕관 2겹, 선 링 2겹, 잉크 덩어리·방울, 불꽃, 호, 잉크 퍼프. absorb는 잉크 나선 띠 2줄(검은 띠 + 보라 띠, 와이프 플립북)이 몸을 감고, 장부 링(눈금 표시 룬띠)이 가슴을 조이다가 **+0.35 s**에 번쩍이며 몸으로 가라앉음(디버프가 기록되는 순간). 발밑에는 잉크 웅덩이, 소원진, 파문.
- 눈·입·비늘은 여전히 없음.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
