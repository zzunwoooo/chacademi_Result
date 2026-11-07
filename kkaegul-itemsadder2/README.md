# 운빨깨굴랜드 아이템 — 기존 ItemsAdder contents 에 합치기

기존 서버의 `item` 네임스페이스(resourcepack/assets 구조, 설명 양식)에 맞춘 버전.

## 넣는 법
`contents/item/` 안의 두 폴더를 기존 `plugins/ItemsAdder/contents/item/` 에 그대로 덮어 넣기 (기존 파일은 안 건드림)
- `configs/kkaegul/items.yml`, `configs/kkaegul/categories.yml`
- `resourcepack/assets/item/textures/kkaegul/*.png` (17장)
그다음 `/iazip`

기존 아이템 id(invensave, invite, return, tpicon, chunk, chest_1 …)와 겹치지 않게 새 id 를 씀.

## 양식 (기존 아이템과 동일)
- 이름: `&f⨄/⨇/⨊ 이름` (emote 1~3성)
- 설명: 빈 줄 → `&7| 타입 : &f…` → 빈 줄 → `&7| &f⨃ &7: 기능` / 이어지는 줄은 공백 3칸 / 보충은 `&8(…)`

| id | 등급 | 이름 |
| --- | --- | --- |
| item:cola | ⨄ | 깨굴 콜라 |
| item:kkaekkaero | ⨄ | 깨깨로 |
| item:inven_save_ticket | ⨇ | 인벤세이브권 |
| item:teleport_ticket | ⨇ | 텔레포트권 |
| item:return_ticket | ⨇ | 귀환권 |
| item:gacha_protect | ⨇ | 가챠 파괴 방지권 |
| item:farm_ticket | ⨇ | 농사 금액 변동권 |
| item:treasure_ticket | ⨊ | 보물광산 이동권 |
| item:gauge_ticket | ⨇ | 게이지 변동권 |
| item:gauge_random | ⨄ | 랜덤 게이지 변동권 |
| item:invite_ticket | ⨊ | 초대권 |
| item:land_ticket | ⨇ | 땅 구매권 |
| item:coin_pouch | ⨄ | 깨굴코인 주머니 |
| item:normal_box | ⨄ | 일반 상자 |
| item:premium_box | ⨇ | 고급 상자 |
| item:lucky_box | ⨊ | 행운 상자 |
| item:fail_paper | ⨄ | 꽝 종이 |

카테고리: 음식 / 티켓 / 상자·재화 (`ia.menu.seecategory.kkaegul_food|kkaegul_tickets|kkaegul_boxes`)
