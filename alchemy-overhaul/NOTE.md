# feature/alchemy-overhaul-20261031 — 연금술 개편 (기능 먼저, 레시피는 나중)

신기능 세션 작업. 서버·런처 반영은 규칙 세우기 세션이 한다. 바탕 = `origin/source`.
게임 안에서는 확인하지 않았다 (컴파일·바이트코드 비교·통신 형식 왕복 시험만).

## 무엇이 바뀌나 (오너 결정 2026-10-31)
| 기능 | 내용 |
|---|---|
| 연금 쪽지 | `item:alchemy_note_1~4` (일반·고급·유일·전설) 우클릭 → 아직 발견하지 않은 레시피 하나의 힌트. 1장 = 재료 1개, 2 = 재료 2개, 3 = 재료 전부(개수 숨김), 4 = 재료·개수·결과 전부. 처음 보는 레시피를 먼저 고른다. 새로 알 게 없으면 쪽지를 쓰지 않는다. |
| 연금 수첩 | 연금술 창 왼쪽 위 책 버튼 → 모은 쪽지 목록 + 고른 쪽지를 등급 쪽지 그림 위에 펼쳐 보여 줌. 쪽지를 읽으면 수첩이 그 쪽지를 펼친 채로 열림. 학교·야생 공유 (MariaDB `chaca_alchemy_notes`). |
| 단계별 근접 문구 | 틀린 조합이고 기존 "거의 맞음" 힌트도 없을 때, 아직 모르는 레시피와 재료가 몇 종류 겹치는지(1·2·3개 이상)에 따라 문구. |
| 최초 발견 칭호 + 공지 | 레시피에 `first-title: <titles.yml 칭호 ID>` 를 적으면, 서버 전체에서 처음 발견한 한 명만 칭호를 받고 모든 서버 채팅에 공지: **"OO님이 최초로 OOO의 연금 조합식을 발견하여 OO칭호를 획득하였습니다."** (오너 문구 그대로). 개편 전에 누가 이미 발견한 레시피(LuckPerms 권한 보유자 있음)는 아무도 받지 않는다. 기록 = MariaDB `chaca_alchemy_first`. |
| 한 번만 만드는 레시피 | `once: true` → 한 번 만든 사람은 다시 만들 수 없다 ("이 조합식은 한 번만 만들 수 있습니다."). 장신구 1회 지급용. |
| 버그: 장비 아이템이 기본 아이템으로 나옴 | 연금술 편집기에 장비로 등록한 아이템을 결과로 넣으면 아이템 종류만 저장돼 등록 정보가 빠졌다. 이제 편집기가 그 아이템을 통째로 `result-item` 에 저장하고 제작 때 그대로 복사한다. **기존 레시피는 등록된 아이템을 인벤토리에 넣고 편집기에서 한 번 다시 저장해야 고쳐진다.** |
| 버그: 장비창 이름이 "종이" | 서버 `EquipPreview`: 아이템 정보가 512바이트를 넘을 때 이름을 `[이름]+마우스 정보` 통째로 넣어서 또 넘치고, 이름 없는 단계로 떨어졌다. 이제 이름만 옮긴다. |

## alchemy.yml 에 새로 쓸 수 있는 칸 (전부 선택)
```yaml
messages:
  match-1: 마법진이 아주 희미하게 떨린다. 재료 하나가 무언가에 닿은 것 같다…   # 비우면 이 기본 문구
  match-2: 마법진이 잠깐 빛나다 사그라든다. 재료 두 가지가 서로 끌리는 것 같다…
  match-3: 마법진이 크게 일렁인다. 여러 재료가 맞물리는 느낌이다…            # 3개 이상
  once: 이 조합식은 한 번만 만들 수 있습니다.
recipes:
  recipe_xx:
    first-title: prefix_alchemy_xxx   # titles.yml 에 있고 켜진 칭호만 (없으면 콘솔 경고, 지급 안 함)
    once: true                        # 한 번만
    note: false                       # 연금 쪽지에 안 나옴 (기본 나옴)
    hint: 붉은 빛을 내는 무언가와 물가의 풀이 섞이면…   # 쪽지 맨 위 문장 (없으면 재료 목록만)
    result-item: <편집기가 자동 저장>
```
alchemy.yml 은 학교·야생이 공유 DB 로 같이 쓴다 → 한쪽에서 고치고 `/연금술관리 새로고침`.

## 반영 방법 (규칙 세우기 세션) — 재시작 필요
### 서버: MagicCodexBridge jar (학교·야생1·2·아레나1·2 전부)
- `server-jar-add/` 의 클래스를 각 서버 jar 에 넣는다 (`jar uf <jar> -C server-jar-add .`).
  - `AlchemyBridge`·`AlchemyEditor`: 학교·야생·아레나 배포 jar 셋 다 서로 같고, **이 브랜치 소스에서 표시 부분을 뺀 것 = 배포본** (javap 메서드별 비교로 확인; 소스 브랜치에 빠져 있던 우편 부분을 다시 넣음). → 통째로 교체해도 된다.
  - `AlchemyPlus`·`AlchemyNoteProtocol*`: 새 클래스. `EquipPreview`: 배포본(소스 없음)을 그대로 옮기고 이름 부분만 고침.
  - 세 jar 기준으로 각각 컴파일해 결과 클래스가 바이트 단위로 같음.
- 표시: `// [기능:alchemy-overhaul 시작/끝]`.
### 클라이언트 모드 (런처)
- `client-jar-add/` 를 **지금 배포 중인 클라이언트 jar** (`MagicCodex-client-6cfa5a95-elena-journal-acctip-spyglass-shop-aim2.jar`) 에 넣는다 (`jar uf <jar> -C client-jar-add .`) → 새 이름으로 릴리스.
  - 클래스: `AlchemyClient`·`AlchemyScreen` (교체), `AlchemyNoteClient*`·`AlchemyNoteScreen*`·`AlchemyNoteProtocol*` (새로). 텍스처 `assets/magiccodex/textures/gui/alchemy_note/` 11장.
  - 배포 jar 의 Alchemy* 클래스 = magiccodex-source `6cfa5a95` 로 빌드한 것과 javap 완전 동일 확인 → 이 둘만 교체하면 됨. `MagicCodexClient` 는 안 건드림 (수첩 통신은 AlchemyClient 에서 등록).
  - 빌드: magiccodex-source 6cfa5a95 + 이 변경, `remapJar` 성공.
- 모드를 안 바꾼 유저: 쪽지를 읽으면 채팅으로 힌트가 나온다 (수첩 버튼만 없음).
### 아이템
- `all-servers/plugins/ItemsAdder/...` (쪽지 4종, 앞 커밋) → 5대 복사 → `/iazip`.

## 오너가 정할 것 / 남은 것
- 레시피별 최초 발견 칭호: titles.yml 에 칭호를 만들고 레시피에 `first-title` (레시피 개편 때 같이).
- 연금 쪽지 얻는 곳 (몬스터 드롭·의뢰 보상·호감도 퀘스트 등) — 아직 아무 데서도 안 나옴.
- 한 번만 레시피(장신구) 실제 등록 — 레시피 개편 때.
