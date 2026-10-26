# 차카데미 기능 분류와 코드 위치 지도

작성일: 2026-10-26. 목적: Claude가 기능별 책임 코드와 설정을 찾기 위한 인수인계.

## 0. 기준과 한계
- 게임 소스: zzunwoooo/chacademi-magiccodex-source, claude/opening-final-20261025, eb18a2b0dc1b12584de480edeba299f47fa10942.
- 런처 소스: zzunwoooo/chacademi-launcher, main, 8300b83ed054307eec53523d5d0fbbd686cda85d.
- 아래 경로는 각각 원본 저장소 루트 기준. 통합 저장소 game/·launcher/는 예정 구조이며, 소스 대량 복사는 아직 완료되지 않았다.
- 파일·클래스 존재와 설정 목록을 직접 확인한 기능 지도다. 모든 기능의 코드 전체를 검증하거나 현재 운영 서버를 재접속하여 조사한 결과는 아니다.
- 소스에 있는 기능 ≠ 운영 서버에서 활성화된 기능 ≠ 게임 안에서 검증 완료된 기능.
- 운영에만 들어간 핫픽스는 deployment/provenance/OPERATING_RECONCILIATION.md와 함께 대조해야 한다.
- 기존 README의 날짜별 배포 상태는 과거 기록이다. 최신 운영 상태로 그대로 해석하지 않는다.

## 1. 코드 구조를 먼저 이해할 것
핵심은 기능마다 독립 플러그인인 구조가 아니라, 많은 기능이 MagicCodexBridge 한 플러그인에 들어 있고 대응 화면은 MagicCodex Fabric 모드에 모여 있는 구조다.

- 서버 공통 기능: magic-codex-paper/src/main/java/school/magiccodex/paper/
- 서버 기본 설정: magic-codex-paper/src/main/resources/
- 클라이언트 UI: magic-codex-fabric/src/main/java/school/magiccodex/client/
- 클라이언트 이미지·UI 자산: magic-codex-fabric/src/main/resources/assets/
- 양쪽 통신 규약: magic-codex-protocol/src/main/
- 공통 DB 관련 코드: magic-codex-database/src/main/
- 운영 덮어쓰기 설정: deployment/operating-config/
- 운영 지시서: docs/handoff/BUILD_READY_20261026.md 및 CLAUDE_OPENING_FINAL_PATCHES_20261026.md

아래에서 B는 서버 공통 Java 경로, C는 클라이언트 Java 경로, R은 서버 기본 설정 경로를 뜻한다.

## 2. 큰 갈래별 기능과 위치

### ① 네트워크·서버 이동·전체 채팅·파티
기능: Velocity 서버 연결, 전체 채팅, 귓말 연계, 파티 명단/HUD, 서버 간 이동, 튜토리얼 라우팅.
- 프록시: chacademi-party-velocity/src/main/java/ 아래 PartyPlugin, NetworkChat, TravelRelay, TutorialRouter, PartyStore, PartyHud.
- 서버 연결부: B/TravelBridge.java, PartyRoster.java, PartyVitalsBridge.java, PartyIdentityBridge.java, WhisperPeers.java.
- 설정: R/travel.yml, server-heartbeat.yml. 실제 프록시 운영 파일은 velocity.toml, party.properties, travel.properties, tutorial.properties 대조.
- 서버 ID: school, wild1, wild2, arena1, arena2. 튜토리얼 별도 서버 없음.
- 세부 네트워크 설계: docs/planning/PROXY_ARCHITECTURE.md.
주의: 프록시 연결 성공이 백엔드 명령어·DB 정상 동작까지 보장하지 않는다.

### ② 플레이어 기본 정보·기숙사·칭호·친구
기능: 닉네임, 이름표, 내 정보/능력치, 칭호, 친구, 인기, 기숙사 배정·게시판, 프리셋.
- 서버: B/NicknameBridge.java, NameplateBridge.java, StatsBridge.java, TitleBridge.java, SocialBridge.java, PopularityBridge.java, SchoolBridge.java, HouseBoardBridge.java, GeneralPresetBridge.java.
- 저장: NicknameStore, NameplateStore, TitleStore, FriendStore, SchoolStore, PopularityStore, PlayerStateStore.
- 클라이언트: C/FriendsScreen.java 및 이름표·정보·기숙사·프리셋 관련 Client/Screen.
- 설정: R/titles.yml, title-awards.yml, player-presets.yml, popularity.yml, house-board.yml.
주의: 닉네임·친구·칭호·퀘스트 공용화는 아레나까지 완료됐다고 간주하지 말 것. 역할별 실제 DB 설정을 별도 대조해야 한다.

