# 랜덤 구조물 다이아몬드 블록 (오픈 전 메모 #67)

## 저장소에서 찾은 것
- **구조물을 만드는 플러그인 코드는 없다.** 야생 몹 플러그인(creature-spawns-paper)은 구조물 위치를 *읽기만* 한다(`StructureIndex` — `chunk.getStructures()` 로 `chacademia:` 구조물 상자만 계산). 붙여 넣는 코드가 없으므로 플러그인 필터를 넣을 곳이 없다.
- 구조물은 **데이터팩** `Chacademia-Terralith-1.21.4-v3.zip` 이 월드 생성 때 놓는다 (`docs/handoff/CODEX_WILD_RESET_20261012.md`, `CLAUDE_WILDLIFE_SPAWNER_20261023.md`). 원본 스키매틱은 호스팅의 `plugins/FastAsyncWorldEdit/schematics/1~21.schem` 21개, 이것을 잘라 만든 구조물 템플릿 585개(`chacademia:natural/natural_XX_rYY_cZZ`)가 데이터팩 안에 있다 (`reference/output/wildlife-spawns-v2/structure-audit.json`).
- **이 저장소에는 .schem·.nbt·데이터팩 ZIP 이 하나도 없다** (모든 브랜치 확인). 그래서 다이아몬드 블록 개수는 여기서 셀 수 없었다 → 호스팅에서 아래 스크립트로 센다.

## 세기 · 지운 사본 만들기 — [diamond_filter.py](diamond_filter.py)
파이썬 3 표준 라이브러리만 쓴다. **원본은 고치지 않고 항상 새 파일로** 쓴다. 다이아몬드 블록을 팔레트에서 다른 블록(기본 `minecraft:air` = 비움, 오너 2026-10-26)으로 바꾼다 — 위치·다른 블록·상자 내용은 그대로.

```
# 1) 개수만 보기 (아무것도 안 씀)
python deployment/structures/diamond_filter.py count  "<야생 월드>/datapacks/Chacademia-Terralith-1.21.4-v3.zip"
python deployment/structures/diamond_filter.py count  "plugins/FastAsyncWorldEdit/schematics"

# 2) 지운 사본 만들기 (다른 블록으로: --to minecraft:cobblestone 등)
python deployment/structures/diamond_filter.py strip  "<…>/Chacademia-Terralith-1.21.4-v3.zip"  "Chacademia-Terralith-1.21.4-v3-nodiamond.zip"
python deployment/structures/diamond_filter.py strip  "plugins/FastAsyncWorldEdit/schematics"  "schematics-nodiamond"
```
- 데이터팩 ZIP 안의 `data/*/structure/**.nbt`(1.21 이름)와 `structures/`(옛 이름)를 모두 본다. 다른 파일은 그대로 복사.
- Sponge 스키매틱 v2·v3(`.schem`)도 읽는다.
- 깨진 파일은 "읽기 실패"로 표시하고 나머지는 계속한다.
- 합성 데이터로 검사함: 구조물 템플릿 3개 → 0개, 스키매틱(돌이 이미 팔레트에 있는 경우 번호 합치기 포함) 3개 → 0개, ZIP 안 다른 파일 보존.

## 적용 (호스팅 · GPT)
1. `count` 로 데이터팩 ZIP 의 개수를 보고 기록한다.
2. 개수가 0이 아니면 `strip` 으로 `-nodiamond.zip` 을 만든다.
3. 서버를 끈 뒤 야생 월드 `datapacks/` 의 v3 ZIP 을 **백업 폴더로 옮기고**(지우지 말 것) 새 ZIP 을 넣는다. 같은 데이터팩 두 개를 같이 켜지 말 것.
4. 서버를 켜고 `/datapack list enabled` 로 새 ZIP 하나만 켜졌는지 확인.
- **이미 만들어진 청크**의 구조물에는 적용되지 않는다 (새로 생성되는 청크만). 이미 생성된 곳의 다이아몬드 블록을 지울지는 오너 결정 — 메모 정책상 "플레이어 소유 블록이나 월드 전체 제거가 아님".
- 원본 `.schem` 21개를 고친 사본은 다음에 구조물을 다시 잘라 만들 때 쓰는 용도다 (데이터팩 재생성 시).

## 오너가 정할 것
- ~~다이아몬드 블록 대신 넣을 블록~~ → **비움(공기)** 으로 결정 (2026-10-26).
- ~~이미 생성된 야생 청크~~ → 야생을 새로 밀 예정이라 상관없음 (2026-10-26).
