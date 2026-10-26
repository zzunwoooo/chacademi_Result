## Inventory and broom integration — 2026-10-13

SurvivalInventoryUI is a feature of magic-codex-fabric (not a separate mod): vanilla-authoritative 27+9 inventory/crafting, visible offhand, guarded armor Shift, status effects and high-resolution glint. Motion 0.3.0-codex.1 supplies permission-owned broom flight and equipment APIs; MagicCodex provides the five-card equipment UI, precharge movement-spell refusal, shared notice and rebound sneak dismount.

See [combined release and complete regression commands](docs/handoff/CODEX_INVENTORY_BROOM_RELEASE_20261013.md). Hosting verification: 633 tests, zero failures/errors/skips. In-game GPU/control acceptance is still pending.

# Current update: party HUD, 2026-10-12

See [party HUD handoff](docs/handoff/CODEX_PARTY_HUD_20261012.md) for the latest deployment and test scope. Prior HolyTaming/protection features are preserved.

# Current integration: 2026-10-12

HolyTaming, recovery fixes and discovery/protection deployment supersede the historical pending status below. See [current handoff](docs/handoff/CODEX_HOLYTAMING_20261012.md). Gimmick module and dungeon models remain excluded; shared donation database remains pending.

# 차카데미 MagicCodex 작업 저장소

## 2026-10-11 shared source integration

This checkpoint is based on public `codex/hires-item-icons-20261006` commit `f44666f689714d5292adfff6e36c8586d27760c0`, preserving the chat/discovery integration working tree and adding Claude magic source from `d4c0859fe11e96291c874dd0cef7ea71ac17e8d5` plus Codex protection fixes. Integration branch: `codex/chat-discovery-magic-protection-20261011`.

Included: Velocity network chat relay and client presentation; discovery event core, donation policy administration and Velocity party conditions; `magic-mythic-paper` source, generated public skill configuration, generator, tests and WorldGuard protection; Bridge prepare/VFX/execute integration. Existing portrait Sunburst/Flare, Luna analysis/transparency, personal UI sizing, pets/shiny and Story source remain preserved. The 97-entry discovery catalog has 95 connected entries and 2 pending (`crimson_heart`, `bouncing_droplet`); it is distinct from the 95-entry magic gameplay/VFX set (generated routes have their own count).

Verification: hosting Java 21.0.10; final targeted test run passed **293 tests** (magic-mythic-paper 11, magic-codex-paper 282), zero failures. These are unit tests, including real WorldGuard classes used in memory and mocked Bukkit entities/events. They are not whole-repository or live-server acceptance tests. Actual dependencies: MythicMobs 5.9.0 (`C:/Chacademi/network/school/plugins/MythicMobs-5.9.0.jar`, staging hardlink), WorldGuard 7.0.13 (`worldguard-bukkit-7.0.13-dist.jar`), FAWE 2.13.1-SNAPSHOT-1119 in the same hosting plugins directory; Paper API 1.21.4. Paid/external JARs are not published. See [protection handoff](docs/handoff/CODEX_MAGIC_PROTECTION_20261011.md) and [discovery handoff](docs/discovery-event-core/HANDOFF.md).

Deployment: earlier chat changes were applied to personal client/server. New discovery and magic-protection changes are **NOT deployed**. This Git synchronization performs no deployment, restart, launcher replacement/release update or DB operation. Discovery donation-policy table creation on future activation requires separate operational/DB authorization. No live tests of initial Mythic mechanic registration, WorldGuard boundaries/OP behavior, projectile/tick scheduling or actual pet integration have been performed.

Remaining: temporary-block recovery journal can lose recovery data (write ordering/save failure/unloaded-world handling); prison release does not preserve original AI awareness. Dungeon 63-model assets/generators/viewer/registration are withheld by user request. Newly observed `claude/gimmick-plugin-20261012` is not reviewed or integrated here. HolyTaming remains separately pending; existing TamingBridge discovery hooks do not authorize that separate feature. Secrets, runtime configuration, real databases, login material, paid JARs, restricted fonts and private reference images are excluded.

Older sections below are historical checkpoints; use this section for the current integration scope and deployment status.


기준일: 2026-10-05. Minecraft 1.21.4 / Java 21. 호스트 소스: `C:\Chacademi\build-workspace`. 운영: `C:\Chacademi\network`. 소스 커밋은 운영 배포나 서버 재시작을 의미하지 않습니다.

## 모듈과 빌드