### ③ 인벤토리·가방·우편·저장/복구
기능: 가방 페이지, 서버 이동 시 인벤토리 연계, 소유권 잠금, 스냅샷/복구, 우편함과 보상 수령.
- 서버: B/BagPagesBridge.java, BagCommitFlow.java, BagHuskCoordinator.java, BagHuskSnapshotLink.java, BagSyncSession.java, BagPageRecovery.java, InventorySaveBridge.java.
- 저장: BagPagesSql, BagSharedSql, BagNativeRedis, MailboxStore.
- 우편: B/MailboxBridge.java, MailService.java, RewardDelivery.java.
- 클라이언트: C/BagPagesClient.java, GalaxyBagScreen.java.
- 설정: R/bag-pages.yml, inventory-save.yml.
- 외부 의존: HuskSync, Redis, MariaDB.
주의: 과거 남은 가방 소유권 때문에 HuskSync가 해제되지 않아 명령어 전체가 무응답이 된 사례가 있다. 잠금 복구 처리와 재발 방지 코드의 배포 여부를 구분해야 한다.

### ④ 마법·전투·마나·강화
기능: 주문 시전, 조준, 마나, 속성, 피해/상태효과, 강화, 마력 재구성, 장비·유물.
- 서버 공통: B/ManaCasting.java, ManaService.java, SpellRuntime.java, SpellRules.java, CatalogGameplayFlow.java, EnhancementBridge.java, ReconfigurationBridge.java, EquipmentBridge.java, ArtifactBridge.java.
- 전투 구현: magic-mythic-paper/src/main/java/school/chacademia/magicmythic/.
- 외부 스킬: deployment/operating-config/MythicMobs/ 및 deployment/mythicmobs/Skills/.
- 클라이언트: C/CastingClient.java, AimClient.java, CodexScreen.java, EnhancementScreen.java, EquipmentScreen.java, ArtifactCodexScreen.java.
- 설정: R/mana-spells.yml, spell-runtime.yml, spell-elements.yml, spell-sounds.yml, enhancement.yml, reconfiguration.yml, artifacts.yml, combat-lock.yml.
- 별도 설정: magic-mythic-paper/src/main/resources/config.yml.
주의: 주문 판정, 표시 UI, VFX 준비 상태는 서로 다른 계층이다. 한쪽만 바꿔서 해결됐다고 판단하지 않는다.

### ⑤ 마법 이펙트·모션·빗자루
기능: VFX JSON 로딩·검증·렌더링, 서버 VFX 실행, 캐릭터 동작, 빗자루 비행/선택.
- 이펙트 공통/클라/서버: portable-vfx-runtime/common/, client/, paper/.
- 이펙트 자산: portable-vfx-runtime/assets/.
- 모션: chacademy-motion/chacademy-motion-mod/, chacademy-motion-plugin/.
- 서버 빗자루: 모션 플러그인의 broom/BroomService.java.
- 설정: 모션 플러그인 src/main/resources/brooms.yml, config.yml.
- 연결: B/CatalogVfxLink.java, BroomCastGuard.java; C/BroomClientLink.java, BroomSelectionScreen.java.
주의: 운영 VFX JAR 및 다섯 JSON 수정본과 개발 브랜치가 일치하지 않았던 사실이 확인됐다. 원본 브랜치를 빌드한다고 운영 핫픽스가 자동 포함되지 않는다. 수정본 배포와 실제 재접속 후 마법 시전 검증은 구분한다.

