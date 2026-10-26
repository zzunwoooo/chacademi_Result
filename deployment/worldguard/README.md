# WorldGuard — 일반 유저가 블록을 못 부수게 (오픈 전 메모 #4)

명령 모음: [protect-build.txt](protect-build.txt) (콘솔에 붙여 넣기)

## 어디에
| 서버/월드 | 적용 |
|---|---|
| 학교 서버 (학교·마을 — 마을도 학교 서버에 있음) | **적용** |
| 튜토리얼 서버(들) | **적용** |
| 야생1·야생2 | **적용하지 않음** — 유저가 땅을 캐고 짓는 곳. 개인 보호는 "청크 점유" 쪽 기능이 맡을 예정 (아래 #88 참고) |
| 아레나(큐디치·결투) | 이 문서 범위 밖. 큐디치 플러그인이 경기 중 보호를 따로 한다 |
| 던전 | 이 문서 범위 밖 (기믹 동작 확인 후 따로) |

- 월드 전체를 막아도 되면 **방법 A** (`__global__` 에 플래그). 같은 월드에 열어 둘 곳이 섞여 있으면 **방법 B** (구역을 만들어 그 안만).
- 둘 다 OP는 막히지 않는다: OP는 모든 권한을 가지므로 WorldGuard 우회 권한(`worldguard.region.bypass.*`)도 가진다. 만약 OP도 막히면 그 OP가 게임 안에서 `/rg bypass` 로 우회를 켠다. (WorldGuard config 의 `disable-bypass-by-default` 가 true 로 바뀌어 있으면 이렇게 된다.)

## 막는 것 / 막지 않는 것
| 막음 (deny) | 이유 |
|---|---|
| `block-break` `block-place` | 블록 부수기·놓기 (물·용암 양동이 붓기 포함) |
| `lighter` `fire-spread` `lava-fire` | 부싯돌로 불 붙이기, 불 번짐 |
| `tnt` `creeper-explosion` `other-explosion` `ghast-fireball` `wither-damage` | 폭발로 지형 깨짐 |
| `enderman-grief` `ravager-grief` | 몹이 블록을 들고 가거나 부숨 |
| `block-trampling` | 밭 밟아서 망가뜨리기 |
| `entity-item-frame-destroy` `entity-painting-destroy` `item-frame-rotation` | 액자·그림 장식 부수기·돌리기 |

| 허용 (allow) | 이유 |
|---|---|
| `use` | 문·다락문·울타리 문·버튼·레버·압력판 |
| `interact` | 블록·엔티티 우클릭 전반: NPC(Citizens·ChacaNPC) 대화, 펫(MCPets), 상점 NPC, 책 블록(BookBridge), 기숙사실 문(Housing Doors), 스토리 조건(블록 우클릭), 던전 기믹(GimmickProtection 은 INTERACT 가 deny 면 기믹을 막음) |
| `chest-access` | 상자·통·셜커 상자 열기 |
| `item-pickup` `item-drop` | 아이템 줍기·버리기 |
| `sleep` | 침대 (부활 지점 정하기) |
| `ride` (방법 B만) | 탈것·빗자루 거치 등 |

**블록을 바꾸는 마법도 같이 막힌다**: 마법 애드온(magic-mythic)은 블록을 부수거나 놓기 전에 시전자 이름으로 가짜 BlockBreakEvent/BlockPlaceEvent를 보내 보고(`Targets.canBreak`·`canPlace`), WorldGuard가 거절하면 그 칸을 건너뛴다. 그래서 학교·마을에서 땅을 파는 마법·발판 마법 등은 일반 유저에게 블록을 바꾸지 않는다 (OP는 바뀜). 마법 자체(피해·이펙트)는 `chacademi-magic` 플래그만 따르므로 영향 없음.

**토네이도(계절 이벤트)**: `__global__`에 `block-break deny`가 있으면 그 월드에서는 블록을 부수지 않는다(`TornadoPlugin.isUnprotected`). 야생에는 넣지 않으므로 야생 토네이도는 그대로.

## 방법 B를 쓸 때 주의
- WorldGuard 구역은 "구성원이 아닌 사람"의 부수기·놓기·**문 열기·상자 열기까지** 기본으로 막는다. 그래서 `use`·`interact`·`chest-access`·`item-pickup` 등을 **반드시 allow** 로 적어야 한다 (txt 에 들어 있음).
- 던전 기믹을 그 구역 안에 둘 경우: 기믹 판정은 플레이어 없이 위치로만 묻는다(`GimmickProtection.query`: `chacademi-magic`·`build`·`interact` 중 하나라도 deny 면 막힘). 위 설정은 `build` 를 건드리지 않으므로 보통은 괜찮지만, **구역 안 기믹은 꼭 실제로 한 번 눌러 확인**한다.
- ItemsAdder 가구(앉기 등)는 ItemsAdder 가 WorldGuard 를 직접 본다. 구역 안 가구에 앉기가 막히면 ItemsAdder 설정의 WorldGuard 항목을 확인.

## 확인 순서 (OP 아닌 테스트 계정)
1. 블록 부수기·놓기 → 안 됨
2. 문·버튼·레버·상자 → 됨
3. NPC 클릭(대화·상점), 펫 클릭, 책 블록, 기숙사실 문 → 됨
4. 아이템 버리고 줍기 → 됨
5. 블록을 바꾸는 마법(예: 땅 파기·발판) → 블록은 안 바뀜
6. OP 계정으로 블록 부수기 → 됨

## 오너가 정할 것
- 학교 서버의 월드 이름·튜토리얼 서버 월드 이름 (명령의 `<월드>`).
- 학교·마을 안에 유저가 캐거나 심어도 되는 곳(밭·채집터)이 있는지 → 있으면 방법 B 의 작은 구역으로 연다.
