# MCPets 우클릭·라이딩 및 교화 연결 복구 준비

상태: 준비/빌드/검증 완료, 운영 미적용. **학교·야생·아레나 적용 전 사전 안내 대기.**

- 독립 트리 `C:/Chacademi/staging/pet-interactions-20261023-task7`, 브랜치 `review/pet-interactions-20261023-task7`.
- 기준 `ccaed61bccb03016524fefa24d92cbb8d5337c44`. 미배포 HUD 등 6583021 미포함.
- MagicCodexBridge JAR/소스는 변경하지 않는다. 별도 하우징/검색 hotfix와 JAR 병합 충돌 없음. Bridge 설정은 충돌 없는 새 `taming.d/vanilla_override_links.yml`만 추가한다.

## 실제 운영 확인

세 서버 MCPets4.1.11, 메뉴/전역 Mountable 켜짐, 개별 Mountable=true142개/서버, AutoRide=true0개. 설정만으로 메뉴·라이딩은 비활성화할 수 있으나 우클릭 소환 해제 옵션은 없다. 공식 API와 설치 JAR 바이트코드를 대조했다.

## 최소 코드/설정 변경

- 새 독립 플러그인 `ChacaPetInteractions 0.1.0-interactions-20261023`, MCPets를 depend, ModelEngine을 softdepend.
- 정상 우클릭/위치 우클릭에서 실제 MCPets 등록 개체이고 활성 상태이며 클릭한 사용자의 UUID가 소유자와 일치할 때만 `Pet.despawn(REVOKE)` 호출.
- NPC 메타데이터/태그, 타인 펫, 소유자 없는 펫, 비활성 개체, 이미 보호 취소된 입력은 해제하지 않는다. OP도 타인 펫 해제 예외 없음. entity.remove/소유권 회수/권한 변경/플레이어 DB 직접 변경 없음.
- REVOKE는 기존 PetBridge도 쓰는 활성 해제 사유다. MCPets의 활성/마지막 소환 기록 정리는 정상 API가 담당하며 영구 보유권을 지우는 명령을 사용하지 않는다.
- 일반 설정 RightClickToOpenMenu/LeftClickToOpenMenu/Mountable/FastMount=false. 좌클릭 메뉴 우회도 닫고 좌클릭을 소환 해제로 바꾸지는 않는다.
- 개별 Mountable=true142개씩의 값만 false로 변경한다. 나머지 ID·권한·모델·스탯·능력·스킨·변색종 설정은 그대로다.
- MCPets EntityMountPetEvent, Bukkit EntityMountEvent, ModelEngine ModelMountEvent의 **MCPets 등록 개체만** 차단한다. 빗자루·큐디치·기타 차량에는 적용하지 않는다.

## 교화/펫 전체 대조 결과

현재 각 서버 교화 프로필368개. 야생은 펫452개/정의 연결 정상. 학교·아레나는 펫284개로, 이미 등록된 바닐라계84종의 일반/변색종168개 및 Mythic 정의252개가 각각 빠져 있었다.

- 학교·아레나: 야생에서 정상 사용 중인 MCPets 파일168개와 Mythic 파일7개(252정의)를 **원문 그대로 추가**한다. 대상 파일이 이미 존재하면 준비/배포 검사를 중단하므로 다른 정의를 덮어쓰지 않는다.
- 빠진 정의에 추가 모델 파일 의존성 없음. 기존 펫 모델 참조는 모두 존재. 기존 커스텀 CA_Wild/Claude 교화 연결도 누락 없음.
- 바닐라 CAVE_SPIDER/WITHER_SKELETON/GIANT는 MythicMobs 바닐라 오버라이드라 활성 Mythic 개체로 해석될 때 기존 vanilla-type fallback을 우회할 수 있다. 각 서버에3개 명시적 mythic-id 별칭을 추가하되 기존 이름·펫ID·확률·보스/변색종 정책을 재사용한다.
- 준비본을 겹친 결과 각 서버 프로필371개/펫452개, 등록 대상→펫→Mythic 연결 및 변색종 참조 누락0. 기존 보스7개 정책은 변경하지 않는다.

## 의도적/기획 확인 예외

- CA_Crop_*14개: 수확물을 맡고 정해진 수명 후 사라지는 임시 작물 소환체. 기존 수확물 반환/정리 생명주기를 깨뜨릴 수 있어 신규 교화 허용은 보류한다.
- CHA_MINION_wolf/skeleton2개: 몹 스킬 소환체, 일반 야생 펫으로 연결하지 않는다.
- MCPets 펫 개체, NPC, 이미 길들인/소유된 바닐라 동물의 기존 제외 정책 유지.
- MythicMobs 기본 ExampleMobs5개(SkeletalKnight, SkeletonKing, SkeletalMinion, StaticallyChargedSheep, AngrySludge)는 운영 교화 기획으로 등록된 대상이 아니므로 열지 않는다. 특히 예제 보스/소환체를 임의 허용하지 않는다.
- 이 예외들을 포함해 모든 개체를 새로 교화 가능하게 했다고 주장하지 않는다. 정상 등록 대상의 누락 연결만 복구한다.

