# 보류 아티팩트 29개 — 그룹 C "마법 규칙" (2026-10-26)

대상 5개: 순서의 목걸이(sequence_necklace) · 무지개 프리즘(rainbow_prism) · 혼돈의 주사위(chaos_dice) · 단 하나의 서(the_only_tome) · 거꾸로 된 마나병(inverted_mana_flask).
`artifacts.yml`(magic-codex-paper 리소스)에서 5개의 `enabled: false` 줄을 지웠다. 맨 위 "121개 구현" 주석은 그대로.

## 키세팅을 서버가 아는 방법 (중요)
`GeneralPresetBridge`(PDC normal_magic_preset)는 **어느 프리셋(1/2/OFF)을 골랐는지만** 안다. 칸에 든 마법은 모른다.
그래서 이미 있던 경로를 썼다: MagicCodex 클라이언트가 `chacademia:spell_slots` 로 지금 쓰는 6칸(HUD 순서, 빈 칸 제외)을 1초 주기로 보고 →
MagicMythic `SpellSlots` 가 저장. MagicMythicPlugin 에 공개 API `spellSlots(Player)` / `spellSlotsKnown(Player)` 를 추가하고 Bridge 가 리플렉션으로 읽는다.
- 큐디치 경기 중에는 클라이언트가 경기 프리셋(KyudichiLoadout)을 쓰므로 보고도 경기 프리셋 기준이다 (CastingClient.refreshBindings → STATE.bindings()).
- 배운 마법(magic.learned.&lt;id&gt;)만 센다. 관리자 강제 지정 `/유저관리 <닉네임> 마법슬롯 ...` 도 그대로 먹는다 (시험할 때 편함).
- 클라이언트가 보고하는 값이라 조작된 클라이언트는 칸을 속일 수 있다 (아티팩트 보너스 판정에만 쓰임).

## 아티팩트별 구현
- **순서의 목걸이**: `ArtifactMagicRules.sequence`. 성공한 시전마다 칸 번호를 본다. 1번부터 차례로, 칸 사이 **10초** 안. 다른 칸·키세팅 밖 마법이면 처음부터(1번이면 1부터). 6칸이 다 차 있어야 한다.
  6번 시전이 순서를 완성하면 그 시전 뒤 10초 동안 **그 마법의 피해만** +40% (반응 피해 제외). 첫 타격이 시전 명령 안에서 바로 나가므로 판정은 `MagicCastAttemptEvent`(시전 직전, MONITOR)에서 미리 하고, 순서 진행은 성공한 시전 뒤에 한다.
  피해가 어느 마법 것인지는 MagicMythic `MagicDamageEvent.getSpell()`(새로 추가, SpellTrace 의 spell=) 로 안다. 칸마다 자수정 소리(점점 높게, 본인만), 완성 시 알림.
- **무지개 프리즘**: 6칸 원소가 6원소 하나씩(1초마다 판정). 원소는 새 리소스 `spell-elements.yml`(도감 category, 237종 전부) — 서버에는 원래 마법 원소 표가 없다. **새 마법을 추가하면 이 파일에도 넣어야 한다.**
  반응 피해 x2 (MagicDamageEvent reaction≠"" 이고 "detonate"(원소 잉크병 터뜨리기) 제외), 무지개 회복 x2 (MagicHealEvent reaction=rainbow), 반응 CC `multiplyControl(1.5)`,
  범위 x2 = MagicReactionEvent 에 새 `multiplyRadius` (증기·불씨·모래 회오리·연쇄 번개 튀는 거리·무지개·섬광 반경에 곱함, 상한 x2). 켜질 때 알림 1번.
