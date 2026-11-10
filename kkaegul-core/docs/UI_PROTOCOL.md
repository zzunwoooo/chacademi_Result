# UI 프로토콜 (서버 ↔ 고화질 렌더러)

> 실제로 만들 화면은 docs/UI_LIST.md 의 10개뿐이다. 렌더러가 그리는 화면은 config `ui.renderer-screens` (기본: hud, gauge, diamond, fever, gacha, menu, player_select) 이고, 그 밖의 open/update 는 서버가 보내지 않고 중앙 멘트·채팅으로 대체한다. 아래 표의 다른 화면은 참고용.

**툴팁**: `hud` 와 `gacha` data 의 `tips{요소id: 문구}` (이미 색코드 §, 줄바꿈 \n). 채팅/인벤토리 등으로 커서가 보일 때 해당 아이콘에 마우스를 올리면 오버레이로 표시. HUD 키: target, held, coin, gauge, protection(보호 중일 때만), fever(피버 중일 때만), jail(감옥일 때만). 가챠 키: fortune, efficiency, unbreaking, destroy, protect, cost.

**menu** 화면 data: `tab`(me/playtime/titles/hall), `me`, `playtime`, `titles{rows}`, `hall{boards}` / 액션 `menu.open tab=...`

코어는 UI를 그리지 않습니다. 화면에 필요한 값은 전부 아래 JSON으로 클라이언트 모드(차카데미 렌더러 방식)에 보냅니다.
렌더러 규격이 다르면 `ui/UiBridge.java`의 `send()` 하나만 바꾸면 됩니다.

- **서버 → 클라**: 플러그인 메시지 채널 `kkaegul:ui`, UTF-8 JSON 1개
- **클라 → 서버**: 채널 `kkaegul:action`, 평평한 JSON 객체 `{"action":"gacha.spin","machine":"2"}`
- 클라가 `kkaegul:ui` 채널을 등록하지 않았으면(렌더러 없음) 타이틀/액션바/채팅으로 대체 출력합니다.
- 렌더러 없이 액션 테스트: `/kg ui <액션> 키=값 ...` (예: `/kg ui gacha.spin machine=3`)

## 서버 → 클라 메시지 종류 (`type`)

| type | 필드 | 용도 |
| --- | --- | --- |
| `open` | `screen`, `data` | 화면 열기 |
| `update` | `screen`, `data` | 열린 화면/HUD 값 갱신 |
| `close` | `screen` | 화면 닫기 |
| `center` | `text`, `sub`, `ms` | 화면 중앙 멘트 (모든 "멘트"는 이걸로 옴, `&` 색코드 포함) |
| `toast` | `text` | 짧은 알림(액션바 대체) |
| `roulette` | `kind`, `label`, `increase`, `min`, `max`, `rolls[]`, `total`, `extra`, `spinMs`, `fever` | 다이아 증감 룰렛 연출. `spinMs` 동안 돌리고 `rolls` 결과로 멈추면 됨. 장부 반영은 서버가 `spinMs` 뒤에 함 |
| `fx` | `id`, `data` | 일회성 연출 |
| `names` | `map{uuid:{name,title}}` | 머리 위 이름표용 닉네임/칭호 (접속·닉변·칭호 변경 시 전체에게) |
| `cosmetic_defs` | `defs{치장품id:{slot,name,description,rarity,icon}}` | 치장품 정의 (접속 30틱 뒤 렌더러 있는 클라에게, /깨굴 리로드 때 전원). icon = ItemIcon 문자열, IA 에 없으면 "" |
| `cosmetics` | `full`, `players{계정명 소문자:{slot:id}}` | 착용 상태. full=true: 접속 때 본인에게 전원 상태(입은 게 없는 사람은 빠짐) / full=false: 바뀐 사람 한 명의 현재 슬롯 전부를 전원에게 (`{}` = 다 벗음) |

## 화면 (`open` / `update` 의 `screen`)