| 모듈 | 역할 / 명령 |
| --- | --- |
| magic-codex-fabric | Fabric UI 0.39.0-catalog.alpha.2, Loader 0.16.14 / Fabric API 0.119.4+1.21.4. `.\magic-codex-fabric\gradlew.bat -p magic-codex-fabric test remapJar` |
| magic-codex-paper | Paper Bridge 0.30.0-catalog.alpha.1. `.\gradlew.bat :magic-codex-paper:test :magic-codex-paper:jar` |
| magic-codex-protocol / magic-codex-database | 공통 패킷 계약 / 서버 저장 계층. sourceSets로 포함되는 공통 소스 |
| portable-vfx-runtime | common / paper / client 별도 프로젝트. Paper 3.2.0-catalog.alpha.4: `.\portable-vfx-runtime\gradlew.bat -p portable-vfx-runtime -PskipClient :paper:test :paper:jar` |
| magic-discovery-paper / creature-spawns-paper / tornado-event-paper | 기존 서버 모듈. 이번 작업에서 기능 변경하지 않음 |
| chaca-npc-paper (+ chaca-npc-protocol) | ChacaNPC 0.2.0 — school 전용 AI NPC 플러그인 (Citizens 필수). `.\gradlew.bat :chaca-npc-paper:test :chaca-npc-paper:jar`. [모듈 안내](chaca-npc-paper/README.md), [작업 기록](docs/handoff/CHACANPC_20261005.md) |
| chaca-portrait-paper (+ chaca-portrait-protocol) | ChacaPortrait 0.1.0 — 스킨 → AI 상반신 일러스트, 대화창 "내 차례" 초상화, ItemsAdder `item:reroll` 다시 그리기. `.\gradlew.bat :chaca-portrait-paper:test :chaca-portrait-paper:jar`. [모듈 안내](chaca-portrait-paper/README.md), [작업 기록](docs/handoff/CHACAPORTRAIT_20261010.md) |

호스트의 `Build-Server.ps1 -Verify`는 기존 서버 모듈 전체를 빌드합니다. Fabric/Portable VFX는 별도 명령을 사용하세요. [의존 목록](build-support/dependencies.json)에 따라 적법하게 확보한 외부 라이브러리가 필요합니다. ModelEngine/MythicMobs 등 유료 JAR, DB 실데이터, 비밀 설정, 빌드 산출물은 Git에서 제외합니다.

각 모듈의 `build/libs`에 산출물이 생성됩니다. 배포 대상은 `network/school/plugins`, `network/wild/plugins`와 사용자의 Fabric `mods`입니다. 기존 같은 플러그인 JAR을 교체하고 중복 설치하지 않습니다. 이번 작업은 운영 교체·재시작·DB 변경을 수행하지 않습니다.

## 최신 통합과 UUID 계약

`/닉네임`, `/닉네임설정`, `/codexnickname`은 한 이름 입력, 고정 계정명, prefix/name/suffix 미리보기와 취소/저장을 제공합니다. `NicknameProtocol`의 OPEN/SAVE/CLOSE 요청에는 대상 UUID가 없습니다. 서버가 접속 소유 UUID·연결·세션 토큰·sequence·저장 revision을 검증합니다. 계정 이름·권한·친구 식별자는 기존 UUID 계약을 유지합니다. 이름은 Java UTF-16 기준 1–16자, Unicode 문자/숫자 및 `_`/`-`를 허용합니다. 비용이나 새 인기도 규칙은 추가하지 않았습니다.

`DatabaseSettings`를 재사용해 SQLite의 기존 `friends.db` 안 `nicknames`, MariaDB의 `codex_nicknames`에 저장합니다. PlaceholderAPI `%magiccodex_nickname%`, `%user_nickname%`은 namespace가 비어 있을 때만 등록하며 기존 제공자를 덮어쓰지 않습니다. `DisplayNames`는 저장 닉네임을 우선합니다. 친구 목록·추가·삭제·검색·메시지·정보는 기존 처리기를 재사용합니다. 보이는 다른 비 NPC 플레이어를 주 손으로 쉬프트 우클릭하면 기존 `/스텟창 <UUID>`를 호출하며 `StatsBridge` 권한과 타인 장비 읽기 전용 정책을 유지합니다.
최신 PetBbModel / PetModelRenderer / PetScreen, ShinyClient, vanilla-shiny.json, PNG 186개와 기존 UI 자산을 보존했습니다. EquipmentBridge 개인 인벤토리 판정 수정과 CustomShinyModels 최신 매핑도 포함합니다. 닉네임 PNG 최종 상태는 [통합 검증 기록](docs/handoff/MAGICCODEX_20261005.md)을 확인하세요. GUI 모델 81개는 운영 미설치입니다.

## DB 및 운영 상태

MariaDB 11.4.13 / `Chacademi-MariaDB` / 로컬 3306 설치는 인수인계 기록 기준입니다. 이번 SSH 일반 계정은 서비스 관리자 조회 권한이 없어 현재 실행 상태를 재확인하지 못했습니다. XConomy 2.26.3 / Vault 경제를 사용합니다. school/wild MySQL 기본키 누락에 대한 `plugins/XConomy/database.yml`의 `table-suffix` 수정은 적용됐으나 최신 런타임 성공은 미확인입니다. 돈 API는 Vault provider를 사용하며 클라이언트는 DB에 직접 연결하지 않습니다.