## 검증

18개 통과, 실패/오류/스킵0. 실제 MCPets4.1.11/ModelEngine4.0.8/MythicMobs5.9.0으로 독립 모듈 빌드.
소유자/타인/OP/미소유/비활성/NPC/오프핸드/위치 클릭/보호 취소/양손 중복/일반·MCPets·ModelEngine 탑승/다른 차량 예외/API 실패 시 안전 동작과, 세 서버 준비본의 모든 교화·변색종·펫 참조 및 Mountable 외 필드 보존을 확인했다.
실제 인게임 클릭·탑승·교화 성공·재소환·서버 이동 검수는 미실시다.

## 적용 범위와 재시작

학교·야생·아레나 각각 새 소형 플러그인1개, MCPets 전역 설정, 개별 라이딩 설정142개, 교화 별칭1파일. 학교·아레나에만 누락 정의175파일 추가. 전체 준비 대상785개.
새 플러그인과 MCPets 설정/모브 정의의 일관된 적용을 위해 **세 서버를 각각 정상 종료/저장 후 개별 재시작**하는 방식으로 준비한다. 일반 서버 reload/PluginMan 핫로드는 사용하지 않는다. 프록시/클라이언트/런처는 변경 불필요.
현재는 stop·교체·리로드·재시작을 실행하지 않았다. 부모 사전 안내 게이트에서 대기한다. 운영 DB 이관·보유권 변경·모델 교체 없음.
다른 스레드의 하우징/검색 Bridge JAR이 새로 배포돼도 이 작업에서 덮어쓰지 않는다. 실행 직전 각 파일/서버 상태를 다시 검증해야 한다.

## 산출물

플러그인: `C:\Chacademi\staging\pet-interactions-20261023-task7\pet-interactions-paper\build\libs\chaca-pet-interactions-0.1.0-interactions-20261023.jar`

크기 5405바이트, SHA256 `df997e82e1c7ac2115758518c81d8757084b84cfebd7236a6fc6eade98b14380`.

전체 파일별 원본/준비 해시는 호스트 artifacts `pet-interactions-20261023-task7/ready.json`, `catalog-prepared.json`, `config-plan.json`에 있다. 비밀 정보와 플레이어 소유권 파일/DB는 목록에 포함하지 않는다.

## 학교·아레나 복구 대상 84종

아래 각각 일반/변색종 펫 두 정의를 복구한다. 신규 종·이름·등급·능력을 만들지 않는다.