| screen | 언제 | data 주요 필드 |
| --- | --- | --- |
| `hud` (update만) | 2초마다 + 값 바뀔 때 | `debt`(목표=남은 다이아), `held`(보유 다이아), `gauge{value,max,tier}`, `coins`, `money`, `title`, `fever{diamond,triple,box}`(남은 초), `day`, `protected`, `jail{done,required}` 또는 null, `mail` |
| `gauge` (update) | 게이지 변화 | `value`, `max`, `tier` (low/mid/high/jackpot) |
| `diamond` (update) | 장부 변화 | `debt`, `delta`, `reason`, `protected` |
| `fever` (update) | 피버 상태 | `diamond`, `triple`, `box` (남은 초) |
| `gacha` | 가챠 NPC / 스핀 후 갱신 | `machine`, `label`, `max`, `cost`, `destroyChance`, `coins`, `holdingPickaxe`, `stats{fortune,efficiency,unbreaking}`, `protectTickets` |
| `jail` | 감옥 입장, 운동마다 갱신 | `inJail`, `done`, `required`, `exitReady`, `count` |
| `jail_exit` | 출구 NPC | 위와 같음 → 버튼 2개: `jail.exit choice=lobby` / `choice=return` |
| `wasd` | WASD 챌린지 | `keys`("W A S D ..."), `seconds`, `index`(update) |
| `quiz` | 거탐 퀴즈 | `category`, `question`, `seconds` (답은 채팅) |
| `minigame` | 미니게임 중 | `game`(pepero/quizdoor/invite/treasure) + 게임별: 빼빼로 `partner,target,seconds,progress`, 퀴즈맵 `question,options[],seconds`, 보물찾기 `chests,seconds` |
| `fortune` | 오늘의 운세 | `good`, `stat`, `amount`, `seconds`, `message`, `usesLeft`, `gaugeDelta` |
| `profile` | 프로필 NPC | `name`, `voice`, `done` → `profile.save` / `profile.done` |
| `player_select` | 유저 텔포권 우클릭 | `purpose`("user_tp"), `players[]`(계정명), `labels[]`(닉네임) → `ticket.userTp target=계정명` |
| `nickname` | 첫 접속(닫기 불가) / 프로필 NPC / `/닉네임` | `account`, `nickname`, `min`, `max`, `first`, `title`, `rule` / update `{error, ok, checked}` → `nickname.check name=` / `nickname.save name=` |
| `gauge_ticket` | 게이지 변동권 우클릭 | `value`, `max`, `tier` → `ticket.gauge dir=up|down` |
| `playtime` | 접속 보상 | `todaySeconds`, `milestones[{id,label,needSeconds,claimed,ready}]`, `failPapers`, `exchangeNeed` |
| `titles` | 칭호 | `rows[{id,name,owned,selected,condition}]` |
| `hall_of_fame` | 명예의 전당 | `boards[{id,label,rows[{rank,name,value,title}]}]` (increased/breaks/deaths/remaining/donated/jail) |
| `donation_board` | 랜덤 제출 기부 랭킹 | `total`, `rows[{rank,name,amount}]` |
| `farm` | 작물 시세 | `prices{MATERIAL:가격}`, `nextMs` |
| `coin_shop` | 코인 상점 NPC | `price`, `money` → `shop.buyCoins amount=N` |
| `mail` | 우편함 | `items[{name,amount,material}]` / update `count` |
| `me` | 내 정보 | hud 필드 + 누적 통계 |
| `card_draw` | 카드뽑기 NPC | `cards`(장 수), `cost` → `card.pick index=N` |
| `wardrobe` | `/옷장`, NPC `wardrobe`, `wardrobe.open` / 장착·해제 뒤 update | `owned[id]`, `equipped{slot:id}`, `counts{owned,total}` (정의는 cosmetic_defs 로 받은 것) |

## 일회성 연출 (`fx` 의 `id`)

