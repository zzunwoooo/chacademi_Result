# 동행 펫 전환 — 서버 파일 교체 때 할 일 (오너 2026-10-26, 메모 #81)

커스텀 펫(ca_* 276개, 변색종 포함)은 이제 MCPets 몹을 쓰지 않는다. MagicCodexBridge 가 `pet-companions.yml` 로 목록·이름·아이콘·보유 권한·능력을 직접 다루고, 클라 모드가 펫을 그린다. 바닐라 펫(ChAcademiaTaming / ChAcademiaShiny)은 예전 MCPets 그대로.

## 학교 · 야생 · 아레나 각각 (서버를 정상 종료한 뒤)
1. **지우기:** `deployment/mcpets-companion-removal.json` 의 `delete` 목록 276개
   - 위치: `plugins/MCPets/Pets/ChAcademiaCustom/ca_*.yml`
   - 지우기 전에 한 번 백업해 둔다.
2. **그대로 두기:**
   - `ChAcademiaTaming`, `ChAcademiaShiny` 폴더
   - MythicMobs `CA_Pet_*` 몹 정의 (변신 가면이 씀)
   - 플레이어 권한 `chacademia.pet.*` (보유 기록이 그대로 이어짐)
3. **새로 넣기 / 덮어쓰기** (`deployment/operating-config`, manifest sha256 확인):
   - `MagicCodexBridge/pet-companions.yml`
   - `MythicMobs/Skills/chacademi_pet_skills.yml` (펫 스킬 202개, 미몹 `/mm reload` 대신 재시작)
4. **같이 배포할 것:**
   - MagicCodexBridge jar
   - magic-codex-protocol 을 쓰는 클라 모드 (`magiccodex:pet_companions` 채널, 펫 창 Entry 버전 02 — 서버·클라를 같이 올려야 펫 창이 열림)
   - PortableVFX 팩 `PetPaw` (portable-vfx-runtime/assets/PetPaw)
5. 예전에 MCPets 로 꺼내 둔 커스텀 펫 몹이 남아 있으면, 서버 재시작 때 MCPets 가 정리한다 (정의 파일이 없으면 다시 소환하지 않음).

## 데이터 다시 만들기
`cd magic-mythic-paper && python3 tools/pet_companions.py` → `pet-companions.yml`(소스·운영 둘 다), `chacademi_pet_skills.yml`(소스·운영 둘 다), `deployment/mcpets-companion-removal.json`.
그다음 `python3 tools/operating_overlay.py regenerate` 와 `check` 를 실행한다.