| 기존 프로필 ID | 이름 | 바닐라 타입 | 기존 펫 ID |
|---|---|---|---|
| allay | 알레이 | ALLAY | chacademia_allay |
| armadillo | 아르마딜로 | ARMADILLO | chacademia_armadillo |
| axolotl | 아홀로틀 | AXOLOTL | chacademia_axolotl |
| bat | 박쥐 | BAT | chacademia_bat |
| bee | 꿀벌 | BEE | chacademia_bee |
| blaze | 블레이즈 | BLAZE | chacademia_blaze |
| bogged | 보그드 | BOGGED | chacademia_bogged |
| breeze | 브리즈 | BREEZE | chacademia_breeze |
| camel | 낙타 | CAMEL | chacademia_camel |
| cat | 고양이 | CAT | chacademia_cat |
| cave_spider | 동굴 거미 | CAVE_SPIDER | chacademia_cave_spider |
| chicken | 닭 | CHICKEN | chacademia_chicken |
| cod | 대구 | COD | chacademia_cod |
| cow | 소 | COW | chacademia_cow |
| creaking | 크리킹 | CREAKING | chacademia_creaking |
| creeper | 크리퍼 | CREEPER | chacademia_creeper |
| dolphin | 돌고래 | DOLPHIN | chacademia_dolphin |
| donkey | 당나귀 | DONKEY | chacademia_donkey |
| drowned | 드라운드 | DROWNED | chacademia_drowned |
| elder_guardian | 엘더 가디언 | ELDER_GUARDIAN | chacademia_elder_guardian |
| ender_dragon | 엔더 드래곤 | ENDER_DRAGON | chacademia_ender_dragon |
| enderman | 엔더맨 | ENDERMAN | chacademia_enderman |
| endermite | 엔더마이트 | ENDERMITE | chacademia_endermite |
| evoker | 소환사 | EVOKER | chacademia_evoker |
| fox | 붉은 여우 | FOX | chacademia_fox |
| frog | 개구리 | FROG | chacademia_frog |
| ghast | 가스트 | GHAST | chacademia_ghast |
| giant | 거인 | GIANT | chacademia_giant |
| glow_squid | 발광 오징어 | GLOW_SQUID | chacademia_glow_squid |
| goat | 염소 | GOAT | chacademia_goat |
| guardian | 고대 유적의 수호자 |  | chacademia_guardian |
| hoglin | 호글린 | HOGLIN | chacademia_hoglin |
| horse | 말 | HORSE | chacademia_horse |
| husk | 허스크 | HUSK | chacademia_husk |
| illusioner | 환술사 | ILLUSIONER | chacademia_illusioner |
| iron_golem | 철 골렘 | IRON_GOLEM | chacademia_iron_golem |
| llama | 라마 | LLAMA | chacademia_llama |
| magma_cube | 마그마 큐브 | MAGMA_CUBE | chacademia_magma_cube |
| mooshroom | 무시룸 | MOOSHROOM | chacademia_mooshroom |
| mule | 노새 | MULE | chacademia_mule |
| ocean_guardian | 가디언 | GUARDIAN | chacademia_ocean_guardian |
| ocelot | 오실롯 | OCELOT | chacademia_ocelot |
| panda | 판다 | PANDA | chacademia_panda |
| parrot | 앵무새 | PARROT | chacademia_parrot |
| phantom | 팬텀 | PHANTOM | chacademia_phantom |
| pig | 돼지 | PIG | chacademia_pig |
| piglin | 피글린 | PIGLIN | chacademia_piglin |
| piglin_brute | 피글린 야수 | PIGLIN_BRUTE | chacademia_piglin_brute |
| pillager | 약탈자 | PILLAGER | chacademia_pillager |
| polar_bear | 북극곰 | POLAR_BEAR | chacademia_polar_bear |
| pufferfish | 복어 | PUFFERFISH | chacademia_pufferfish |
| rabbit | 숲 토끼 | RABBIT | chacademia_rabbit |
| ravager | 파괴수 | RAVAGER | chacademia_ravager |
| salmon | 연어 | SALMON | chacademia_salmon |
| sheep | 양 | SHEEP | chacademia_sheep |
| shulker | 셜커 | SHULKER | chacademia_shulker |
| silverfish | 좀벌레 | SILVERFISH | chacademia_silverfish |
| skeleton | 스켈레톤 | SKELETON | chacademia_skeleton |
| skeleton_horse | 스켈레톤 말 | SKELETON_HORSE | chacademia_skeleton_horse |
| slime | 슬라임 | SLIME | chacademia_slime |
| sniffer | 스니퍼 | SNIFFER | chacademia_sniffer |
| snow_golem | 눈 골렘 | SNOW_GOLEM | chacademia_snow_golem |
| spider | 거미 | SPIDER | chacademia_spider |
| squid | 오징어 | SQUID | chacademia_squid |
| stray | 스트레이 | STRAY | chacademia_stray |
| strider | 스트라이더 | STRIDER | chacademia_strider |
| tadpole | 올챙이 | TADPOLE | chacademia_tadpole |
| trader_llama | 상인 라마 | TRADER_LLAMA | chacademia_trader_llama |
| tropical_fish | 열대어 | TROPICAL_FISH | chacademia_tropical_fish |
| turtle | 거북 | TURTLE | chacademia_turtle |
| vex | 벡스 | VEX | chacademia_vex |
| villager | 주민 | VILLAGER | chacademia_villager |
| vindicator | 변명자 | VINDICATOR | chacademia_vindicator |
| wandering_trader | 떠돌이 상인 | WANDERING_TRADER | chacademia_wandering_trader |
| warden | 워든 | WARDEN | chacademia_warden |
| witch | 마녀 | WITCH | chacademia_witch |
| wither | 위더 | WITHER | chacademia_wither |
| wither_skeleton | 위더 스켈레톤 | WITHER_SKELETON | chacademia_wither_skeleton |
| wolf | 회색 늑대 | WOLF | chacademia_wolf |
| zoglin | 조글린 | ZOGLIN | chacademia_zoglin |
| zombie | 좀비 | ZOMBIE | chacademia_zombie |
| zombie_horse | 좀비 말 | ZOMBIE_HORSE | chacademia_zombie_horse |
| zombie_villager | 좀비 주민 | ZOMBIE_VILLAGER | chacademia_zombie_villager |
| zombified_piglin | 좀비화 피글린 | ZOMBIFIED_PIGLIN | chacademia_zombified_piglin |
