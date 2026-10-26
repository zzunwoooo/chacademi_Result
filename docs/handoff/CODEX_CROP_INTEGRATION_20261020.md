# Crop awakening integration — prepared, not deployed

호스트 전용 작업 폴더: `C:\Chacademi\staging\crop-integration-20261020-task2`.
운영 기준: `zzunwoooo/chacademi-magiccodex-source`, `codex/operating-final-20261019` @ `81c7458c27ee31641c80bdc778392630ba6c8353`.
작물 원본: `d813abae8b3cf177d0aa722744e6e14802c6fd90`. 실제 브랜치는 **zzunwoooo/chacademi-server**의 `claude/crop-awakening-20261020`에 있으며 magiccodex-source 원격에는 없었다. 서로 다른 저장소 전체를 병합하지 않고 작물 파일 및 필요한 접점만 통합했다.

## 변경과 검증

- Wildlife 0.4.0. 기존 `Habitat`, `StructureIndex`, `PopulationCache`, `ChunkMobLimit`와 서식지 데이터는 보존. 기본 `chunk-cap: 6`, 지역 제한 및 기존 상태 명령도 보존.
- CropAwakening 리스너, 테스트 명령, 기본 설정, Mobs/Skills YAML 2개, bbmodel 14개 추가.
- 같은 틱 수확의 예약 소환까지 플레이어당 3마리 제한에 포함. 스케줄 실패 시 예약 반납, 수명 만료 시 소유 목록 정리, 접속 종료/청크 미로드 시 예약 소환 건너뜀.
- 이벤트 변경은 HIGHEST에서 처리하며 취소 이벤트는 무시. 수직 시선의 0 벡터 방지.
- 실제 야생 서버 `MythicMobs-5.9.0.jar`에서 `SpawnReason.OTHER` 존재를 javap로 확인하여 그대로 사용.
- 호스트 Java 21.0.10, Gradle 8.12.1, 실제 MythicMobs 및 운영 Bridge JAR로 `test jar` 성공. ChunkMobLimitTest 11 + CropAwakeningTest 8 = 19개, 실패/오류 0. API deprecated 경고만 있음.
- YAML 중복 키, 14개 미몹/모델 매핑, 도망 AI 선언, 빈 Drops, 48틱 기어나오기 스킬, spawn/idle/walk/attack/death, bone/element 참조, 내장 PNG CRC 및 크기 검증 통과. `asset-validation.json` 참조.
- 실제 ModelEngine 파싱·리소스팩 재생성, MythicMobs 리로드, 소환·수확·도망·30초 소멸의 게임 내 검증은 **미실행**.

JAR: `creature-spawns-paper/build/libs/chacademia-wildlife-0.4.0.jar`

SHA256: `d1fd2c3a08edd2593d6847d6fe5c8ce5ede2792aba4c9558890f80fc72a69dfd`.