- **혼돈의 주사위**: 시전 직전(MagicCastAttemptEvent) MagicMythic `rollChaosElement(Player)` → `ChaosElements` 가 이번 시전 원소를 굴림(직전 원소와 다르게). `Reactions.hit` 첫머리에서 원소가 있는 마법의 원소만 바꾼다 → 표식 아이콘·반응이 그 원소. 굴린 원소는 15초 유지(긴 장판·투사체도 한 원소). 피해 이벤트 element·VFX 색은 그대로. 원소 없는 마법은 그대로 원소 없음. 해제하면 지움.
- **단 하나의 서**: 키세팅에 마법이 **정확히 1개**이고 그 마법의 mana-spells.yml 재사용 대기가 0초 초과 30초 이하면 켜짐(1초마다 판정, 켜질 때 알림, 조건이 안 맞으면 장착 시 안내 1번).
  켜진 동안 지능 x0.5 (`MagicCodexBridge.equipmentPower` 에 곱함 → 마법 피해·스탯창·책 요구 지능 모두). 충전 4회: 첫 충전을 쓰는 순간 (가속 적용된 대기 x2) 타이머 시작, 충전이 남아 있으면 응답 쿨 0 + 서버 쿨 제거(클라이언트도 바로 다시 씀), 마지막 충전 뒤에는 타이머 끝까지 쿨, 끝나면 4회 모두 참.
  훅: `ManaBridge.cast` 성공 직후 `plugin.artifactCooldown(...)` 한 줄 (클라이언트가 쿨을 받기 전). 충전 수는 알림으로 보여 준다 (HUD 표시는 안 함). 충전·타이머는 마법을 바꿔도 이어짐(바꿔 끼워 채우기 방지).
- **거꾸로 된 마나병**: `ManaAccount` 에 과열 모드(`heat`). 시전 비용만큼 과열, 초당 5 냉각(마나 재생 대신), 최대치 = 마나 재생량 x20 (최소 20; 기본 재생 5 → 100). 가득 차면 30초 시전 거부(EMPTY).
  스냅숏은 current = 남은 여유, maximum = 과열 최대치라 기존 마나 프로토콜(41/49바이트) 그대로. 보통 마나(current)는 여유 비율을 따라가서 저장·서버 이동·해제에 그대로 쓰인다 → 해제/재장착으로 마나를 채우거나 과열을 지울 수 없다.
  명령 실패 환불이면 방금 건 잠김도 풀림. 다른 시스템의 마나 +(성소·물약)는 냉각, -는 과열로 작동.
  클라이언트 표시: 새 S2C 채널 `magiccodex:mana_heat` (`ManaHeatProtocol`, 6바이트: 켜짐·잠김 남은 ms). 채널을 등록한 클라이언트에게만 보내므로 구 클라이언트는 영향 없음(그냥 보통 마나 바처럼 여유량이 보임).
  새 클라이언트는 마나 바를 주황→빨강 과열 게이지(과열량 = 최대 - 여유)로, 문구 "과열 x / y", 잠김 중 "과열! N초", 마나 부족 알림을 "과열 중이에요…"로 바꾼다.
- 학교·야생 공유: 단 하나의 서 충전·타이머, 과열 잠김 시각을 `ArtifactEffects.runtimeText` 에 `tome=`, `heat=` 로 넣었다 (restore 는 `default` 갈래에서 `rules.restore`).

## 바꾼 파일
- magic-codex-paper: 새 `ArtifactMagicRules.java`(로직 전부), 새 리소스 `spell-elements.yml`, `ArtifactEffects.java`(훅 몇 줄), `ManaAccount.java`/`ManaService.java`(과열 모드), `ManaBridge.java`(한 줄), `MagicCodexBridge.java`(지능 배율·쿨 훅·spellRule), `artifacts.yml`, 새 테스트 `ArtifactMagicRulesTest.java`.
- magic-codex-protocol: 새 `ManaHeatProtocol.java`.
- magic-codex-fabric: `ManaClient.java`(과열 신호 받기·문구), `PlayerHudRenderer.java`(과열 게이지).
- magic-mythic-paper: 새 `ChaosElements.java`, `MagicMythicPlugin.java`(API 4개), `MagicDamageEvent.java`(getSpell, 기존 생성자 유지), `Targets.java`(spell 전달), `MagicReactionEvent.java`(multiplyRadius), `Reactions.java`(반경 배율·혼돈 원소).

