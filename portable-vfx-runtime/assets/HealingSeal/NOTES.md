# HealingSeal (회복봉인) — 변환 메모 (REDO 2026-10-25)

- 대상(디버프를 받은 몹/플레이어)에 붙는 attached 이펙트. 발 = 원점, +Z = 대상 정면. 가슴 앞(1.22 m, 정면 0.24 m)에 적자색 자물쇠 시길(줄 그은 회복 십자+열쇠구멍)과 3D 걸쇠, 가슴(1.32 m)·허리(0.86 m)를 감는 사슬 고리 2개, 몸통(1.06 m) 룬 띠.
- 크기: `scale.input = targetHeight` (기준 1.8 m, 0.5~2.5배). 대상 키를 넘겨 주세요.
- 단계(phased): `healing_seal_begin` → `healing_seal`(유지, 런타임 `holdDuration`, 기본 8초) → `healing_seal_end`.
  - 디버프 적용 시 begin 시작, 0.62초 뒤 유지 시작, 디버프 종료/해제(정화·사망 포함) 시 유지 정지 → end 재생.
  - 유지 레이어는 `onStop`(jumpToTail)으로 정지 즉시 사라지고 end의 '고리 열림'이 이어받음.
- `healing_seal_blocked`: 외부 "회복 시도 차단" 이벤트마다 대상에 새 인스턴스로 1회 재생(약 0.6초). 회복 시도가 없으면 재생하지 않음 — 반복 파동 없음.
  - 초록·흰 회복빛 = 체력 회복, 옅은 청색 입자 = 마나 회복 차단(README 후속 정의: 회복봉인은 체력+마나 모두 봉인).
  - 어떤 회복 수단이 '차단 이벤트'를 보내는지는 미정 → VFX가 정하지 않음.
- 시작: 적자색 리본이 몸을 감고 사슬 고리 2개가 조여 들며 시길이 커지고, 걸쇠가 내려와 **0.58 s**에 잠김(섬광·파동·파편·사슬 번쩍임). 유지: 시길·걸쇠·사슬·룬 띠가 돌며 유지(입자 1개씩, onStop 0.08 s), 가라앉는 입자·어두운 연기·사슬 반짝임·1초마다 약한 맥동. 종료: 걸쇠가 튀어 열리고 사슬이 바깥으로 풀리며 흩어짐. 차단: 초록·흰 회복빛이 몰려오다 0.10 s에 자물쇠가 번쩍이며 튕겨 냄(초록 잎빛·청색 마나 입자 흩어짐).
- 지속 8초, 마나 85, 쿨타임 25초, 획득 조건(연금술 물약 제작 20회), 실제 회복 차단·디버프 판정은 모두 외부 기능.
- 미지원 기능 사용 없음(client_compat OK).


## REDO 2026-10-25 (시각 재제작, 계약 동일)
- 시스템 4개 id·역할·phase·duration 동일(begin 0.6 / healing_seal 루프 1 / end 0.1 / healing_seal_blocked 0.12), sequence(sustainStart 0.62, hold 0.1–60, 기본 8)·scale(targetHeight 1.8, 0.5–2.5) 동일, 차단 시 0.10 s 잠금 섬광 동일.
- 예전 평면 아이콘(원형 표식·십자 빌보드)을 3D 형태로 교체: 몸을 감는 사슬 메시 고리, 세로 룬 띠, 정면 자물쇠 시길 + 걸쇠 메시. 고리 회전은 begin→sustain에서 rot3로 이어진다.
- client_compat fix 0건, check OK. 데모 `front`(정면 근접) 추가.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
