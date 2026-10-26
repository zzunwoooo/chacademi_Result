> **[BUILD-READY] Claude 2026-10-26: [빌드·배포 지시 — 이것부터](BUILD_READY_20261026.md)** (오늘 메모 #84~#110 + 전투 스펙·장신구 강화·책 67권 포함, 모듈 전부 빌드).

> Claude 2026-10-26: [VFX 팩 95개 저장소에 올림 — 새 77 + 어둠 교체 18, 런처 client-assets 배치](CLAUDE_VFX_PACKS_20261026.md)
> Claude 2026-10-26 (not built): [오픈 최종 — 빌드·배포용 패치 모음 (마법 300개 합침 + 70044a79 뒤 미배포 패치 전부, 모듈·서버 배치·운영 설정·VFX 팩·지울 파일·프로토콜)](CLAUDE_OPENING_FINAL_PATCHES_20261026.md). **GPT 는 이 문서부터.**

> Codex deployed 2026-10-25: [Opening-final + townmap live deployment receipt](CODEX_OPENING_FINAL_DEPLOYED_20261025.md). Product70044a79; temporary launcherc023a10; proxy preserved; manual in-game verification pending.

> Codex 2026-10-25: [Opening-final actual host builds and full tests](CODEX_OPENING_FINAL_VERIFICATION_20261025.md) — source tests passed; private live-preserving staging only, **NOT deployed**.

> Claude 2026-10-25 밤 (not built): [오픈 최종 통합 — 운영본 c96c5ae3 + 검수 승인분 + 스토리 일지·올레 길잡이·낚싯대 설명](CLAUDE_OPENING_FINAL_20261025.md). Branch claude/opening-final-20261025. **빌드·전체 테스트 필수**, 1장 대화 번호 ch1-15→ch1-17 운영 파일 같이.

> 현재 운영 기준: [LIVE_SOURCE_HANDOFF_20261025](LIVE_SOURCE_HANDOFF_20261025.md) — 운영 상태와 재현 한계를 먼저 확인하세요.

> Claude 2026-10-25 (not built, NOT committed): [오픈 전 메모 1차 개선 — 마법 자동시전 검수·지능 명칭·감정 성향·책 지능·칭호 지급·사전 설정·AI 대화 호감도·천장·파티 아군·대상 마법·허수아비·하우징 좋아요·장 잠금·길잡이·커스텀 경기·재접속·설정창·귀환권·튜토리얼 상점 등](CLAUDE_OPENING_PREP_20261025.md). Local branch work/opening-prep-20261025 (merge of latest claude/* branches).
> Claude 2026-10-24 (not built): [던전 기믹 자동 연결 — 퍼즐·노드·puzzles.yml 삭제, 붙어 있는 블럭끼리 연결, 명령은 소환·도구·목록·모델·초기화, 막대 정답 모드](CLAUDE_GIMMICK_AUTOWIRE_20261024.md). Branch claude/gimmick-simplify-20261024 on claude/travel-map-20261023. ChacaGimmick jar only (+ config.yml keys).
> Claude 2026-10-25 (not built): [서버 렉 1단계 — Guards/Passives WG 순서, 타이머 위상 분리, 변색종 track 송신 축소, 접속 saveData 1곳 제거, 공중 발판 저널 배치, Links Method 캐시](CLAUDE_LAG_FIX_STEP1_20261025.md). Branch claude/lag-fix-step1-20261025 on claude/travel-map-20261023 @ af3bffa. MagicMythic + creature-spawns + MagicCodexBridge jars; no client/config change. GPT 검토 통과 항목 + 2번 추적 거리 복구(96 유지). 5·7 제외.
> Claude 2026-10-24 (not built): 지도 이동 2차 — WaypointGlobe VFX(클라 PortableVFX 팩 배포 필요)·미니맵·J 키·야생 스폰·공간 도약 1곳/지정 무료. Same branch, see §0-2 of the doc below.
> Claude 2026-10-23 (not built): [지도 이동 메뉴 — 학교/야생1/야생2 지도 창 · 공개 지점 서버 판정 해금 · 개인 지점(공간 도약 135 시전) · 서버 간 이동 표 · 공간 도약 획득 = 공개 지점 3곳](CLAUDE_TRAVEL_MAP_20261023.md). Branch claude/travel-map-20261023 on claude/book-ui-20261023 @ 346c0fb. MagicCodexBridge + MagicDiscovery (school/wild) + Velocity party jar + client mod together; new channels magiccodex:travel_request/response, chacademi:travel_link; new travel.yml; live discoveries.yml·mana-spells.yml need the spatial_leap edits.
> Claude 2026-10-23 (not built): [책 읽기 UI — 홀로그램 책 창 + 닫을 때 명령(플레이어당 1번, 공유 DB) + 블록 연결](CLAUDE_BOOK_UI_20261023.md). Branch claude/book-ui-20261023 on review/integrated-release-20261023-task7 @ af1ed3e. MagicCodexBridge (school/wild) + client mod together; new channels magiccodex:book_request/response; new books.yml.
> Claude 2026-10-24 (not built): [일반 NPC 상점 v3 — 4×3 카드 · 페이지 · 정렬 · 하단 상세바 (오너 UI v2 묶음)](CLAUDE_SHOP_V3_20261024.md). Same branch claude/shop-admin-inventory-20261024. Client mod (deploy with the shop-admin Bridge change).
> Claude 2026-10-24 (not built): [알림 카드 외형 교체 — 승인 PNG(금테·반투명 네이비 바탕, 원형 받침, 아이콘 10종, 수락/거절·닫기) + 긴 문구 줄바꿈](CLAUDE_NOTIFICATION_SKIN_20261024.md). Branch claude/notification-skin-20261024 on claude/galaxy-bag-20261024 @ f088c59. Client mod only; 동작·위치·발견 알림(상단 중앙) 그대로.
> Claude 2026-10-24 (not built): [아공간 배낭 — 서바이벌 인벤토리 새 화면(은하 테두리·중앙 상단) + 버튼으로 여는 옆 제작창(2×2·조합법 목록) + 옷 버튼→장비창 + 배낭 2·3쪽(가방 확장권, 27칸 교체, 핫바 고정)](CLAUDE_GALAXY_BAG_20261024.md). Branch claude/galaxy-bag-20261024 on claude/shop-admin-inventory-20261024 @ b01c784. Bridge (school/wild) + client mod together (new bag channels); 야생은 bag-pages.yml server-id 확인.
> Claude 2026-10-24 (not built): [상점 관리 — 인벤토리에서 등록 · 분류/순서 · 40개 제한 해제 · /상점관리 가져오기 · 피피 잡화점 195종](CLAUDE_SHOP_ADMIN_20261024.md). Branch claude/shop-admin-inventory-20261024 on review/integrated-release-20261023-task7 @ af1ed3e. Bridge (school/wild) + client mod together (shop + shop admin wire format changed); DB columns added automatically.
> Claude 2026-10-24 (not built): [마일리지 상점 + 관리 창 + 구매→우편 + /유저관리 마일리지 + 메뉴 「마일리지샵」 연결](CLAUDE_MILEAGE_SHOP_20261024.md). Branch claude/mileage-shop-20261024 on review/integrated-release-20261023-task7 @ af1ed3e. Bridge (school/wild) + client mod + protocol together; new channels magiccodex:mileage_*.
> Claude 2026-10-23 (not built): [책 읽기 UI — 홀로그램 책 창 + 닫을 때 명령(플레이어당 1번, 공유 DB) + 블록 연결](CLAUDE_BOOK_UI_20261023.md). Branch claude/book-ui-20261023 on review/integrated-release-20261023-task7 @ af1ed3e. MagicCodexBridge (school/wild) + client mod together; new channels magiccodex:book_request/response; new books.yml.
> Claude 2026-10-23 (not built): [고해상도 아이템 손·3인칭·바닥·액자 매끈 렌더 + 비정사각 아이콘 비율 유지](CLAUDE_HIRES_ITEM_WORLD_20261023.md). Branch claude/hires-item-world-20261023 on claude/npc-buttons-20261023 @ b97de93. Client mod only.
> Claude 2026-10-21 (not built): [Citizens NPC 3D 외형 — GLB 스키닝 렌더러 새 모드 chacademi-npc-model + ChacaNPC 외형 동기화·/NPC관리 외형](CLAUDE_NPC_MODEL_20261021.md). Branch claude/npc-model-20261021 on codex/followups-integrated-20261020-task12 @ 0cd1454. New client mod + ChacaNPC (school) + launcher content chacademi/npc-models (GLB not in this public repo). Story marker height patch in patches/.
> Claude 2026-10-21 (not built): [스토리 진행 단계 — Skript 없이 (조건·호출·HUD·포탈·징표 + 기숙사 분류 SchoolBridge 연결 + 성공 신호 4종)](CLAUDE_STORY_STEPS_20261020.md). Branch claude/story-steps-20261020 on codex/followups-integrated-20261020-task12 @ 0cd1454. ChacademyStory + story mod + MagicCodexBridge + ChacaHousing + ChacaNPC together; 1장 콘텐츠 chacademi-server claude/story-ch1-20261020 (story_ch1.sk 삭제).
> Deployed 2026-10-20: [Korean command cleanup — all installed Paper modules, arena, Velocity and four client modules](CODEX_COMMAND_CLEANUP_DEPLOYED_20261020.md). Code 8e8cc23f, launcher feed b721b32, 1583 tests (2 hosting-only skips), actual loads and Korean console commands verified. House sorting 06753da and repaired asset manifest preserved; house dialogue content and artifact CC 7882171 remain excluded.
> Claude 2026-10-20 (not built): [아티팩트 CC·원소 반응 개편 — 반응 아티팩트 4 수정 + CC 감소류 8 교체](CLAUDE_ARTIFACT_CC_20261020.md). Same branch claude/reaction-cc-vfx-20261020. MagicMythic + MagicCodexBridge + artifacts.yml together.

> Claude 2026-10-20 (not built): [원소 반응 CC 개편 — VFX 팩 교체 + 반응 효과·크기 서버 코드](CLAUDE_REACTION_CC_VFX_20261020.md). Branch claude/reaction-cc-vfx-20261020 on codex/operating-final-20261019 @ 8356980. MagicMythic jar (school/wild) + client VFX pack.

> Claude 2026-10-20 (not built): [명령어 전면 정리 — 한글 대단원 15개·Brigadier 칸 안내·실패 알림·영어 명령 삭제](CLAUDE_COMMAND_CLEANUP_20261020.md), 운영자 안내 [ADMIN_COMMANDS_KO.md](../ADMIN_COMMANDS_KO.md). Branch claude/command-cleanup-20261020 on codex/operating-final-20261019 @ ef55623. **All plugins + Velocity + all client mods together**; operating config command strings must change (§1).
> Claude 2026-10-20 (not built): [하우징 가구 100종 그림 + furniture.yml 100종](CLAUDE_HOUSING_20261017.md#2026-10-20-가구-100종-그림-브랜치-claudehousing-furniture-20261020). Branch claude/housing-furniture-20261020 on codex/operating-final-20261019 @ ef55623 (+ cherry-pick 8521b9e). Client mod + live furniture.yml replacement on school/wild.

> Claude 2026-10-20 (not built): [던전 기믹 마법 239~250 · 기믹 블록 단위 설치·배리어 · 마력 신호 · 미개발 168~299 도감 삭제](CLAUDE_GIMMICK_SPELLS_20261020.md). Branch claude/dungeon-gimmick-spells-20261020 on codex/operating-final-20261019 @ 6b9866b. ChacaGimmick + MagicMythic + Bridge/MythicMobs/PortableVFX configs + client mod (default-spells) together; 12 VFX packs in portable-vfx-runtime/assets; 37 bbmodels in gimmick-paper/models.
> Claude 2026-10-20 (not built): [물고기 판매소 · 통발 아공간 수조 창 · 통발 마법(fish_trap 324)](CLAUDE_FISH_SHOP_TANK_20261020.md) + 2차 QA 패치(§6: fishing protocol v2, ItemsAdder 장비·물고기 227종 + DB 내구도, 메뉴 물고기 도감). Branch claude/fish-shop-20261020 on codex/operating-final-20261019 @ 21a88ec. Bridge (school/wild) + client mod together; new channels magiccodex:fish_shop_*, magiccodex:fish_tank_*; merge fish_trap into live mana-spells.yml.
> Claude 2026-10-20 (not built): [낚시 미니게임 · 장비 27종 · 물고기 200종 · 낚시 도감](CLAUDE_FISHING_20261020.md). Branch claude/fishing-20261020 on codex/operating-final-20261019 @ 81c7458. Bridge (school/wild) + client mod together; new channels magiccodex:fishing_request/response; new ymls fishing*.yml.
> Claude 2026-10-19 (not built): [몹 예고 장판 원형·직선·부채꼴 + 블레이즈 화염 기둥](CLAUDE_MOB_TELEGRAPH_20261019.md). Branch claude/mob-telegraph-20261019 on claude/status-hud-polish-20261019. Protocol + MagicMythic + client mod; VFX pack BlazePillar on the owner PC.
> Claude 2026-10-19 (not built): [대상 체력바 HUD v4 (금색 프레임·한 줄 게이지) + 상태이상 아이콘 v2 + 글자 외곽선](CLAUDE_STATUS_HUD_TEXT_20261019.md). Branch claude/status-hud-polish-20261019 on codex/operating-status-hud-20261019 @ c5a2ccf. Client mod only.

> Claude 2026-10-19 (not built): [대상 체력바 HUD(보스 HUD 레퍼런스) + 상태이상 16종 + 머리 위 원소·디버프 아이콘](CLAUDE_STATUS_HUD_20261019.md). Branch claude/status-hud-20261019 on codex/operating-latest-20261018 @ 6fe06f0. MagicMythic + Bridge (school/wild) + client mod together; new channels magiccodex:status, magiccodex:target_hud.

> Deployed 2026-10-18: latest integrated Kyudichi/duel, controls, shop and shared alchemy; 814 tests, personal client, school/wild/arena/Velocity and public launcher v0.1.12 verified. See [deployment](CODEX_LATEST_DEPLOYED_20261018.md).

> Operating integration candidate: Kyudichi c2ec793 + Housing f1d3302, preserving deployed shop db396a7, shoulder 9ed319e and broom roll f0e9aaf. Deployment evidence is recorded separately.

> Integrated shop redesign db396a7 (build/deployment status recorded separately): [CLAUDE_SHOP_REDESIGN_20261017.md](CLAUDE_SHOP_REDESIGN_20261017.md). Client only.


## Claude 빗자루 배럴롤 거리 증가 (2026-10-17, 미빌드)

[CLAUDE_BROOM_ROLL_20261017.md](CLAUDE_BROOM_ROLL_20261017.md): `claude/broom-roll-20261017` (broom-30 기준). A·A/D·D 배럴롤 옆 회피 거리 약 2배 (ROLL_IMPULSE 0.85, 구르는 동안 감쇠 0.02). 모드만 변경, 균형 소모·쿨다운 그대로.

## Claude 어깨 시점 조준점 대상 선택 (2026-10-17, 미빌드)

[CLAUDE_SHOULDER_CROSSHAIR_20261017.md](CLAUDE_SHOULDER_CROSSHAIR_20261017.md): `claude/shoulder-crosshair-20261017`. F5 어깨 시점에서 블럭 부수기·설치·공격·상호작용이 캐릭터 정면이 아니라 조준점 대상을 따른다. 리치와 가림은 눈 기준으로 검사해 서버와 일치한다. MagicCodex 클라이언트만 바뀐다.

> Claude 2026-10-17 (not built): [하우징 1차 — 기숙사 내 방 꾸미기 UI (새 플러그인 ChacaHousing + MagicCodex 창)](CLAUDE_HOUSING_20261017.md). Branch claude/housing-20261017 on claude/kyudichi-20261017 @ 268e92c. New plugin housing-paper (school/wild, shared MariaDB) + client mod must ship together (new channel chacademi:housing). Bridge/Velocity unchanged.

> Claude 2026-10-17 (not built): [큐디치 3:3 공중 PvP — 경기장 플러그인·매칭 대기열·클라 HUD/창](CLAUDE_KYUDICHI_20261017.md). Branch claude/kyudichi-20261017 on claude/party-ui-20261016 @ 8e9bbad. New arena server + ChacaKyudichi, Velocity party jar, Bridge (all servers), client mod and chacademy-motion must ship together.

> Claude 2026-10-16 (not built): [파티 창 UI + 파티 초대 알림](CLAUDE_PARTY_UI_20261016.md). Branch claude/party-ui-20261016 on codex/operating-alchemy-carrier-20261016 @ 9cefa25. Velocity party plugin + magic-codex UI must be deployed together (new channel chacademi:party_ui).

<!-- Latest operating artifact-only ownership integration: CODEX_SHARED_ARTIFACT_20261016.md -->
> Operating integration 2026-10-16: alchemy dd34f25 + 43513c2 and broom projectile b69ea36 added to the deployed integrated baseline. See CODEX_OPERATING_ALCHEMY_CARRIER_20261016.md.

> Content follow-up: [Six Story descriptions and Claude editor contract](STORY_DESCRIPTION_CONTRACT_20261012.md). Personal dialogue files updated with backups; only description metadata is published. No restart required.

> Deployed 2026-10-12: [Unified journal, shared notices, mouse keys and target/wildlife release](CODEX_JOURNAL_INPUT_RELEASE_20261012.md). Source938256b; hosting630 tests passed; school/wild and personal mods verified. Real game visual acceptance remains pending because hosting graphics initialization failed. This supersedes earlier preparation-only notices below.

> 2026-10-12 prepared, not deployed: [attack targets / grounded rare wildlife](CODEX_TARGETS_WILDLIFE_20261012.md). UI/input work is separate.

> Deployed 2026-10-12: [Catalog gameplay runtime + Story queue fix](CODEX_CATALOG_RUNTIME_FIX_20261012.md). School/wild Bridge runtime.1 and Story0.1.3 verified; client and Velocity unchanged. This supersedes candidate-only notices below.

> Latest combined candidate (tested, NOT deployed): [Catalog gameplay startup + Story queue](CODEX_CATALOG_RUNTIME_FIX_20261012.md). Bridge300 + Story109 tests passed; server-only replacements pending normal maintenance.

> Latest follow-up (tested, NOT deployed): [Story failed-cutscene queue fix](CODEX_STORY_QUEUE_FIX_20261012.md). Server-only0.1.3 candidate; live remains0.1.2.

> Latest deployed integration: [Taming, single-file Story and gimmicks](CODEX_TAMING_STORY_20261012.md). 673 hosting tests passed; school/wild and personal UI/Story applied. This latest entry supersedes historical pending statuses below. Dungeon models and launcher/feed remain excluded.

# Current deployment: wild reset and HUD server fixes, 2026-10-12

See [completed operation and rollback boundary](CODEX_WILD_RESET_20261012.md). Wild overworld reset with verified backup; Wildlife installed; Bridge updated on school/wild. Launcher remains explicitly held. This supersedes the earlier staging/approval-pending status below.

# Current staging: HUD fixes and wildlife review, 2026-10-12

See [staged fixes and blockers](CODEX_HUD_FIXES_20261012.md). Nothing in this staging patch is deployed. Launcher changes are explicitly held. Icon baseline is corrected using parent-verified pixel evidence; live visual acceptance and worldgen/new-plugin approval remain pending.

# Current update: party HUD, 2026-10-12

See [party HUD handoff](CODEX_PARTY_HUD_20261012.md) for the latest deployment and test scope. Prior HolyTaming/protection features are preserved.

# Current integration: 2026-10-12

HolyTaming, recovery fixes and discovery/protection deployment supersede the historical pending status below. See [current handoff](CODEX_HOLYTAMING_20261012.md). Gimmick module and dungeon models remain excluded; shared donation database remains pending.

# Claude 작업분 목록 (GPT/Codex 인계용)

## 2026-10-11 shared source integration

This checkpoint is based on public `codex/hires-item-icons-20261006` commit `f44666f689714d5292adfff6e36c8586d27760c0`, preserving the chat/discovery integration working tree and adding Claude magic source from `d4c0859fe11e96291c874dd0cef7ea71ac17e8d5` plus Codex protection fixes. Integration branch: `codex/chat-discovery-magic-protection-20261011`.

Included: Velocity network chat relay and client presentation; discovery event core, donation policy administration and Velocity party conditions; `magic-mythic-paper` source, generated public skill configuration, generator, tests and WorldGuard protection; Bridge prepare/VFX/execute integration. Existing portrait Sunburst/Flare, Luna analysis/transparency, personal UI sizing, pets/shiny and Story source remain preserved. The 97-entry discovery catalog has 95 connected entries and 2 pending (`crimson_heart`, `bouncing_droplet`); it is distinct from the 95-entry magic gameplay/VFX set (generated routes have their own count).

Verification: hosting Java 21.0.10; final targeted test run passed **293 tests** (magic-mythic-paper 11, magic-codex-paper 282), zero failures. These are unit tests, including real WorldGuard classes used in memory and mocked Bukkit entities/events. They are not whole-repository or live-server acceptance tests. Actual dependencies: MythicMobs 5.9.0 (`C:/Chacademi/network/school/plugins/MythicMobs-5.9.0.jar`, staging hardlink), WorldGuard 7.0.13 (`worldguard-bukkit-7.0.13-dist.jar`), FAWE 2.13.1-SNAPSHOT-1119 in the same hosting plugins directory; Paper API 1.21.4. Paid/external JARs are not published. See [protection handoff](CODEX_MAGIC_PROTECTION_20261011.md) and [discovery handoff](../discovery-event-core/HANDOFF.md).

Deployment: earlier chat changes were applied to personal client/server. New discovery and magic-protection changes are **NOT deployed**. This Git synchronization performs no deployment, restart, launcher replacement/release update or DB operation. Discovery donation-policy table creation on future activation requires separate operational/DB authorization. No live tests of initial Mythic mechanic registration, WorldGuard boundaries/OP behavior, projectile/tick scheduling or actual pet integration have been performed.

Remaining: temporary-block recovery journal can lose recovery data (write ordering/save failure/unloaded-world handling); prison release does not preserve original AI awareness. Dungeon 63-model assets/generators/viewer/registration are withheld by user request. Newly observed `claude/gimmick-plugin-20261012` is not reviewed or integrated here. HolyTaming remains separately pending; existing TamingBridge discovery hooks do not authorize that separate feature. Secrets, runtime configuration, real databases, login material, paid JARs, restricted fonts and private reference images are excluded.

Older sections below are historical checkpoints; use this section for the current integration scope and deployment status.

| Claude branch | Source commit | Integration status |
| --- | --- | --- |
| claude/magic-mythic-20261011 | d4c0859fe11e96291c874dd0cef7ea71ac17e8d5 | Public source selectively integrated with protection fixes; not deployed |
| claude/dungeon-gimmick-models-20261011 | 81de103de182b6551862069d85845fd523aae8d4 | User deferred; excluded |
| claude/gimmick-plugin-20261012 | a8b7b678575c904a43109f0033461b5758a9d17f | Observed remotely; not reviewed or integrated |


Claude 는 GPT 작업 브랜치에 직접 커밋하지 않고, **`claude/` 로 시작하는 별도 브랜치**에만 올립니다.
각 브랜치에는 `docs/handoff/CLAUDE_<주제>_<날짜>.md` 인계 문서가 한 개씩 있습니다.

## GPT/Codex 가 받는 순서

사용자가 "깃에서 클로드 작업분 확인하고 주의사항 보고 다음 패치 진행해" 라고 하면:

1. `git fetch origin` 후 `git branch -r --list 'origin/claude/*'` 로 Claude 브랜치를 찾는다.
2. 아래 표에서 **상태가 `받기 전`** 인 브랜치마다 인계 문서를 읽는다 (`git show origin/<브랜치>:docs/handoff/<문서>`).
   문서의 "주의사항"과 "확인할 것"을 먼저 본다.
3. 지금 작업 브랜치에 병합한다 (`git merge --no-ff origin/<브랜치>`). 충돌이 나면 인계 문서의 "변경 범위" 표를 기준으로 Claude 쪽 의도를 살려 해결한다.
4. AGENTS.md 대로 호스팅에서 빌드·테스트한다.
5. 이 표의 상태를 `받음 (<커밋>, <날짜>)` 으로 바꿔 커밋한다. 받은 뒤의 Claude 브랜치는 지우지 않는다 (기록용).
6. 그다음 사용자의 다음 패치를 진행한다.

## 브랜치

| 브랜치 | 기준 | 인계 문서 | 내용 | 상태 |
| --- | --- | --- | --- | --- |
| `claude/first-nickname-20261010` | `codex/hires-item-icons-20261006` @ `9a6263a` | `CLAUDE_FIRST_NICKNAME_20261010.md` | `/최초닉네임설정` (닫을 수 없는 창, 재접속 시 다시 열기, 저장 후 `storydialogue {player} ch1-2`) | 받음 (1a42349, 2026-10-10; 호스팅 검증 완료, 배포 전) |
| `claude/chacademy-story-20261010` | `claude/first-nickname-20261010` (위 브랜치 포함) | `CLAUDE_CHACADEMY_STORY_20261010.md` | `chacademy-story/` 스토리 컷신·대화 모드+플러그인+편집기. 한글 닉네임, 내 일러스트, 호감도, 닫기 금지, 이어서 보기 | 받음 (3779d57, 2026-10-10; 검증·기존 한국어 글꼴 개인 배포 완료) |
| `claude/audit-fixes-20261010` | `codex/hires-item-icons-20261006` @ `310512b` | `CLAUDE_AUDIT_FIXES_20261010.md` | 스토리 대화창을 MagicCodex 기존 대화 UI로 통합 + 전체 코드 점검 수정 (상태 저장 추방, VFX 한도, 닉네임 중복 금지, 친구 신청제, 리롤 소모 규칙, 스토리 서버 검증 등). **빌드·실행 안 함 — 문서 0장·4장 먼저** | 받음 (36b2b35 안전 수정, 호스팅 790 테스트·MariaDB 격리 검증·서버/개인 런처 배포 완료; Story 콘텐츠 별도) |
| `claude/party-hud-20261012` | `codex/chat-discovery-magic-protection-20261011` @ `e11af86` | `CLAUDE_PARTY_HUD_20261012.md` | 파티 위젯: 기숙사 아이콘·한글 닉네임·실시간 체력/마나 막대, Alt 이동·크기 조절, 마우스 올리면 수치 팝업 (Paper vitals → Velocity → 클라이언트). **MC 의존 코드 빌드 안 함 — 문서 3·5장 먼저** | 받음 (0dcd626, 2026-10-12; hosting tests and deployment verified) |
| `claude/survival-inventory-20261012` | `codex/chat-discovery-magic-protection-20261011` @ `c373610` | `CLAUDE_SURVIVAL_INVENTORY_20261012.md` | 서바이벌 인벤토리 새 화면(떠 있는 조합 구체·타원 받침, 바닐라 슬롯·조합·조합법 그대로) + 128px 아이콘 반짝임. **Gradle 빌드·실게임 전 — 문서 0·6장 먼저** (1.21.4 yarn jar로 javac 타입검사, JUnit 257개 통과) | 받음 (2026-10-13; hosting 633 tests passed; see CODEX_INVENTORY_BROOM_RELEASE_20261013.md) |
| `claude/broom-flight-20261012` | `codex/chat-discovery-magic-protection-20261011` @ `c373610` | `CLAUDE_BROOM_FLIGHT_20261012.md` | 비행 마법 → 빗자루 비행 (클라이언트 물리·균형/낙마·숙련도·빗자루 10종·`/빗자루`). `chacademy-motion/` 0.2.0 사본 추가(PC 빌드 성공) + magic-mythic 연결(빌드 안 함). MagicCodex 제안은 문서 5장(미적용). **0.3.0 추가: 빗자루 보유=권한, 장비창 빗자루 칸·선택창 제안 `CLAUDE_BROOM_EQUIP_UI_20261012.md` (GPT 검토 후 적용, 자료 `broom-ui/`)** | 받음 (2026-10-13; hosting 633 tests passed; see CODEX_INVENTORY_BROOM_RELEASE_20261013.md) |
| `claude/aim-camera-20261013` | `codex/chat-discovery-magic-protection-20261011` @ `59f10f8` | `CLAUDE_AIM_CAMERA_20261013.md` | 어깨 시점(SSR 대체, F5 3인칭 뒤만), 마법 모드 에임 커서(시선 고정·Alt 꾹=시점 회전), 롤식 조준 시전(키→지형 맞춤 범위/사거리/대상 표시→좌클릭 발동·우클릭 취소). **클라이언트만, 서버·프로토콜 변경 없음**(발동 직전 시선 패킷). javac 타입검사·JUnit만, **빌드·실게임 전 — 문서 0장 먼저, SSR 런처에서 제거** | 받기 전 |
| `claude/followup-fixes-20261013` | `126fcea` | `CLAUDE_FOLLOWUP_FIXES_20261013.md` | 수정 요청서 1~7: 채팅 중 알림·스킬HUD·좌상단 메뉴, 알림 소리, 커스텀 인벤토리 제거, 설정(50%·슬라이더·직접 입력·소리), 키세팅 원래 디자인+마우스(아래 6칸 제거, 창 높이 축소), 사거리 표시(단색·채움·블룸·직선 홀로그램·힌트 위치), 장비창, 비행 HUD(시안·기능), 교화 UI. **javac·JUnit만, 빌드·실게임 전 — 문서 0장 먼저, 비행 HUD 사용자 승인됨** | 받기 전 |
| `claude/element-reaction-20261014` | `claude/followup-fixes-20261013` @ `41ee355` | `CLAUDE_ELEMENT_REACTION_20261014.md` | 원소 반응 정규 시스템: 표식 3초·반응 15종(적 대상 간단 효과, 제어 1.5초 이하)·반응 후 3초 내부 쿨, 몹 머리 위 원소 아이콘(HUD 이미지)·반응 이름 팝업. magic-mythic `Reactions` + 클라이언트 `ElementMarkClient` + `ElementProtocol`. VFX 나중. **순수 로직 javac만, Gradle·실게임 전** | 받기 전 |
| `claude/artifact-codex-20261015` | `claude/element-reaction-20261014` @ `1a0fe6f` (위 브랜치 포함) | `CLAUDE_ARTIFACT_CODEX_20261015.md` | 아티팩트 도감 UI(승인 PNG + MagicCodex 폰트), 서버 `artifacts.yml`(언제든 수정 → `/아티팩트관리 새로고침`), 보유=권한, 장착 기록(PDC), 장비창 아티팩트 칸 → 도감. 예시 4개(등급별 1개). **능력 미구현. 프로토콜 javac만, Gradle·실게임 전** | 받기 전 |
| `claude/magic-71-gameplay-20261014` | `claude/followup-fixes-20261013` @ `41ee355` | `CLAUDE_MAGIC_71_20261014.md` | 미구현 마법 71종(이전 7 + 101~167 신규 64) 게임플레이: 애드온 새 메커닉 6종·패시브(슬롯 장착)·획득 조건(수동 9·자연 47)·VFX 바인딩 초안(미사용). 64종은 VFX 클라이언트 지원 전까지 시전 비활성 유지, `/마법미몹 시험` 으로 기능 확인. MagicCodex 코드는 제안만(Bridge 쿨타임 API 패치·클라이언트 슬롯 채널). **javac 타입검사만, JUnit·빌드·실게임 전 — 문서 0장 먼저** | 받기 전 |
| `claude/magic-71-gameplay-20261014` | 같은 브랜치 이어서 | `CLAUDE_VFX_BC7_20261014.md` | PortableVFX 클라이언트 텍스처 BC7 압축(순수 Java 인코더, 디스크 캐시, 화질 38 dB 미만은 RGBA 유지). 신규 62팩 430→119 MiB, 예산 문제 해소. `-Dportablevfx.claude.textureCompression=off` 로 예전 경로. **스텁 javac + 일부 테스트만, Gradle·GL 스모크·실게임 전 — 문서 0장 먼저** | 받기 전 |
| `claude/magic-71-gameplay-20261014` | 같은 브랜치 `da851b7` | `CLAUDE_DEPLOY_101_167_20261015.md` | **101~167 배포 전달 사항**: 070ceae 와 통합, 서버·클라이언트·런처에 무엇을 배포할지, 기존 PC 도감 폴더 미갱신 문제, VFX 연결 미반영(시전 거부 원인), 켜지 않는 마법 목록 | 받기 전 |
| `claude/reaction-vfx-20261015` | `claude/artifact-codex-20261015` @ `10c7e42` (위 브랜치 포함) | `CLAUDE_REACTION_VFX_20261015.md` | 원소 반응 VFX 15종: PortableVFX 팩 `ElementReactions`(client_compat OK, 텍스처 RGBA 2.5 MiB) + magic-mythic `ReactionVfx`(리플렉션, softdepend PortableVFX)로 반응 시 재생. **클라 팩 배포 필요. javac 스텁 검사만, Gradle·실게임 전** | 받기 전 |
| `claude/artifact-catalog-20261015` | `claude/reaction-vfx-20261015` @ `b07c62c` (위 브랜치 포함) | `CLAUDE_ARTIFACT_CATALOG_20261015.md` | 검수 완료 아티팩트 150항목 yml(보류 2 숨김, 사용형 20), 아이콘 71장, 아티팩트 사용 키 X(프로토콜 v2 `USE`·대기 시간·`ArtifactUseEvent`), 마법 키세팅에서 X 금지. **능력 미구현, 서버·모드 같이 배포. Fabric 화면 코드 컴파일 미확인** | 받음 (Codex integration; 637 hosting tests passed; school/wild and personal UI deployed) |
| `claude/artifact-summon-20261015` | `claude/artifact-catalog-20261015` @ `c1c2c01` (위 브랜치 포함) | `CLAUDE_ARTIFACT_SUMMON_20261015.md` | 유물함 소환(아티팩트 뽑기): `/아티팩트소환` 창, [소환하기]로 `item:random_artifact` 소모, 등급 5:3:1:0.1, 새것은 권한 지급·중복은 `item:artifact_shard` 우편, 등급이 드러나는 개봉 연출(가산 합성 셰이더·바닐라 사운드). **아이템애더 아이템 2개 생성 필요, Fabric 화면 컴파일 미확인** | 받기 전 |
| `claude/artifact-effects-20261015` | `claude/artifact-summon-20261015` @ `0236fee` 이후 (위 브랜치 포함) | `CLAUDE_ARTIFACT_EFFECTS_20261015.md` | 아티팩트 효과 120/150 구현(스탯·받는 피해·마법 피해·원소 반응·채집·음식·사용 키 18종), 보류 30(모션·키세팅·빗자루 훅 등). 파티 명단 Velocity→서버 `chacademi:party_roster` + 공개 서비스 `PartyRoster`. MagicMythic 이벤트 4종 추가. 스쿨·야생 공유(`codex_player_state`에 열 6개 자동 추가) + 서버 도착 시 능력치 통합 갱신 `PlayerStats`(장신구·아티팩트 재계산 후 현재 체력·마나 복원). **Paper API 소스로 javac 확인, Gradle·실게임 전. Velocity·Bridge·MagicMythic 같이 배포** | 받기 전 |
| `claude/alchemy-20261016` | `claude/artifact-effects-20261015` @ `ac545d0` (위 브랜치 포함) | `CLAUDE_ALCHEMY_20261016.md` | 연금술: 경험치 포인트로 제작, 숨겨진 레시피(알맞은 재료로 합성하면 발견, LuckPerms `magiccodex.alchemy.recipe.<id>`), 근접 힌트, 결과는 인벤토리→우편. 재료 칸은 lore 첫 줄 '재료' 아이템만. `/연금술`, `/연금술관리`, `alchemy.yml`(레시피 비어 있음). GPT 연금술 PNG + MagicCodex 폰트, 겹침 테스트. **새 채널, 서버·모드 같이 배포. 서버는 Paper API 소스 javac 통과, Fabric 화면 컴파일 미확인**  + 10-17 레시피 관리 화면(`/연금술관리`, 모드 전용 화면, 새 채널) | 받기 전 |
| `claude/housing-20261017` | `claude/kyudichi-20261017` @ `268e92c` (위 브랜치 포함) | `CLAUDE_HOUSING_20261017.md` | 하우징 1차: 기숙사실 문 → UI 미니룸 편집(쿼터뷰, 가구 상자, 레이아웃 3칸, 확장 Vault), 구경(좋아요·방명록·친구 방·구경 중 명단 실시간). 새 플러그인 `housing-paper`(ChacaHousing, 공용 MariaDB `codex_housing_*`). **Gradle·실게임 전, 플러그인·모드 같이 배포, 확장 비용 임시값** | 받기 전 |

## Claude 가 지키는 규칙

- GPT 브랜치(`codex/*` 등)에 직접 push 하지 않는다.
- 이 저장소를 빌드·서버 설치하지 않는다 (AGENTS.md: 호스팅 우선). 빌드 여부는 인계 문서에 적는다.
- 새 브랜치를 만들 때마다 이 표에 한 줄 추가한다.

## Codex audit checkpoint

Audit integration verified and deployed; Story content intentionally deferred. See [CODEX_AUDIT_VERIFICATION_20261010.md](CODEX_AUDIT_VERIFICATION_20261010.md). See the final verification section for deployment evidence and remaining content/gameplay limitations.

## Codex Story client-only checkpoint (2026-10-11)

최신 기본 대화 흐름은 클라이언트 단일 실행·로컬 중복 기록으로 변경됐고, 두 고정 보상을 접속자 본인에게 적용하는 한정 API 연결을 포함한다. UI/닉네임 ACK/컷신 공개 완료 알림 제거와 사용법은 [CODEX_STORY_LOCAL_20261011.md](CODEX_STORY_LOCAL_20261011.md)를 먼저 읽는다. 앞선 인계의 서버 그래프 필수 설치·서버 ledger 설명은 새 기본 대화 경로에 적용하지 않는다. school/wild 및 개인 런처 배포는 완료됐으며 이번 공유 Git 동기화는 재배포가 아니다. 개인 폰트·콘텐츠·설정·JAR은 공개 소스에 넣지 않는다.

## Codex portrait clip follow-up (2026-10-11)

[CODEX_PORTRAIT_CLIP_20261011.md](CODEX_PORTRAIT_CLIP_20261011.md): 앞선 Story UI 변경에서 빠진 공통 PlayerTurn 및 AI NPC 초상화 scissor 하단을 CLIP_BOTTOM으로 통일. 얼굴 위치/크기 불변, Fabric 226 테스트 및 remapJar 검증, UI 자산 901개 보존. 서버 변경 없음.

## Codex 한국어 명령·리로드 통합 (배포 완료)

4014a51 채팅·파티 기준의 한국어 명령, 클라이언트 리로드, ID 메타데이터 자동완성 및 배포 제한은 [CODEX_STORY_COMMANDS_20261011.md](CODEX_STORY_COMMANDS_20261011.md)를 확인한다. 사용자 승인으로 공유 소스에 반영. 개인 런처 및 school/wild 교체·재시작, 추가 승인된 Velocity 파티 설치·재시작 완료. 실사용 다계정 검증은 별도.

## Unit target ring integration

[CLAUDE_UNIT_TARGET_RING_20261015.md](CLAUDE_UNIT_TARGET_RING_20261015.md): selected client-only ring patch 09186da; unrelated broom-projectile ancestor excluded.

See [artifact catalogue and unit ring integration](CODEX_ARTIFACT_CATALOG_UNITRING_20261015.md) for versions, validation and deployment boundary.

[CLAUDE_BROOM_PROJECTILE_20261015.md](CLAUDE_BROOM_PROJECTILE_20261015.md): `claude/broom-projectile-20261015`. 빗자루 비행 중 직선 투사체가 뒤에서 나오는 문제 — 비행 속도 상속 + 핑 지연만큼 출발점 앞당김 (chabolt 판정·PortableVFX 연출 동일 공식). ChacademyMotion·magic-mythic·PortableVFX paper 3개 jar. magic-71 라인 BoltMech 와 머지 충돌 주의.

## Claude 빗자루 비행 궤적 (2026-10-16, 미빌드)

[CLAUDE_BROOM_TRAILS_20261016.md](CLAUDE_BROOM_TRAILS_20261016.md): `claude/broom-trails-20261016` (기준 `claude/broom-projectile-20261015`). 빗자루별 비행 궤적 — 모션 모드가 PortableVFX 클라이언트 API(무수정)로 솔 끝에 궤적 효과를 켜고 매 틱 보간 위치·기울기를 넘김. 새 팩 `BroomTrails`(기본·전설 + lite) 클라 배포 필요. 서버·패킷 변경 없음. 재시도 간격·순간이동·월드 변경·경계 히스테리시스·입자 예산 2000 반영. 한계: 내/남 궤적 투명도 분리 불가, 덮어쓰기 hook 은 로컬 전용.

## Claude 빗자루 30종 개편 (2026-10-16, 미빌드)

[CLAUDE_BROOMS30_20261016.md](CLAUDE_BROOMS30_20261016.md): `claude/broom-30-20261016` (기준 `claude/broom-trails-20261016`). 빗자루 ID를 등급 순 broom_1~30으로 재번호(지급 이력 없음, 변환 없음), bbmodel 30종, brooms.yml 새 능력치(등급 배수 제거, config-version 2 자동 교체), 스페이스 상승 유지(공통 climb-power, 시선 상승과 합산)·상하에도 선회 적용·최대 균형 90+3×안정 단계, 장비창 30칸·게이지=단계, 궤적 팩 4개(BroomTrails 교체 + Wood/Unique/Legend 신규, 팩당 시스템 16개 미만 한도 때문에 분할). 모션 모드·플러그인·magic-codex·PortableVFX 팩 4개 같이 배포.

> Claude 2026-10-20 (not built): [원소 반응 CC 개편 — VFX 팩 교체 + 반응 효과·크기 서버 코드](CLAUDE_REACTION_CC_VFX_20261020.md). Branch claude/reaction-cc-vfx-20261020 on codex/operating-final-20261019 @ 8356980. MagicMythic jar (school/wild) + client VFX pack.
> Claude 2026-10-20 (not built): [원소 반응 CC 개편 — VFX 팩 교체 + 반응 효과·크기 서버 코드](CLAUDE_REACTION_CC_VFX_20261020.md). Branch claude/reaction-cc-vfx-20261020 on codex/operating-final-20261019 @ 8356980. MagicMythic jar (school/wild) + client VFX pack.

> Claude 2026-10-25 (not built): [신규 마법 63종 325~387 실제 기능 + 어둠 마법 VFX 재제작](CLAUDE_NOVA_325_387_20261025.md). Branch claude/magic-325-387-20261025 on review/live-source-handoff-20261025-task3 @ c96c5ae3. magic-mythic / magic-codex paper+fabric / motion mod+plugin / creature-spawns / chaca-npc / tornado + client VFX packs.