빌드 재현(호스트 PowerShell):

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
$env:GRADLE_USER_HOME='C:\Chacademi\tools\gradle-cache'
Set-Location C:\Chacademi\staging\crop-integration-20261020-task2
.\gradlew.bat -p creature-spawns-paper --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx512m' '-PmythicJar=C:/Chacademi/network/wild/plugins/MythicMobs-5.9.0.jar' '-PmagicCodexBridgeJar=C:/Chacademi/network/wild/plugins/magic-codex-bridge-0.31.1-integrated-20261018.1-statushud.1.jar' test jar
```

## 부모의 최종 통합 배포 절차

현재 작업에서는 운영 파일 교체·서버 재시작·콘솔 명령을 하지 않았다. 다른 통합 작업과 조율해 아래를 최종 배포에 합친다.

1. 호스트 C 드라이브 공간 확보부터 수행. 처음 free=0으로 fetch가 실패했고, 이번 실패 fetch가 만든 임시 pack 하나만 제거했다. 빌드 후 여유 공간은 약 28 MB에 불과하다. ModelEngine 팩 생성/백업 전에 충분한 공간이 필요하다. 다른 작업의 파일은 삭제하지 않았다.
2. 최종 운영 브랜치에 이 작업 커밋을 cherry-pick하거나 `crop-delivery/crop-integration.patch`를 `git apply --check` 후 적용. 최종 소스가 추가 변경되었다면 같은 실제 의존성으로 빌드·테스트 재실행.
3. wild만 정지한 유지보수 창에서 기존 Wildlife JAR·설정, 두 대상 YAML, 동일 이름 blueprints의 원본/부재 상태를 백업. Wildlife JAR은 하나만 활성화하고 0.4.0으로 교체. habitats, 기존 야생 RandomSpawns, 기존 야생/펫 YAML, 청크 제한을 덮어쓰지 않는다.
4. `mythicmobs/Mobs/chacademia_crops.yml` → `C:\Chacademi\network\wild\plugins\MythicMobs\Mobs\chacademia_crops.yml`.
5. `mythicmobs/Skills/chacademia_crops.yml` → `...\MythicMobs\Skills\chacademia_crops.yml`.
6. `mythicmobs/blueprints/*.bbmodel` 14개 → `...\ModelEngine\blueprints\` (하위 폴더 전체 교체 금지). wild 시작 후 로그의 Wildlife 0.4.0 및 모델/미몹 오류 확인.
7. `/meg reload` → 재생성된 리소스팩을 현재 운영 배포 경로로 전파·클라이언트 재수신 확인 → `/mm reload` → `/야생관리 reload`. 각 명령이 실제 성공했는지 콘솔 응답과 새 로그로 확인. 자동 재생 spawn의 중복 여부는 여기서 확인한다.
8. 게임에 접속한 관리 플레이어가 `/야생관리 status`로 chunk-cap=6을 확인하고 `/야생관리 crop wheat`, `/야생관리 crop pumpkin shiny` 실행. 약 2.4초 기어나오기·그 후 플레이어에게서 도망·약 30초 연기 후 제거 확인. 명령은 Player 전용이므로 콘솔 호출만으로 이 테스트를 수행했다고 기록하지 않는다.
9. 다음 확률 보호 도구로 실제 수확 테스트. 최대 성장 밀/당근/감자/비트/네더 사마귀, 줄기가 붙은 호박/수박을 준비하고 미성장·크리에이티브·설치 호박 제외, 드롭 취소, 3마리 제한도 확인. 게임 조작/관찰의 실제 수행자와 결과를 기록한다.
10. 실패하면 원래 확률과 설정 복원이 확인된 뒤 중단한다. 배포 자체 롤백은 wild 정지 후 백업한 Wildlife JAR/대상 자산 복원, 새 파일은 원래 부재였던 것만 제거하고 재시작·리로드·팩 재배포한다.

## 실제 수확 확률과 실패 시 복원

검토 시 운영 `plugins/ChAcademiaWildlife/config.yml`에 crop-awakening 섹션은 없었다. 새 JAR 적용 시 **기본 유효 확률 0.05 (5%)**이며 파일에 명시된 기존 chance는 **없음**이다. 배포 직전 다시 읽어야 한다.

`creature-spawns-paper/tools/Test-CropHarvest.ps1`는 원본 설정 바이트와 SHA256, 명시 확률 유무, 유효 원래 확률을 `.original`/`.original.json`에 기록한다. 일시적으로 chance=1.0으로 설정하고 리로드, 테스트 콜백을 실행한 뒤 **finally에서 테스트 성공/실패와 관계없이 원본 바이트를 복원하고 다시 리로드**한다. 전용 유지보수 구간에 실행하며 테스트 동안 다른 운영자가 같은 설정을 편집하지 않는다.

PowerShell 7에서 실제 콘솔 전송 어댑터와 게임 수확 확인 콜백을 제공한다. 두 콜백은 실패 시 throw하고, SendCommand는 서버 응답까지 확인해야 한다. 단순 출력 콜백은 실제 테스트에 사용하지 않는다.

```powershell
& .\creature-spawns-paper\tools\Test-CropHarvest.ps1 `
  -SendCommand $confirmedWildConsoleCommand `
  -HarvestTest $actualPlayerHarvestAndObservation
```

프로세스 강제 종료/호스트 장애는 finally 실행을 보장할 수 없다. 출력된 원본 BackupPath를 보존하고 재개 전에 아래 복구부터 실행한다(다른 배포 설정 변경과 충돌하지 않도록 조율).

```powershell
& .\creature-spawns-paper\tools\Test-CropHarvest.ps1 `
  -RestoreOnly -BackupPath '<출력된 .original 절대 경로>' `
  -SendCommand $confirmedWildConsoleCommand
```

복원 도구 검증: 격리 파일의 섹션 부재(유효 .05), 명시 .123, 의도적 테스트 예외 모두 바이트 단위 복원 PASS. 이 검증의 콘솔은 DRY-RUN이며 운영 chance는 변경하지 않았다.

## 포함 모델

`crop_wheat.bbmodel`, `crop_wheat_shiny.bbmodel`, `crop_carrot.bbmodel`, `crop_carrot_shiny.bbmodel`, `crop_potato.bbmodel`, `crop_potato_shiny.bbmodel`, `crop_beetroot.bbmodel`, `crop_beetroot_shiny.bbmodel`, `crop_pumpkin.bbmodel`, `crop_pumpkin_shiny.bbmodel`, `crop_melon.bbmodel`, `crop_melon_shiny.bbmodel`, `crop_nether_wart.bbmodel`, `crop_nether_wart_shiny.bbmodel`.

미몹 ID는 `CA_Crop_` + 작물 ID + 선택적 `_shiny`. 모델 ID는 파일명에서 `.bbmodel`을 뺀 값. 스킬은 `CA_Crop_Awaken`, `CA_Crop_Hurt`.
