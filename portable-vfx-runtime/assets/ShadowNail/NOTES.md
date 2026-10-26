# ShadowNail (shadow_nail, 그림자 못) — 변환 메모

**배치**: 4개 시스템 모두 `role: attached`, **대상(TARGET)** 에 붙임 (anchor = 대상 발, facing = 대상 몸 방향). 말뚝은 가슴(1.8 m 기준 y 1.17) 정면에 박혀 대상 이동을 따라감.
`scale.input = targetHeight` (s = clamp(h/1.8, 0.5, 2.5)) — 대상 히트박스 높이를 넘겨 줄 것.

**단계 (SCHEMA §5.1 phased + triggeredEnd)**
- `shadow_nail_begin` (begin, 0~0.35 s): 검은 금속 말뚝이 앞쪽 위에서 0.1 s 만에 가슴에 박힘 → 작은 보라 섬광·고리, 보랏빛 균열·잉크 얼룩이 퍼짐. 상처/피 없음.
- `shadow_nail` (sustain, 0.35 s~): 말뚝 + 은은히 숨쉬는 균열 + 작은 보라 입자. 런타임 `holdDuration` = 저주 남은 시간 (기본 15 s, 최대 60 s).
- `shadow_nail_burst` (end, endBranch `trigger`): 외부 **trigger** 신호(시전자–대상 거리 20블록 초과 발동 이벤트) → 0~0.15 s 말뚝이 한 번 강하게 맥동 → 그림자 조각·보라 불꽃으로 터짐 (~1 s). 유지 레이어는 onStop 0 으로 즉시 정리.
- `shadow_nail_fade` (end, endBranch `expire`): 15 s 만료 / 취소(대상 사망 등) → 폭발 없이 말뚝·균열이 옅어지며 소멸 (~1 s).
- trigger 는 시전당 1회만. 먼저 온 쪽이 이김 (trigger 후 expire 무시).

**외부 기능**: 거리 계산, 피해(피해량 미정), 15 s 지속시간, 마나 170 / 쿨타임 60 s / 획득 조건(1:1 대전 누적 10승)은 모두 게임 쪽. VFX는 발 묶기·순간이동 차단 연출 없음.
**클라이언트 미지원 항목**: 없음.

## 2026-10-25 재제작 (시스템·역할·타이밍 그대로)
- 시스템 id/역할/phase/endBranch, sequence(sustainStart 0.35, hold 15 s 기본·최대 60 s, trigger), scale(targetHeight 1.8, 0.5~2.5), 말뚝 메시·자세·날아오는 궤적(0.1 s)·burst 맥동 0~0.15 s → **터짐 +0.15 s** 는 예전과 같음.
- 서버 바인딩 그대로: external:nail → shadow_nail_begin + shadow_nail(+2 t), external:nail_burst → shadow_nail_burst (stop shadow_nail), external:nail_fade → shadow_nail_fade (stop shadow_nail).
- 바뀐 연출: 박힐 때 섬광 + 가슴 정면 선 충격링 2겹 + 잉크 왕관 + 오각 봉인진이 그려짐 + 발밑 못 원진이 떨어져 깔림. 유지 중에는 가슴 오각 봉인진(선 메시, 룬띠 역회전, 숨쉬는 파동), 잉크 얼룩·균열 맥동, 발밑 못 원진(10개 핀 + 오각성 + 룬띠 + 잉크 웅덩이), 허리 결박 점선 링, 잉크 방울 흘러내림, 말뚝 머리 잉크 연기, 모트. burst 때는 봉인진이 수축하며 빨려들었다가 0.15 s에 섬광, 선 충격링 2겹, 봉인진 파열, 그림자 조각, 말뚝 파편, 보라 불꽃, 잉크 왕관, 회전 호, 잉크 퍼프, 발밑 충격링이 나감. fade 때는 말뚝이 빠지며 옅어지고, 봉인진·원진이 줄어들고, 결박 링이 내려가며, 잉크가 모트로 흩어짐.
- 유지 레이어는 onStop jumpToTail 0(말뚝·봉인 등 즉시 정리, 끝 시스템이 같은 자리에서 이어받음), 입자류는 0.15~0.2 s.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