Redis는 Ubuntu VM GRUB 이후 사용자 설치 진행 단계이며 가동·동기화 완료가 아닙니다. HuskSync 배포나 다른 플러그인 DB 연결 전체 완료를 전제로 하지 마세요. Bridge SQLite `friends.db`와 LuckPerms H2 (`luckperms-h2-v2.mv.db`)는 유지되며 기존 자료의 전체 DB 이관은 미완료입니다. 과거 SQLite 충돌 2건이 발견됐습니다. 닉네임 테이블은 Bridge 활성화 시 생성됩니다. 닉네임 Client A832BB20… 및 Bridge 0E731DF8… 파일 복사가 완료됐습니다. school/wild의 Bridge는 해당 해시로 직접 확인했으며 클라이언트 복사는 인수인계 기록 기준입니다. 실제 게임·플러그인 활성화·테이블 생성·운영 저장 성공은 아직 검증하지 않았습니다.

`DatabaseSettings`는 외부 `database.properties`의 mode / host / port / database / user / password를 읽습니다. 실제 연결 값은 저장소 밖에서 관리하세요. 코드는 환경변수를 자동 치환하지 않습니다. 예시에는 `DB_HOST`, `DB_USER`, `DB_PASSWORD` 같은 플레이스홀더만 사용하세요.

## 검증과 공개 제한

[통합 검증 기록](docs/handoff/MAGICCODEX_20261005.md)에 staged 결과와 이번 저장소 빌드 결과를 구분해 기록합니다. 초기 전체 클라이언트 테스트는 175/176이며 외부 spells fixture 부재로 1건 실패했습니다. 실제 게임 상호작용은 미확인입니다.

이 공개 저장소는 공유 제한 출처의 참고 모델 데이터와 원본 설정을 제외한 최신 소스입니다. 기존 chacademi-server 저장소의 브랜치·태그·이력·PRIVATE 상태는 유지했습니다. [공개 점검 기록](docs/handoff/PUBLICATION_REVIEW_20261005.md)을 확인하세요. GitHub 캐시·다른 clone까지 완전히 삭제됐다는 뜻은 아닙니다. 일부 참고 모델 미리보기는 제외됩니다.

[작업 원칙](AGENTS.md), [기존 서버 인수인계](docs/handoff/SERVER_HANDOFF.md), [자료 안내](reference/MATERIALS.md)를 함께 참고하세요. 과거 기록과 현재 운영/staged 상태를 구분하세요.
## 상점·우편함

일반 관리자 상점, 구매 시스템 우편, 첨부 수령과 soft delete 복구 API를 추가했습니다. 승인된 배경과 ChacaNPC 통합 빌드가 준비됐으며, 운영 배포 전 공유 DB·경제 정합성·실게임 검증이 필요합니다. 명령/API/복구 경계는 [SHOP-MAILBOX](docs/SHOP-MAILBOX.md)를 확인하세요.

최종 통합 JAR·해시·356개 전체 테스트·운영 준비 사항은 [최종 통합 기록](docs/handoff/FINAL_INTEGRATION_20261005.md)을 확인하세요.

상점·우편함만 외부 `plugins/MagicCodexBridge/shop-mailbox-database.properties`를 읽습니다. school/wild의 이 설정만 같은 MariaDB를 지정할 수 있으며 다른 기능의 `database.properties`와 기존 SQLite 자료는 유지됩니다. 계정·비밀번호 설정, 실데이터 이관, 운영 배포·재시작은 별도 작업입니다.


## UI 후속 통합 (2026-10-06)

상점·우편함/닉네임 열기·NPC 직접 입력/기록·HUD tooltip·상점 자동완성 후속 통합은 [변경·설정·검증 기록](docs/UI_FOLLOWUP_20261006.md)을 확인하세요. 호스팅 테스트 381개 통과, 기존 자산 895개와 보호 Bridge 클래스 48개 보존 확인. 이번 Bridge/UI는 SHP2 계약을 함께 사용하며 운영 배포와 임시 런처 반영은 대기 상태입니다.

## 고해상도 아이템 아이콘 (2026-10-06)

32px를 넘는 평면 아이템 텍스처를 GUI에서 픽셀 격자 없이 부드럽게 그립니다(바닐라 16px 아이템은 그대로). 조건·동작·검증 상태는 [ITEM_ICONS_HIRES](docs/ITEM_ICONS_HIRES.md), 빌드 지시는 [HIRES_ITEM_ICONS_BUILD](docs/handoff/HIRES_ITEM_ICONS_BUILD.md)를 확인하세요. 호스팅 빌드·실게임 확인 전입니다.

### UI review integration (2026-10-13)

[Release checklist and verification](docs/handoff/CODEX_UI_REVIEW_RELEASE_20261013.md) covers the built-in camera, SurvivalInventoryUI, journal/admin, notices, equipment, Motion and monster HUD. [Developed modules](docs/DEVELOPED_MODS.md).

## 2026-10-13 final follow-up

SurvivalInventoryUI remains inside Magic Codex: 27 inventory slots and nine hotbar slots plus real offhand, revised approved artwork, shared notices, persistent key registration order, settings/camera/local VFX opacity, straight aim preview, broom equipment and four-key recatch. Motion is 0.3.0-codex.3. Real-time 72-hour season support awaits an explicit user-selected epoch before activation. [Combined implementation and regression checklist](docs/handoff/CODEX_FINAL_FOLLOWUP_20261013.md). Public launcher feed is outside this follow-up.
