# 아티팩트 29개 — 그룹 D "소환·분신·말풍선" (2026-10-26, Claude)

오너 지시: 소환체·분신은 VFX(PortableVFX)로, 말풍선은 리소스 PNG 를 만들어 띄운다. 5개 모두 `artifacts.yml` 의 `enabled: false` 삭제.
빌드 도구가 없어 **컴파일 확인 못 함** (파싱 검사만 통과, 프로토콜은 javac 로 왕복 확인). GPT 빌드 때 아래 "확인 필요" 부분을 먼저 봐 주세요.

## 아티팩트별 구현

| id | 구현 |
|---|---|
| fire_spirit_lantern 화염 정령의 등불 | 서버 `ArtifactCompanions`: 1초마다 키세팅(MagicMythic `slotSpells` = 클라가 보고한 6칸, 배운 것만)이 **1개 이상이고 전부 화염**이면 정령 표시 플래그 (원소표 `spell-elements.yml`, 클라 default-spells 의 category 로 생성). 내 화염 마법 피해(MagicDamageEvent MONITOR, 반응·표식 피해 제외) → 근처 클라에 SPIRIT_SHOT → 5틱 뒤 MagicMythic `echoDamage`(20%). 클라 `ArtifactFxClient`: `claude:artifactcompanions/fire_spirit` 를 그 플레이어에 playAttached(오른쪽 어깨 뒤), 불덩이 `spirit_bolt` 를 5틱 동안 move 로 대상까지, `spirit_hit`. |
| mirror_twin 거울 쌍둥이 | 서버: 장착 플래그만 보냄. 내 마법 피해 0.5초(10틱) 뒤 같은 대상에 `echoDamage`(30%) + TWIN_ECHO. 클라: 그 플레이어의 자세(위치·몸/머리 방향·걷기·팔 휘두르기·웅크림)를 매 틱 기록해 **10틱 늦은 자세로 스킨을 반투명 하늘색**으로 한 번 더 그림 (PlayerEntityRenderer 모델 + entityTranslucent). 따라 쓰기 순간 대상에 `mirror_echo`. |
| shadow_lord_mask 그림자 군주의 가면 | 서버 `ArtifactShadows`: 내가 처치한 몹(보스·펫·길든 동물·NPC·플레이어 제외, 경기 중 제외) 자리에 `shadow_rise` VFX → 0.55초 뒤 같은 MythicMobs 종류(APIHelper.spawnMythicMob) 또는 같은 바닐라 타입 소환 → 무적·저장 안 함·드롭 0·검은 팀 글로우·`shadow_aura` VFX(서버 follow, 10초)·크기(SCALE)·아기 여부 복사. 0.5초마다: 내가 때린 적 / 나를 때린 적을 목표로, 목표 없으면 나를 따라옴(20블록 넘으면 순간이동). 10초 뒤 `shadow_fade` 후 제거. 주인당 10(넘으면 오래된 것부터), 서버 전체 40. |
| chatty_parrot 수다 앵무새 | 앵무새 = **클라 렌더** (`ArtifactParrotMixin`: 바닐라 어깨 앵무새 렌더 상태의 오른쪽 어깨에 RED_BLUE 앵무새를 넣음, 서버 엔티티 없음 → 떨어짐·남는 앵무새 없음). 서버: AsyncChatEvent → 서식 문자 제거·20자 자르기("…") → 1.5초에 한 번, 32블록 안 클라에 BUBBLE. 클라: 새 PNG `textures/gui/artifact_fx/parrot_bubble.png`(3조각: 왼쪽 끝+꼬리 / 늘어나는 가운데 / 오른쪽 끝) + MagicCodex 글꼴, 4초 뒤 사라짐. |
| shapeshift_mask 변신 가면 (X, 쿨 60) | 서버 `ArtifactShapeshift`: 모델 찾는 순서 = 소환 중인 내 펫의 ModelEngine 모델 → `plugins/MagicCodexBridge/shapeshift.yml` `models: {펫ID: 모델ID}` → 펫의 MythicMobs 몹 Skills 의 `model{mid=…}`. 보유 펫 = MCPets 권한(PetBridge `owned`). ModelEngine 으로 플레이어에 모델을 씌우고 원래 몸 숨김. 30초 뒤·사망·세계 이동·로그아웃(서버 이동)·플러그인 종료 때 복구, 접속 때 PDC 흔적 있으면 정리. 펫 없음/모델 없음/경기 중/빗자루 비행 중이면 실패 알림(쿨 안 돎). |

## 바꾼 파일
- 새 파일: `magic-codex-paper/.../ArtifactCompanions.java`, `ArtifactShadows.java`, `ArtifactShapeshift.java`, `magic-codex-paper/src/main/resources/spell-elements.yml`,
  `magic-codex-protocol/.../ArtifactFxProtocol.java` (채널 `magiccodex:artifact_fx`), `magic-codex-fabric/.../client/ArtifactFxClient.java`, `.../mixin/ArtifactParrotMixin.java`,
  `magic-codex-fabric/src/main/resources/assets/magiccodex/textures/gui/artifact_fx/parrot_bubble.png(+mcmeta)`, `portable-vfx-runtime/assets/ArtifactCompanions/` (vfx.json + 12 textures + NOTES.md),
  `magic-codex-paper/src/test/.../ArtifactFxProtocolTest.java`.