### ⑥ 스토리·튜토리얼·퀘스트·컷신
기능: 메인 스토리 단계, 대화/선택지, 튜토리얼, 퀘스트, 연구일지, 책 UI, 수업, 컷신.
- 스토리 서버: chacademy-story/plugin/.
- 스토리 클라이언트: chacademy-story/mod/.
- 편집 도구: chacademy-story/editor/.
- 일반 기능: B/QuestBridge.java, DialogueBridge.java, JournalBridge.java, BookBridge.java, LessonBridge.java.
- 설정: R/quests.yml, books.yml, lessons.yml, dialogues/.
- 실제 배포 대화: deployment/story-dialogues/.
- 운영 단계: deployment/operating-config/ChacademyStory/steps.yml.
- 새 컷신: deployment/cutscenes/ch1_harbor_arrival/, ch1_chronicle/.
- 런처 대상: config/chaca_cutscene/. 기존 ch1_ashen_night, magic_tutorial 보존.
주의: 스토리 단계 데이터와 대화 파일은 JAR 교체와 별도다. 학교 안에서 튜토리얼을 운영한다.

### ⑦ NPC·호감도·선물·모델·초상화
기능: NPC 대화, 힌트, 호감도/선물, 사회적 반응, NPC 외형, 3D 모델, 플레이어 초상화와 재생성.
- NPC 서버: chaca-npc-paper/src/main/java/kr/chacademy/npc/.
- 주요 하위: dialogue/DialogueService.java, GiftService.java; social/; appearance/AppearanceService.java.
- NPC 설정: chaca-npc-paper/src/main/resources/npcs/, prompts/, gift-likes.yml, hints.yml, places.yml, quests.yml, config.yml.
- 공통 연결: B/NpcSocialService.java, NpcSocialFacade.java, AffinityStore.java; R/affinity.yml.
- NPC 모델 클라이언트: client-mods/chacademi-npc-model/.
- 통신: chaca-npc-protocol/, chaca-npc-model-protocol/.
- 초상화 서버/통신: chaca-portrait-paper/, chaca-portrait-protocol/.
- 운영 모델 매핑: plugins/ChacaNPC/npc-models.yml. 클라이언트 모델: npc-models/.
- NPC 모델 별도 소스 지점: claude/npc-model-20261021, 0c779a3f72c6ef33012ec8e1902f97aee1577e1a.
주의: NPC 대화용 일러스트와 3D 모델과 플레이어 초상화는 서로 다른 자산이다. 런처 리소스팩이 모드 내부 대화 이미지를 덮어쓸 수 있다.

### ⑧ 탐험·발견·마법 해금·지도·이동권
기능: 발견 조건, 수동 해금 조건, 마법 발견 알림, 연구 진척, 지도, 웨이포인트, 귀환/이동권.
- 발견: magic-discovery-paper/, 주요 DiscoveryService.java.
- 발견 설정: 해당 모듈 src/main/resources/discoveries.yml, manual-conditions.yml, config.yml.
- 이동: B/TravelBridge.java, TravelStore.java, TravelItemsBridge.java, ReturnTicketBridge.java.
- 연결: B/DiscoveryLink.java, SpellHints.java; C/DiscoveryClient.java, DiscoveryQueue.java.
- 설정: R/travel.yml, travel-items.yml, return-ticket.yml, spell-hints.yml.
주의: 기존 wild라는 ID가 새 wild1 설정에 섞이지 않게 점검한다.

### ⑨ 야생·몬스터·교화·펫·환경
기능: 야생 생물 스폰, 서식지/바이옴 조건, 원거리 공격, 교화, 펫 상호작용, 이로치, 기후·계절·온도.
- 생물: creature-spawns-paper/.
- 설정: 해당 src/main/resources/config.yml, habitats.yml, biome-compatibility.yml, ranged-skills.yml.
- 몬스터 수치·스킬: deployment/operating-config/MythicMobs/, tools/mob_spec.py.
- 교화/펫: B/TamingBridge.java, TamingSpell.java, PetBridge.java, ShinyBridge.java, PetCompanions.java; pet-interactions-paper/.
- 환경: B/ClimateService.java, TemperatureService.java, SeasonClock.java.
- 설정: R/taming.yml, pet-companions.yml, shiny.yml, climate.yml.
주의: 야생 몹 운영 YAML은 파일 전체 덮기 대신 체력·피해 줄만 패치하라는 기존 배포 규칙이 있다. 기존 야생 지형은 초기화, 데이터팩만 이전하는 정책.

