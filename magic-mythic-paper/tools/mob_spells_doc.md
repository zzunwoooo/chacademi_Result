# 몹 기술 습득 마법 300~323 (2026-10-19, Claude)

오너 요청: 오늘 만든 몹 스킬 중 기존 마법(1~299)과 겹치지 않는 것을 플레이어 마법으로. **해당 몹을 처치하면 5% 확률로 습득.**
브랜치 `claude/mob-telegraph-20261019`. 원본 표: `magic-mythic-paper/tools/mob_spells.py` (`python3 tools/mob_spells.py` → 아래 파일들, `python3 tools/spells.py` → 미몹 스킬·Bridge 라우트·조준 미리보기).

## 0. 먼저 읽을 것 (GPT)

1. **빌드·실게임 안 함.** 새 메커닉 `chamobshot`(MobShotMech), `chatelegraph warn=false / blink / tpme / hits=enemies` 를 플레이어 마법에도 그대로 씀. 호스팅에서 빌드·시험 필요.
2. **꺼진 채로 들어감 (의도).** `mana-spells.yml` 300~323 `enabled: false`, `spell-catalog.yml` `reviewed/enabled: false`, `phases: []`.
   - 이유: catalog 모드는 카탈로그 VFX 가 준비되지 않은 마법을 거부한다. 이 마법들의 모습은 **몹 기술 VFX 팩**(PC `차카데미 vfx_Claude\몹스킬\<몹>\기본기\*.zip`, 큰 기술 팩)을 메커닉이 직접 재생한다(`vfx=pack/system`, ReactionVfx·GameplayVisual).
   - 켜는 순서: 표의 VFX 팩이 모든 클라이언트에 배포됨 → catalog 항목 `preparation-status: mob-skill-vfx-direct` 처리 방법 결정(빈 phases 를 허용하거나, 시전 섬광 phase 하나 추가) → `reviewed/enabled: true` → mana-spells `enabled: true`.
3. **습득:** `magic-mythic-paper` `DiscoveryHooks` 가 `CA_Wild_*` 몹을 플레이어가 처치하면(`getKiller()`, 마법이든 근접이든) `kill.mob.<가족>` 을 1 올린다(색·이로치 무관, 가족 표 = `DiscoveryHooks.MOB_FAMILIES`). `discoveries.yml` 은 `kill.mob.<가족>: 1` + `chance: 0.05` → **처치할 때마다 5% 판정**. 같은 몹에서 마법 두 개(봉인의 술사·팬텀·해골술사·페가수스)는 각각 따로 5%.
   - 운영 `plugins/MagicDiscovery/discoveries.yml` 은 JAR 로 안 바뀜 → 병합 필요. 아이콘 `magiccodex:textures/spells/mob_skill/<id>.png` 는 아직 없음(도감 `icon: ''`).
4. **수치는 임시.** 피해 = Bridge `base-damage + power-ratio × 마력` (spell-runtime.yml), 마나·쿨 표 그대로. 밸런스 검토 전.
5. 다른 서버 YAML(`spell-runtime.yml`, `chacademi_spells.yml`, `mana-spells.yml`)도 운영 폴더에 직접 병합.
6. `hunting_pounce`·`roar_leap` 은 MythicMobs 기본 `leap` 을 씀 — 빗자루 비행 중 막는 SelfMech 규칙이 안 걸림. 필요하면 확인.

## 1. 마법표

{{TABLE}}

## 2. 기존 마법과 겹침 확인 (뺀 것)

- 드래곤·하늘뱀 브레스(64 드래곤 브레스), 땅 울림·대지 짓밟기(105 지진), 서리 진(78 서리 파열), 뿔 광선·빙핵 레이저(147·62), 깃털 탄막(119 가시 씨앗), 꼬리 회전(31 토네이도 스핀), 덩굴 채찍(42 화염 채찍), 바위·뼈화살 연사(99·28), 소환(154 소환장·104 그놈).
- 168~299 대조 후 추가로 뺀 것: 현혹(195 공포의 종), 나선 휘감기(205 소용돌이 중심), 별 낙하(198 공허의 입), 독약병(303 독 뱉기와 중복).
- 비슷하지만 남긴 것: 봉인진(186 손아귀 풀기는 1체, 이건 범위), 요정 가루(175 졸음은 1체), 끌어오기(172 그림자 교환은 자리 바꾸기), 연막 은신(257 모래 눈속임은 실명만).

## 3. 바뀐 파일

| 파일 | 내용 |
|---|---|
| `magic-mythic-paper/tools/mob_spells.py` (새) | 24종 원본 표 + 생성기 |
| `magic-mythic-paper/tools/spells.py` | `S += mob_spells.routes()`, 조준 미리보기에 chatelegraph/chamobshot 모양 |
| `magic-mythic-paper/mythicmobs/Skills/chacademi_spells.yml`, `server-config/spell-runtime.yml`, `SPELLS.md` | 재생성 (CHA_<id> 24개, 라우트 24개) |
| `magic-mythic-paper/.../DiscoveryHooks.java` | `kill.mob.<가족>` 신호 + `MOB_FAMILIES` |
| `magic-codex-fabric/.../default-spells/300~323_*.yml`, `index.txt`, `known-hashes.tsv` | 도감 항목 (설명·획득 문구·연구 일지·마나·쿨·`마법 <id>`) |
| `magic-codex-fabric/.../spell-targeting.json` | 조준 미리보기 재생성 |
| `magic-codex-paper/src/main/resources/mana-spells.yml` | 300~323 (enabled false) |
| `portable-vfx-runtime/paper/src/main/resources/spell-catalog.yml` | 300~323 (reviewed/enabled false) |
| `magic-discovery-paper/src/main/resources/discoveries.yml` | 300~323 획득 조건 (`kill.mob.<가족>` ×1, 5%) |
