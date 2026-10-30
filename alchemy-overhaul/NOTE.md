# feature/alchemy-overhaul-20261031 — 연금술 개편 (기능 먼저, 레시피는 나중)

신기능 세션 작업. 서버 반영은 규칙 세우기 세션이 한다. 바탕 = `origin/source`.

## 1. 연금 쪽지 아이템 (ItemsAdder) — 완료
`all-servers/plugins/ItemsAdder/contents/item/` 를 5대 서버(학교·야생1·2·아레나1·2) 같은 경로에 그대로 복사.
- `configs/chacademi_alchemy_notes.yml` (새 파일, namespace `item`)
- `resourcepack/assets/item/textures/chacademi/items/alchemy_note_1~4.png` (256×256, 오너 제작)

| ID | 이름 | 등급 |
|---|---|---|
| `item:alchemy_note_1` | 낡은 연금 쪽지 | 일반 |
| `item:alchemy_note_2` | 연금 쪽지 | 고급 |
| `item:alchemy_note_3` | 봉인된 연금 쪽지 | 유일 |
| `item:alchemy_note_4` | 대마법사의 연금 쪽지 | 전설 |

반영: 파일 복사 → `/iazip` → 리소스팩 다시 받기. 확인: `/iagive <닉> item:alchemy_note_1 1`.
※ 우클릭 기능은 아래 2번(코드)이 들어가야 동작한다. 그 전에는 아이템만 존재.

## 2. 기능 코드 — 진행 중
(쪽지 우클릭 힌트·연금 수첩·단계별 근접 문구·최초 발견 칭호와 공지·최초 1회 레시피·장비 아이템 버그·장비창 이름 버그)