`gacha_result` / `gacha_jackpot`(개구리 축하) `{machine,max,before[3],after[3],fortune,efficiency,unbreaking,jackpot}` — 3칸 슬롯(행운/효율/내구성)이 after 숫자로 멈추면 됨, `gacha_destroy`, `gacha_protect`, `box_open{box,label,rewards[],fever}`,
`coin_buy{outcome,multiplier,amount,got}`, `random_submit{outcome,factor,used,credited,donated}`, `armor_roll{tier,message}`,
`jail_game{multiplier,label,base,amount}`, `mending_draw{win,milestone}`, `title_unlock{id,name,serverFirst}`,
`invite_letter{title,to,place|from}`(초대장), `card_reveal{picked,label,all[]}`, `box_open` 의 `box:"cosmetic"` (치장 상자) 는 rewards 에 `rarity` 가 더 붙음 (`rare` = rare|legendary), `graduate`, `fever_diamond`, `fever_box`, `fever_triple`

## 클라 → 서버 액션 (`action`)

| action | 인자 | 동작 |
| --- | --- | --- |
| `hud.request` | | HUD 즉시 갱신 |
| `npc` | `id` | NPC 동작 실행 (동작 목록: `/kg npc`) |
| `gacha.info` / `gacha.spin` | `machine`=1/2/3 | 가챠 정보 / 돌리기 |
| `submit.normal` / `submit.random` / `submit.board` | | 제출 / 랜덤 제출 / 기부 랭킹 |
| `jail.exit` | `choice`=lobby/return | 감옥 나가기 (return 은 50%) |
| `jail.game` | | 다이아 증가 게임 |
| `shop.sellAll` / `shop.sellHand` | | 판매 |
| `shop.buyCoins` | `amount` | 코인 구매 |
| `shop.exchangeDiamond` | `amount` | 다이아→코인 (기본 꺼짐) |
| `farm.info` | | 시세 |
| `mail.open` / `mail.claim` | | 우편함 |
| `playtime.open` / `playtime.claim` / `playtime.exchange` | | 접속 보상 |
| `title.open` / `title.select` | `id` (none=해제) | 칭호 |
| `hall.open` | | 명예의 전당 |
| `profile.save` / `profile.done` | `name`, `voice` | 프로필 |
| `ticket.userTp` | `target` | 유저 텔포권 사용 |
| `ticket.gauge` | `dir`=up/down | 게이지 변동권 사용 |
| `fortune.use` | | 오늘의 운세 |
| `wild.enter` / `wild.random` | | 야생 |
| `card.pick` | `index` (0부터) | 카드뽑기 카드 고르기 |
| `me` | | 내 정보 화면 |
| `nickname.check` / `nickname.save` / `nickname.open` | `name` | 닉네임 중복·규칙 확인 / 저장 / 창 열기 |
| `wardrobe.open` / `wardrobe.equip` / `wardrobe.unequip` | `id` / `slot`(head·face·back·offhand·balloon) | 옷장 열기 / 가진 것 장착(같은 부위 교체) / 부위 해제 |

## 렌더러 쪽에서 해야 할 것 (체크리스트)

1. 접속 시 `kkaegul:ui` 채널 등록(minecraft:register) → 서버가 렌더러 있다고 인식
2. `hud` 를 왼쪽 운빨 게이지 HUD로 상시 표시
3. `center` 멘트를 화면 가운데에 표시 (`&` 색코드 처리)
4. `roulette` 연출 (`spinMs` 기준, `extra`>0 이면 1+1 / 1+2 표시)
5. 위 화면들 + 버튼 → `kkaegul:action` 전송


## 후원 연동 (Colla-Bot mc-donation, donation-bukkit 2.x)

`donation` 플러그인이 있으면 자동 연동 (`api/DonationHook`). 방송인 클라 모드가 받은 후원 → `DonationEvent` → 별풍선 개수(치지직은 치즈 ÷ 100)를 API 번호로 실행.
- 받은 사람이 `donation.server-owners` 에 있으면 섭주 API(`api.server.N`), 아니면 그 사람 개인 API(`api.personal.N`)
- 등록 안 된 개수는 무시(로그). donation 플러그인 기본 동작(콘솔 `api ...` 명령)은 취소
- 설정: config `donation:` 섹션