- 공유 파일 (몇 줄): `ArtifactEffects` (필드 3 + 생성 3줄 + close), `ArtifactActions` (`shapeshift_mask` case 1줄), `PetBridge` (`owned()` + Hook `getMythicMobName` 선택), `MagicCodexBridge` (`pets()`),
  `TamingTargetPolicy` (그림자 교화 불가), `magic-mythic-paper MagicMythicPlugin` (공개 API `echoDamage`·`isBoss`·`slotSpells`), `magiccodex.mixins.json`, `MagicCodexClient` (initialize 1줄), `artifacts.yml` (5줄 삭제).

## 배포 주의
- **모드 + 플러그인 같이**: MagicCodex 클라 모드(정령·분신·앵무새·말풍선 그리기), MagicCodexBridge, ChAcademiaMagicMythic(새 API 3개 — 없으면 정령 조건·따라 쓰기 피해·보스 판정이 안 됨, 경고 1회).
- **PortableVFX 팩 `ArtifactCompanions`** 를 클라이언트 효과 폴더에 배포 (없으면 정령·불덩이·메아리·그림자 안개만 안 보이고 기능은 동작). 고유 텍스처 RGBA ≈ 2.8 MiB.
- 변신 가면은 서버에 ModelEngine·MCPets 필요. 자동으로 모델을 못 찾는 펫은 `shapeshift.yml` 에 적기.

## 게임에서 확인
1. 화염 정령: 키세팅을 화염 마법만으로 → 1초 안에 알림 + 오른쪽 어깨 뒤 정령. 화염 마법으로 몹을 때리면 0.25초 뒤 작은 불덩이가 날아가 추가 피해(내 피해의 20%). 물 마법 하나 넣으면 정령 사라짐.
2. 거울 쌍둥이: 장착 후 F5 — 반투명 하늘색 내가 0.5초 늦게 따라 움직임. 마법 명중 0.5초 뒤 유리 파편 연출 + 30% 추가 피해 (원소 표식·반응 안 생김).
3. 그림자 군주: 몹 처치 → 바닥 룬 원·검은 안개 → 같은 몹이 검은 테두리로 일어나 내가 때린 적을 공격, 플레이어·펫은 공격 안 함, 10초 뒤 흩어짐. 11마리째에 가장 오래된 그림자 사라짐. 보스·펫 처치 땐 안 일어남.
4. 수다 앵무새: 장착 → 오른쪽 어깨 앵무새. 채팅 → 앵무새 위 말풍선(20자 넘으면 "…"), 4초 뒤 사라짐. 다른 사람에게도 보임.
5. 변신 가면: 펫 보유 상태에서 X → 펫 모습 30초, 끝나면 원래 모습. 펫 없을 때 X → "보유한 펫이 없어 변신할 수 없어요" (쿨 안 돎). 변신 중 죽거나 서버 이동 → 원래 모습.

## 확인 필요 / 추정한 것
- **컴파일 미확인 (yarn 1.21.4 이름 추정)**: `ArtifactFxClient.twin()` 의 `EntityRenderer#getAndUpdateRenderState`, `LivingEntityRenderState.bodyYaw/yawDegrees/pitch/limbFrequency/limbAmplitudeMultiplier`, `BipedEntityRenderState.handSwingProgress/isInSneakingPose`, `PlayerEntityRenderState.skinTextures`, `PlayerEntityRenderState.rightShoulderParrotVariant`(믹스인, require=0 이라 대상이 틀려도 모드는 안 멈춤). 이름이 다르면 같은 뜻의 필드로만 바꾸면 됨.
- Paper: `World#spawn(Location, Class, SpawnReason, Consumer)`, `Mob#getPathfinder().moveTo`, `Team#color(NamedTextColor)`. MythicMobs: `APIHelper.spawnMythicMob(String, Location)`/`getMythicMobInstance`(리플렉션), `MobManager.getMythicMob(String).getConfig().getStringList("Skills")`. MCPets `Pet#getMythicMobName`(없으면 그 경로만 건너뜀). ModelEngine 플레이어에 ModeledEntity 씌우기 (ME4 player disguise) — 실제 서버에서 다른 사람에게 몸이 숨겨지는지 꼭 확인.
- 정령 조건: "전부 화염" = 키세팅에 넣은 마법이 1개 이상이고 모두 화염 (빈 칸 무시). 6칸을 다 채워야 하는지 오너 확인 필요. `spell-elements.yml` 은 새 마법(325~)이 추가되면 함께 갱신해야 함.
- 따라 쓰기는 다단 히트·장판 보호로 플레이어·종류별 초당 6회까지. 반응 피해·표식 터뜨리기 피해는 따라 쓰지 않음. 거울 쌍둥이는 정령의 불덩이를 따라 쓰지 않음(재귀 없음).
- 그림자는 `chacademia_npc` 태그로 마법 대상에서 빠짐(아군 마법에 안 맞음). MythicMobs 몹 자체 AI가 플레이어를 노려도 피해는 서버에서 막힘(대상 지정 + 피해 이중 차단). CA_Wild 그림자가 10초 동안 야생 청크 상한(6)에 세어질 수 있음.
- 앵무새 색은 고정(빨강·파랑). 말풍선 디자인은 레퍼런스 없이 만든 기본형 — 오너 레퍼런스가 있으면 PNG 만 바꾸면 됨(3조각 32px 규칙 유지).
