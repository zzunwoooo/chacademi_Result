# 168~214 최종 기획 신규 구현 (Claude, 2026-10-19~20)

기획: `차카데미 전체 마법 기획 238종` 최종안. 오너 지시: "이미 생성되어 있는 마법 말고 없는 것들 제작", 미정 값은 **기존 체계 기준 임시값**,
다른 플러그인·모드 연동이 필요한 것은 **보류 후 보고**. 212 불꽃 줄넘기 피해는 기획에 "임의 수치 제안 금지" → 0 으로 비워 둠 (오너 결정).

자동 생성: `python3 magic-mythic-paper/tools/arcana_spells.py` (이 문서 / default-spells / mana-spells / spell-catalog / spell-bindings) →
`python3 magic-mythic-paper/tools/spells.py` (미몹 스킬 yml / spell-runtime.yml / SPELLS.md / spell-targeting.json).

## 구현표

{{TABLE}}

## 보류 (연동 필요)

{{SKIP}}

## 구현 메모 (GPT 확인)

- 게임 기능: `ArcanaMech.java` (`chaarcana{type=...}`, 상태는 `ArcanaMech.State` 리스너), 176/182/198 은 기존 `chahit`, 196 영창은 `chachannel` → `CHA_eclipse_eye_zone`.
  177/193 은 `Passives.java` (`config.yml passives.hungry_shadow / inverted_offering`).
- **빌드 미검증** (클라우드에 Paper/MythicMobs 의존성 없음). 호스팅 `Build-Server.ps1 -Verify` 로 컴파일 확인 필요.
- 신규 API: `MagicEchoEvent` (169: 퀘스트·던전 플러그인이 `setClue(문구)` / `setHandled(true)`), `StatusEffects.active(entity)` (178 확산용).
- VFX: 45개 PC 팩 (`차카데미 vfx_Claude`) 이 클라이언트에 있어야 catalog 가 ready. **175 졸음 VFX 팩 없음 → enabled:false** (팩 만든 뒤 `orbvfx=` 와 phases 추가).
  207 떠도는 연금실도 팩 없음 (보류 목록).
- 서버가 움직이는 투사체 VFX (`GameplayVisual.cast`): 192 `woundaccounting/projectile`, 196 `eclipseeye/shardMoon|shardFragment`, 213 `dropletrelay/projectile`.
- 168 분신: 첫 시전 직후 Bridge 쿨을 지움(`Links.clearCooldown`), 복귀 시 재시전 마나 환급. 분신이 그냥 사라지면 쿨은 애드온이 따로 25초 막음
  (Bridge 쿨 API 에 "쿨 시작" 이 없어서). Bridge 쿨 API 없으면 첫 시전 후 쿨 25초가 그대로 걸려 복귀 불가 → 확인 필요.
- 173 계약: Bridge mana-cost 0, 메커닉이 최대 마나 70% 차감. 회복 금지 = `Links.regen(id, "forbidden_contract", 0)`.
- 180 사슬 VFX: link 시작점이 이벤트 지점(시전 순간 위치) 고정 → 몹이 움직이면 사슬 한쪽 끝이 따라가지 않음. 따라가게 하려면 PortableVFX 에 entity→entity link 필요.
- 185 팬텀: `chacademia_summon` 태그, 주인·아군·다른 소환수 공격 안 함, 햇빛 안 탐, 드롭/경험치 없음.
- 194 유령 도구: `Attribute.BLOCK_INTERACTION_RANGE` +27 (BuffMech.clear 목록에 추가함).
- 203: BroomLink.riding 만 사용 (모드 쪽 추가 기능 불필요). 속도 유지 텔레포트.
- 210: `BlockReceiveGameEvent` 의 STEP 만 취소. 발별 패드(link) VFX 는 발 위치 입력이 없어 생략, 발자국 `step` 만 재생.
- 211: `EntityDamageByBlockEvent`(덤불) 취소 + Paper `EntityInsideBlockEvent` 취소 (감속).
- 214: VFX 팩의 넘김 시간 1.3초 기준 (기획 2초). 바꾸려면 팩도 같이 수정.
- 패시브 177/193 VFX: 팩이 link 시스템이라 시전 중이 아닐 때는 재생 안 됨 (Links.vfx 는 시전 중 cast 에만 전달). 필요하면 정적 시스템 추가 요청.
- 기획안은 300~323 몹 습득 마법을 215~238 로, 기존 200~219 바람 계열(비활성)을 목록에서 뺐음. 번호 재배치·삭제는 **하지 않음** (오너 결정 필요).
  새 200번대(203, 210~214)는 기존 파일과 order 가 겹침 (`203_air_brake` 와 `203_landing_airbrake` 등) - 정렬용이라 동작 문제는 없음.
- **클라이언트 도감 갱신**: `DefaultSpellMigration` 은 101~167 만 기존 파일을 덮어씀 (정규식 `10[1-9]|1[1-5][0-9]|16[0-7]`). 168~199 는 이미 깔린
  클라이언트 파일이 새 설명/마나로 안 바뀜 → 정규식을 168~214 까지 넓히고 `VERSION` 올리기 필요 (known-hashes.tsv 에 옛 해시는 남겨 두고 새 해시 추가함).
- 습득(discoveries.yml) 조건은 다음 작업 (기획 발견 조건 문구는 도감 discovery 설명에 넣어 둠).