### ⑩ 생활·낚시·연금·토지·하우징
기능: 낚시 미니게임/도감, 낚시 장비·보물, 물고기 판매·수조, 연금, 생활 마법, 청크 소유, 개인 공간·가구·방문/검색/랭킹.
- 생활: B/FishingBridge.java, FishShopBridge.java, FishTankBridge.java, AlchemyBridge.java, LifeSpells.java, ChunkClaimBridge.java.
- 설정: R/fishing*.yml, fish-shop*.yml, alchemy.yml, chunk-claims.yml.
- 클라이언트: C/FishingScreen.java, FishingCodexScreen.java, FishTankScreen.java, AlchemyScreen.java, ChunkLandScreen.java.
- 하우징 서버: housing-paper/; 네트워크 접속 상태: housing-presence-velocity/.
- 하우징 설정: housing-paper/src/main/resources/config.yml, furniture.yml.
- 하우징 UI: C/HousingScreen.java, HousingSearchClient.java, HousingRankScreen.java.
주의: 가구 등록 자산과 ItemsAdder ID는 Java 소스만으로 완성되지 않는다.

### ⑪ 상점·경제·아이템·뽑기·후원
기능: 상점/NPC 상점, 지갑/마일리지, 아이템 보상, 감정, 타로, 100칸 뽑기판, 뽑기 상자, 편의 아이템, 확성기, 별풍선 후원.
- 서버: B/ShopBridge.java, ShopAdminBridge.java, WalletBridge.java, MileageBridge.java, AppraisalBridge.java, DrawBoardBridge.java, LootBoxBridge.java, UtilityItemsBridge.java, MegaphoneBridge.java, BalloonDonationBridge.java.
- 타로 서버는 별도 TarotBridge가 아니라 UtilityItemsBridge.java의 TarotProtocol 연결이다.
- 클라이언트: C/TarotScreen.java, DrawBoardScreen.java, DrawBoardAdminScreen.java, AppraisalScreen.java 및 상점 UI.
- 설정: R/shops/, tutorial-shop.yml, core-appraisal.yml, loot-boxes.yml, utility-items.yml, megaphone.yml, donation.yml, donation-spells.yml.
- 후원 배포물: deployment/donation/의 Bukkit 플러그인과 Fabric 모드가 한 쌍.
- ItemsAdder 연동: B/ItemsAdderItems.java. 실제 아이템 정의·텍스처·캐시·output은 운영 ItemsAdder 파일과 런처 팩을 별도로 대조.
주의: item:gift_ticket 등 ID 정의와 코드 참조의 일치가 필요하다. 교복은 확인한 소스/배포 자료에서 정의를 찾지 못했으므로 등록 완료로 표시하지 않는다. 뽑기판 구현이 존재해도 100개 운영 아이템이 입력 완료됐다는 뜻은 아니다.

### ⑫ 큐디치·1:1 대전·매칭
기능: 경기 규칙·경기장, 큐디치, 1:1 대전, 대기열·매칭·결과·랭킹 연계.
- 경기 서버: kyudichi-paper/src/main/java/school/chacademia/kyudichi/.
- 설정/구조물: kyudichi-paper/src/main/resources/config.yml, blueprints/.
- 프록시: chacademi-party-velocity/의 KyudichiQueue, KyudichiMatchmaker, KyudichiDb.
- 실제 역할: arena1=큐디치, arena2=1:1.
주의: 별도 duel-paper 모듈이 있는 것으로 안내하지 말 것. 실제 경기 좌표/경기 정의 활성화는 분리 작업과 별도로 확인해야 한다.

### ⑬ 공통 화면·HUD·채팅 표현·리소스
기능: 공통 메뉴, 창 배치, HUD 위치/크기, 툴팁, 고해상도 아이콘, 조준 UI, 채팅 표현.
- 주 모드: magic-codex-fabric/.
- 주요 클래스: C/HudCustomization.java, HudLayoutStore.java, ItemTooltipRenderer.java, HiResItemState.java, ItemIconTextures.java, DeferredScreens.java.
- 채팅 보조: client-mods/chacademi-chat-layout/.
- UI 자산: magic-codex-fabric/src/main/resources/assets/.
주의: 서버 채팅 전달과 클라이언트 채팅 레이아웃은 별개다. 설정값·UI 리소스·통신 규격을 같이 점검해야 한다.

