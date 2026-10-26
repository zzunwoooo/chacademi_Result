# CurseTransfer (저주의 전가) — 변환 노트 (REDO 2026-10-25)

확산 이벤트 1회 = 이 팩의 cast 1회 + `spread_gN` 1개. VFX는 대상을 찾거나 스스로 번지지 않는다 (확산 범위·횟수·중복·디버프 복제·30% 감소·적 판정은 전부 게임 기능, 미정값 고정 없음).

## 시스템
| id | role | 언제 | 배치 |
|---|---|---|---|
| mark_begin / mark_hold / mark_end | attached (phased) | 확산 이벤트마다 **도착 대상**에 새 cast | 도착 대상 발밑 + 바라보는 방향. 표식 = (0, 1.15, 0.36)·s (가슴 앞) |
| spread_g1 | link, 1회성 | 1차 확산(최초 대상 → 주변) 이벤트마다 1개 | source = 출발 대상 표식점, target = 도착 대상 표식점 (매 프레임) |
| spread_g2 | link | 2차 확산 | 〃 (잔광 짧고 옅게, 가시 2줄) |
| spread_g3 | link | 3차 이상 | 〃 (가장 짧고 옅게) |

- spread_gN 은 cast 때 자동 시작하지 않는다 — 모드가 확산 이벤트에서 직접 시작 (cast 와 같은 순간). 가시 궤적은 0.3 s 에 도착하고 그때 표식이 붙는다.
- 표식점 권장: 대상 발밑 + 0.64·키 위 + 0.36·s 앞 (몸통에 가려지지 않게). 출발점도 같은 규칙.
- `holdDuration` = 도착 대상이 받은 디버프의 **실제 남은 시간** − 0.6 s(sustainStart). 디버프가 여러 개면 가장 긴 남은 시간 권장. 시간이 30%씩 줄어드니 뒤 단계 표식은 자연히 짧게 남는다.
- 디버프가 일찍 사라짐/대상 사망 → cancel (mark_end 즉시). 같은 대상이 다시 전가받으면 기존 cast cancel + 새 cast.
- 런타임 입력 `targetHeight` = 도착 대상 키 (s = clamp(h/1.8, 0.5, 2.0)), 같은 이벤트의 spread_gN 에도 같은 값.
- 출발 대상의 디버프는 그대로 — 출발 매듭은 맥동만 하고 사라지며 "제거" 연출은 없다.

## 색
검은 가시 매듭 실루엣(alpha) + 틈새의 작은 발광 점(독 탁한 녹색 / 나약함 잿빛 / 구속 남색 / 화상 주홍)을 고정 혼합으로 텍스처에 구웠다.
실제 넘어간 디버프 종류에 따라 색을 바꾸는 입력은 스키마에 없다 (미지원 → 필요하면 디버프별 시스템 변형 추가로 대응 가능, 지금은 넣지 않음). 속박 등 추가 상태는 같은 공통 표식을 쓴다.

## 클라이언트 한계 대응
- 유지 조각 수명 60 s 제한 → mark_hold 의 매듭은 30 s 마다 같은 자리에 재발생(0.3 s 교차 페이드). 유지 시간 길이 제한 없음.
- features.required: linkTarget, pathFollow, trails, phasedSequence, stopTail, attachedFollow(고영향), bloom(고영향), instanceScale, stretchedBillboard 등. meshParticle/orbitalVelocity 미사용.

## 미리보기 (데모 전용)
1차/2차/3차 시나리오 (출발 A → 도착 B, 유지 시간 프리셋 4 / 2.8 / 2 s), 연쇄 시나리오 A→B→C→D (흐름 확인용, 표식은 마지막 대상만 표시 — 데모 한계).

## REDO 2026-10-25 — 바뀐 연출 (시스템 id·역할·sequence·scale·타이밍 그대로)
| id | role / phase | loop | duration |
|---|---|---|---|
| mark_begin | attached / begin | false | 0.6 (sustainStart 0.6, 표식 결합 +0.30 s) |
| mark_hold | attached / sustain | true | 10 (유지 조각 수명 31 s 1개 → hold 최대 30 s, onStop 0.5) |
| mark_end | attached / end | false | 0.1 |
| spread_g1 / g2 / g3 | link | false | 0.1 (가닥 도착 +0.29~0.30 s) |
- 표식: 평면 아이콘 대신 어두운 가시 매듭 코어(잉크 몸체·보라 테두리·빛나는 균열) + 저주 시길(끊긴 가시 원·역삼각·작은 디버프 원) + 서로 다른 축으로 기울어 반대로 도는 가시 링 2개(자이로) + 끊긴 원. 가시 조각이 궤도를 돌고, 독(녹)·화상(주홍) 디버프 모트가 반대 방향으로 돌며, 잉크 방울이 떨어지고 1.4 s마다 맥동한다.
- 결합(+0.30): 링·시길이 크게 조여들며 들어오고 조임 링·별 섬광·충격 링·잉크 조각·불꽃이 터진다. 해제(end): 링이 풀려 퍼지고 매듭이 흩어진다.
- 확산 link: 출발 가시 링·섬광·잉크 조각 + 어두운 잉크 가닥(잉크 꼬리)과 보라 빛 가닥이 함께 날아가고 경로 위 구슬이 켜지며, 도착 섬광·링·잔광. g2/g3는 같은 구성에서 투명도 0.75/0.55, 가닥 2개.
- 데모에 `markClose`(표식 정면 근접) 시나리오 추가 (데모 전용).
- 2차 (2026-10-25): duration보다 늦게 잡혀 재생되지 않던 burst(흡수·도착 섬광·구슬 등)를 창 안에서 생성해 투명하게 대기하도록 고쳤다. 보이는 타이밍은 같다.


## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change
