# 운빨깨굴랜드 ItemsAdder 아이템 (namespace: item)

## 설치
1. `contents/item` 폴더를 `plugins/ItemsAdder/contents/` 안에 넣기
2. `/iazip` 실행 (리소스팩 다시 생성)
3. `/ia` 메뉴에서 카테고리 3개 확인: 음식 / 티켓 / 상자·재화
   - 카테고리 보기 권한: `ia.menu.seecategory.kkaegul_food`, `ia.menu.seecategory.kkaegul_tickets`, `ia.menu.seecategory.kkaegul_boxes`

## 아이템 17종 (id = item:<이름>)
| id | 이름 | 기능 (KkaegulCore 가 처리) |
| --- | --- | --- |
| item:cola | 깨굴 콜라 | 음식 (꿀병처럼 마심, 빈 병 안 남음). API 33 으로 3~11개 |
| item:kkaekkaero | 깨깨로 | 음식 (쿠키). 11/11 근처엔 콜라 대신 지급 |
| item:inven_save_ticket | 인벤세이브권 | 죽을 때 1장 자동 사용 → 아이템·레벨 유지 |
| item:teleport_ticket | 텔레포트권 | 우클릭 → 유저 선택 창(또는 /유저텔포 <이름>) → 이동 |
| item:return_ticket | 귀환권 | 우클릭 → 끌려가기 전 자리로 |
| item:gacha_protect | 가챠 파괴 방지권 | 가챠에서 곡괭이가 부서질 때 자동 1장 사용 |
| item:farm_ticket | 농사 금액 변동권 | 우클릭 → 작물 시세 즉시 변경 |
| item:treasure_ticket | 보물광산 이동권 | 우클릭 → 보물광산 이동 |
| item:gauge_ticket | 게이지 변동권 | 우클릭 +10 / 웅크리고 우클릭 -10 |
| item:gauge_random | 랜덤 게이지 변동권 | 우클릭 → -10~+10 |
| item:invite_ticket | 초대권 | 우클릭 → 랜덤 유저를 내 옆으로 강제 초대 |
| item:land_ticket | 땅 구매권 | 우클릭 → config land.* 에 연결한 땅 명령 실행 |
| item:coin_pouch | 깨굴코인 주머니 | 우클릭 → 코인 3~8개 |
| item:normal_box / premium_box / lucky_box | 상자 3종 | 우클릭 → 열기 (행운상자는 전체 이벤트) |
| item:fail_paper | 꽝 종이 | 5장 → /접속보상 교환 으로 고급상자 |

상자는 PAPER 기반이라 바닥에 설치되지 않음.

## 그림
- 17종 모두 확정 그림 (128×128). 바꿀 땐 `textures/item/<id>.png` 교체 후 `/iazip`

## 플러그인 연결
KkaegulCore config `items.use-itemsadder: true` (기본) — ItemsAdder 가 있으면 지급·인식 모두 item:<id> 로 처리.
테스트: `/kg give <플레이어> <id> [개수]`  예) `/kg give 준우 lucky_box 3`
