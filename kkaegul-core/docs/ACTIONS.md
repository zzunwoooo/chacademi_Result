# 액션 문법

API 번호, 상자 내용물, 세트, NPC 사용자 동작, 미니게임 보상, 행운상자 이벤트가 모두 이 한 줄짜리 액션을 씁니다.
config.yml 만 고쳐서 내용을 바꿀 수 있고, `/kg run <플레이어|*> <액션>` 으로 바로 시험할 수 있습니다.

## 대상 1명

| 액션 | 예 | 설명 |
| --- | --- | --- |
| `roulette <종류>` | `roulette big_increase` | 증감 룰렛 (config `roulette.*`) |
| `box <normal\|premium\|lucky\|random> [개수]` | `box premium 1` | 상자 아이템 지급 (random = 고급/일반 반반) |
| `set <normal\|premium>` | `set premium` | 상자 세트(1111 / 5555) 내용 그대로 지급 |
| `item <아이템id 또는 MATERIAL> [개수 또는 a-b]` | `item teleport_ticket 4-8` | 아이템 지급 (넘치면 우편함) |
| `mending [개수]` | `mending 1` | 수선 마법책 |
| `food` | | 콜라(11/11 근처엔 깨깨로) 3~11개 |
| `jail` / `harass` / `tour` / `death` | | 감옥 / 개인 괴롭히기 / 순회공연 / 즉사 |
| `invite` | | 랜덤 유저 1명을 대상 옆으로 강제 초대 |
| `mine` | | 보물광산으로 이동 |
| `coins <a-b>` / `money <a-b>` | `coins 5` | 깨굴코인 / 돈 |
| `gauge <+n\|-n\|a~b>` | `gauge -10~10` | 운빨 게이지 변경 |
| `msg <텍스트>` / `center <텍스트>\|<서브>` | `center &a축하!\|&f보너스` | 메시지 / 중앙 멘트 |
| `sound <사운드id>` | `sound minecraft:entity.player.levelup` | 소리 |
| `cmd <콘솔 명령>` | `cmd give {player} apple 1` | 콘솔 명령 ({player} 치환) |
| `chance <퍼센트> <액션>` | `chance 1 item SHULKER_BOX 1` | 확률 실행 |

## 전체 / 대상 없음

| 액션 | 설명 |
| --- | --- |
| `all <액션>` | 접속자 전원에게 |
| `random-player <액션>` | 접속자 1명 랜덤 |
| `harass-all` | 섭주 전체 괴롭히기 |
| `fever <diamond\|box> [초]` | 피버 시작 |
| `broadcast <텍스트>` | 전체 공지 |

## 아이템 id

`cola, kkaekkaero, inven_save_ticket, coin_pouch, teleport_ticket, return_ticket, gacha_protect, gauge_ticket, gauge_random,
farm_ticket, treasure_ticket, invite_ticket, land_ticket, fail_paper, normal_box, premium_box, lucky_box`

리소스팩 모델은 `assets/kkaegul/items/<id>.json` (item model 키 `kkaegul:<id>`). 콜라는 콜라캔, 나머지는 티켓 모양으로 만들면 됩니다.