## 배포 주의
- **MagicCodexBridge + ChAcademiaMagicMythic 를 함께** 배포. MagicMythic 이 예전 것이면: 키세팅을 못 읽어 목걸이·프리즘·서 꺼짐(1분마다 경고 1줄), 주사위 시전마다 경고, 프리즘 범위 2배 없음, 목걸이 피해 보너스 없음(경고 1번).
- 과열 게이지 표시는 **MagicCodex 모드(클라이언트) 갱신** 필요. 모드가 예전 것이어도 동작(규칙)은 서버에서 정상.
- 운영 서버의 `plugins/MagicCodexBridge/artifacts.yml`(그리고 저장소 `deployment/operating-config/MagicCodexBridge/artifacts.yml`)은 jar 리소스가 아니라서 5개의 `enabled: false` 를 따로 지워야 도감·뽑기에 나온다. 저장소의 deployment 사본은 건드리지 않았다.
- `ArtifactCatalogTest` 는 "121개 보임 + 29개 꺼짐 목록"을 단언하므로 통합 때 숫자·목록을 고쳐야 한다 (이 브랜치에서는 손대지 않음 → 이 테스트는 지금 실패함).

## 게임에서 확인
- 공통: `/유저관리 <닉네임> 마법슬롯 <마법 6개>` 로 칸을 강제로 넣고 시험 가능 (MagicMythic 명령).
- 목걸이: 6칸을 1→6 차례로 → 칸마다 높아지는 소리, 6번에 "순서 완성!" 알림, 6번 마법 피해가 40% 큼. 중간에 다른 칸 → 처음부터.
- 프리즘: 6원소 한 칸씩(예: 불·물·바람·대지·빛·어둠 하나씩) → "6원소가 모였어요" 알림. 반응(증기 폭발 등) 범위·피해 2배, CC 1.5배.
- 주사위: 원소 마법 하나로 같은 적을 두 번 → 표식 아이콘 원소가 매번 바뀌고 혼자 반응이 남.
- 서: 키세팅에 쿨 30초 이하 마법 1개만 → 알림, 4번 연속 시전(알림 3·2·1), 이후 대기 x2 뒤 "충전이 모두 찼어요". 스탯창 지능 절반.
- 마나병: 장착 → 마나 바가 주황 과열 게이지, 마법 쓰면 차오르고 초당 5 감소, 가득 차면 "과열!" 알림·30초 시전 불가(바에 남은 초).

## 못 한 것 / 추정한 것
- 순서 시간 제한 10초, 6번 피해 보너스 유지 10초, 주사위 원소 유지 15초, 과열 최대치 = 재생 x20(최소 20)은 내가 정한 값.
- 서·마나병 효과는 "조건이 맞는 동안"만 (서는 마법 1개일 때만 지능 -50%·대기 2배; 조건이 안 맞으면 아무 효과 없음).
- 서 충전 수는 HUD 표시 없음(알림만). 처음 4회 중 쿨 표시도 없음(쿨 0).
- 순서 번호 = HUD 순서(클라이언트가 보고하는 순서, 빈 칸은 뒤로 몰림). 큐디치 경기 프리셋에 빈 칸이 끼어 있으면 번호가 당겨질 수 있음.
- 주사위는 원소가 있는 마법(element=)만 바꾼다. 피해 이벤트의 element(원소 반지 등)는 원래 원소 그대로.
- 프리즘 범위 2배는 반응별 반경이 있는 반응만(용암·수정 등 단일 대상 반응은 범위 없음). 잿불 낙인 마나번은 기존처럼 보너스 없음.
- 빌드 도구가 없어 파싱 검사만 했다 (타입 검사·실행 안 함). 테스트 `ArtifactMagicRulesTest` 도 실행 못 함.