### ⑭ 런처·배포·운영 도구
기능: 게임 설치/실행, 모드·리소스 다운로드/업데이트, 기본 리소스팩 선택, 선로딩, 실행 옵션·계정 설정.
- 별도 저장소 chacademi-launcher.
- 배포 목록: distribution.json.
- 실행/자산: app/assets/js/processbuilder.js, distromanager.js, clientassets.js, integratedcontent.js, preloader.js.
- 사용자 설정: app/assets/js/configmanager.js, keydefaults.js, serverpolicy.js.
- 감사·운영 명령: B/AuditCommands.java, AuditStore.java, OperatorCommands.java, ServerHeartbeat.java.
- 별도 운영 웹 앱 소스: ops-dashboard/. 실제 서비스 실행 여부 미확인.
주의: Git push, 콘텐츠 배포, 런처 설치 프로그램 릴리스, 실제 사용자 게임 업데이트는 서로 다른 단계다. 배포 목록의 URL·해시까지 확인한다.

## 3. 현재 오픈 범위와 구별할 코드
- gimmick-paper/: 기믹 관련 코드·모델 설정이 존재. 던전 서버는 현재 구성에서 제외했으므로 일반 운영 기능으로 활성화됐다고 단정하지 않는다.
- tornado-event-paper/: 이벤트 모듈 존재. 현재 활성화 여부 미확인.
- ops-dashboard/: 운영용 웹 소스 존재. 가동 여부 미확인.
- 외부 제품: Velocity, Paper/Purpur, MariaDB, Redis, LuckPerms, HuskSync, ItemsAdder, MythicMobs, WorldGuard, ModelEngine/MCPets 등의 실제 버전·설치·설정은 운영 목록과 대조. 이 저장소가 외부 제품 전체 소스를 포함하는 것은 아니다.

## 4. 서버 역할별 배치 원칙
- school: 중심 콘텐츠, 스토리·튜토리얼·NPC·상점. 공통 데이터/아이템/명령 기능 필요.
- wild1·wild2: 야생 스폰·전투·생활. 공통 데이터/아이템/이동 기능 필요.
- arena1·arena2: 경기 기능. 공통 데이터/아이템이 필요하지만 학교 전용 스토리·야생 스폰을 무조건 복사하지 않는다.
- Velocity: 채팅/파티/매칭/이동 중계. 서버의 게임 로직이나 플레이어 저장을 대체하지 않는다.
- 클라이언트: 모드·NPC 모델·리소스팩·컷신을 런처로 일관되게 배포.

## 5. Claude의 권장 조사 순서
1. 본 문서와 프록시 구조를 읽고 역할·서버 ID를 고정한다.
2. MagicCodexBridge.java와 plugin.yml에서 기능 등록 및 명령어 시작점을 찾는다.
3. 수정할 기능의 Bridge/Service → Store/SQL → Protocol → Client/Screen 순으로 추적한다.
4. src/main/resources 기본값과 deployment/operating-config 운영 덮어쓰기, 실제 서버 파일을 각각 비교한다.
5. 운영 핫픽스와 개발 HEAD 차이를 먼저 수집하고 기준 버전을 확정한다.
6. 서버/클라 동시 변경이 필요한 프로토콜은 짝을 맞춰 빌드·배포한다.
7. 명령어, 서버 왕복 이동/저장, 재접속 잠금, 마법 준비/시전, NPC·스토리 화면을 실제 게임에서 검증한다.

## 6. 빌드 경계
루트 settings.gradle에는 모든 클라이언트/스토리/모션/VFX가 하나로 포함돼 있지 않다. 루트 Gradle 성공만으로 전체 모듈 빌드가 끝났다고 볼 수 없다. 각 하위 프로젝트의 빌드 설정과 BUILD_READY 지시서를 사용한다.

## 7. 이 문서 이후 보완할 항목
각 기능의 명령어/권한, DB 테이블과 공유 범위, 네트워크 채널, 운영 설정 차이, 실제 설치 JAR 해시, 활성화 여부와 게임 테스트 결과를 기능별로 추가한다. 현 문서는 기능 분류와 진입점 지도이며, 모든 테이블/명령어를 검증한 상세 명세서는 아니다.
