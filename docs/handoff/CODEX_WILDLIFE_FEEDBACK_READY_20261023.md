# 야생 스포너 5·6차 배포 준비

상태: **빌드·검증 완료, 미배포. 야생 정지 전 사전 안내 대기.** 운영은 d7d73b53 / Wildlife 0.5.0 상태로 계속 실행 중이다.

## 소스와 범위

- 작업 트리: `C:\Chacademi\staging\wildlife-feedback-20261023-task7`
- 브랜치: `review/wildlife-feedback-20261023-task7`
- 기준: `d7d73b53d952ccb50ba10c1b4b721e5ef70d467d`
- 반영: 선행 `afe6cfa3`(Terralith/크리에이티브), `db2164a5`(5차), `3ea88764`(6차).
- 기존 최신 UI/Story/서버 변경 보존. 미배포 HUD 등 6583021 미포함.
- 야생만 대상. 학교·아레나·프록시·클라이언트·런처 변경 불필요.

## 검증과 보정

Java 21, 운영 MythicMobs 5.9.0 / ModelEngine 4.0.8 / Bridge JAR을 명시하여 `:creature-spawns-paper:test :creature-spawns-paper:build :magic-mythic-paper:test :magic-mythic-paper:build` 실행.

- Wildlife **60개**, MagicMythic **83개**, 총 **143개 통과**. 실패/오류/스킵 0.
- 신규 회귀: 등급별 선택 가중치와 별도 통과확률, 같은 family 근접 억제, 수동 family 설정, Creative 포함/Spectator 제외, ModelEngine 3축 벡터 비교, 전투 설정 경계, 허스크 태그 제한과 일반 포션 예외, 원·직선·부채꼴 실제 피해 경계, 원거리 스킬의 모브/스킬 참조.
- 최초 컴파일에서 실제 `ActiveModel.getScale()`이 `Vector3fc`라 숫자 뺄셈이 불가했다. 시각 크기와 hitbox 크기의 x/y/z를 모두 비교하도록 수정했다. 모델 크기만 맞고 hitbox가 어긋난 경우도 복구한다.
- 기존 야생 정의 테스트의 FollowRange20/속도 고정 기대값을 승인된 32/1.35배로 갱신하고, 나머지 스탯·펫 정의 보존 검사는 유지했다.
- 운영 모브 파일의 **156개 Skills 목록**이 소스 기준에 추가되어 있었다. 원본 전체 덮어쓰기 대신 **MovementSpeed/FollowRange 552개 필드만** 병합해 모든 기존 연결을 보존했다.
- MagicMythic 배포용 JAR은 실제 운영 JAR 위에 `TelegraphMech.class`와 `plugin.yml` 버전만 반영했다. 다른 모든 클래스/리소스는 바이트 단위로 동일하다. 전체 raw 빌드 JAR을 운영에 넣지 않는다.

## 준비 산출물

| 산출물 | 바이트 | SHA256 |
|---|---:|---|
| Wildlife 0.5.1-feedback-20261023 | 105107 | `6d95a552e143be40a14a629dbc2e29565157fe63f4a6699e587928d496016119` |
| MagicMythic 0.2.2-wildlife-20261023 제한 교체 JAR | 427712 | `33d6ce2527c97fef2467e6a30e06f5de90127da5d79efd0835f3852463af5710` |

두 번째 JAR과 설정 준비 폴더: `C:\Chacademi\artifacts\wildlife-feedback-20261023-task7\prepared\`.
Wildlife JAR: 작업 트리 `creature-spawns-paper\build\libs\chacademia-wildlife-0.5.1-feedback-20261023.jar`.
모든 파일의 실제 경로·전후 해시는 같은 artifacts 폴더 `ready.json`에 기록했다.

## 변경 예정 대상 — 모두 wild/plugins 아래

1. Wildlife JAR 0.5.0 → 0.5.1.
2. ChAcademiaMagicMythic JAR 0.2.1 → 위 제한 교체 0.2.2. 그려진 범위와 실제 피해 판정 일치에 필수.
3. `ChAcademiaWildlife/config.yml`: area-cap24→12, 야생 변종분모4096→2048, 근접 family 가중치0.2/반경24, 등급 통과확률1/0.5/0.08/0.01, 3성 모델1.35, 전투/회복 설정 추가.
4. `ChAcademiaWildlife/biome-compatibility.yml`: 선행 Terralith 미연결35개 연결. 현재 운영본과 소스 기준의 의미가 동일함을 확인.
5. `ChAcademiaWildlife/ranged-skills.yml`: 원거리 대응 스킬 표 추가.
6. `MythicMobs/Skills/chacademi_mob_skills.yml`: 기본 공격 범위1.5배, 사거리1.3배 등의 승인 패치. 운영본과 패치 전 소스의 의미가 동일함을 확인.
7. `MythicMobs/mobs/chacademia_custom_wild.yml`: 기존156개 스킬 목록 보존한 속도1.35배/FollowRange32 병합본.

## 보존과 재시작 게이트

- 운영 `habitats.yml` 원본 SHA256 `33584856dba49096801eb82752168c343062ea297a0915c5400d52c12c055fd2` 유지, 교체하지 않음.
- 기존 CA_Natural_*138개 랜덤 스폰 파일은 계속 비활성. 복구본은 기존 wildfire 배포 rollback 폴더에 보관되어 있음.
- 운영 worlds·excluded-areas·거리24~80·청크 제한4·작물 설정 유지. 특히 작물 `cancel-drops=true`, 변종분모4096 유지. 저장소 기본 config의 cancel-drops=false로 덮어쓰지 않음.
- 필요한 재시작은 **야생만 1회**. 콘솔 stop 후 전체 차원/I/O 저장 완료를 확인하고 선택적 교체 및 개별 Start를 사용한다. `Control.ps1 -Action Stop` 사용 금지.
- 정지/적용 전에 부모 사전 안내 게이트를 기다린다. 이 준비 작업에서는 stop·교체·리로드·재시작을 실행하지 않았다.
- 확인 시 PID: school22336, wild25220, arena26932, velocity8932 모두 기동 중.
- 운영 DB 쓰기·이관 없음, 클라이언트 변경/공개 게시 없음. 따라서 공개 재다운로드 검증은 해당 없음.

## 배포 후 남은 검증

새 버전 정상 활성화, ModelEngine 스케일 오류/태스크 예외 없음, `/월드관리 야생몹 상태`의 전투 보정 및 확률 표시, MythicMobs 스킬 로드·기존 랜덤 스폰 비활성, 나머지 서버 PID와 파일 보존을 확인해야 한다.
실제 게임에서 허기 제거, 3성 모델/히트박스 크기, 추격 AI, 원거리 반격 및 비전투 회복, 생성 빈도·분포·부하 검수는 **아직 수행하지 않았다**. 자동 테스트 통과와 실제 플레이 검수는 구분한다.
