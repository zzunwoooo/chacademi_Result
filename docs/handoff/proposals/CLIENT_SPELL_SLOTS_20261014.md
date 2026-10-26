# MagicCodex 클라이언트 변경 제안 (2026-10-14, 미적용)

소유자 규칙: MagicCodex 변경은 GPT 승인 후 적용. 이 문서는 **제안만** 담고 있으며 클라이언트 코드는 건드리지 않았다.
서버(ChAcademiaMagicMythic)는 아래 채널을 이미 받을 준비가 되어 있고, 클라이언트가 아직 보내지 않아도 안전하게 동작한다.

## 1. 키 슬롯 보고 — `chacademia:spell_slots` (클라이언트 → 서버)

슬롯 장착형 패시브 6종(017 미라지 슈터, 030 미스틱 슈터, 049 광석 재련, 052 불꽃 걸음, 055 화염의 정복자, 121 자동 보급)과
163 별빛 재현("장착한 마법 중 무작위 1개")은 서버가 플레이어의 키 슬롯을 알아야 한다. 지금 슬롯은 `SpellKeySettings`(LIMIT 6)에만 있다.

- 페이로드: UTF-8 문자열 `id1,id2,...` (슬롯 순서, 빈 슬롯은 생략, 최대 6개, 각 id `[a-z0-9_-]{1,64}`), 전체 1024바이트 이하
- 보낼 때: 서버 접속 직후(`ClientPlayConnectionEvents.JOIN` 이후, 서버가 채널을 등록했을 때) 1회 + 키 설정 저장 시마다
- 구현 위치 제안: `SpellKeySettings` 저장 경로(`CastingClient` key-settings.yml 저장)에서 `ClientPlayNetworking.send(...)`.
  Fabric 1.21.4 는 `CustomPayload` 등록이 필요하다 (`PayloadTypeRegistry.playC2S().register(...)`, 코덱은 UTF-8 문자열 하나).
- 서버 쪽: `SpellSlots`(magic-mythic-paper) — 받은 목록 중 `magic.learned.<id>` 권한이 있는 것만 장착으로 인정.

클라이언트 변경 전 임시 운영: 애드온 `config.yml` 의 `passive-slots.mode: learned` 로 바꾸면 배운 패시브는 항상 켜진 것으로 취급한다.
테스트용: `/마법미몹 장착 <플레이어> <id...>` (관리자, 강제 지정 / `비우기`로 해제).

## 2. 쿨타임 초기화 수신 — `magiccodex:cooldown_reset` (서버 → 클라이언트)

`CastingState.input` 은 클라이언트에 남은 쿨타임이 있으면 서버에 요청을 보내지 않는다(83행). 서버에서 쿨타임을 지워도(163 별빛 재현,
131 원초의 돌 2단계) 클라이언트가 막으므로, Bridge 제안 패치(`BRIDGE_SPELL_COOLDOWN_API_20261014.patch`)는 지운 마법 id 를 이 채널로 보낸다.

- 페이로드: US-ASCII 마법 id (`ManaProtocol.decodeCooldownReset`)
- 클라이언트 처리: `CastingClient.state()` 의 `cooldowns.remove(id)` (공개 메서드 하나 추가: `clearCooldown(String id)`)

## 3. (선택) 빛의 필사 입력창

151 빛의 필사는 지금 **채팅 한 줄**을 입력으로 쓴다(시전 후 30초 안의 다음 채팅이 글씨가 되고 채팅에는 보이지 않음).
전용 입력창을 원하면 MagicCodex 기존 자유 입력창(대화 UI)을 재사용해 서버에 문자열을 보내는 방식으로 바꿀 수 있다. 결정 필요.
