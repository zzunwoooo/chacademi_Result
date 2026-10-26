# 운영 통합·배포 결과 — 2026-10-20

학교·야생·아레나 서버, Velocity, 개인 런처와 임시 런처 배포를 완료했다. 통합 코드는 `codex/operating-final-20261019`의 `55c70c6`에 있으며, 실제 운영 기준 `7ec321a` 위에 변경을 선별 통합했다. 기능 브랜치 전체로 운영을 덮어쓰지 않았다. 큐디치·하우징·상점·빗자루 등 기존 운영 변경을 유지했다.

## 배포와 검증

- 서버: 최초 4,379개 파일 반영·구 JAR 15개 제거 후, 조준점/스킬 정의 보완 6개 파일과 구 Mythic JAR 3개 교체. 최종 설치 JAR 15개 대상과 전체 배포 파일 해시를 다시 대조했다.
- 학교 PID 5044, 야생 17136, 아레나 7132, Velocity 10260. 세 Paper 서버 `Done` 및 플러그인 활성화, 프록시 리스닝 확인. 재시작 직전 접속자 0명 확인, 정상 종료 방식 사용.
- 임시 런처 [v0.1.16](https://github.com/zzunwoooo/chacademi-launcher/releases/tag/v0.1.16) 공개. `main`은 `b2e1ee92cbcb61f8fe58049d078f9fb39212b74d`. 11개 업로드 자산의 크기·SHA256을 GitHub digest와 대조했고, 공개 `latest.yml`의 HTTP 내용·SHA512와 배포 JSON을 확인했다. 관리 모듈 85개 중 4개 변경, 나머지 81개 유지.
- 개인 인스턴스: `C:\Users\matil\AppData\Roaming\.zzunwoo\instances\launcher_v2-1.21.4`. UI·VFX JAR 및 리소스 총 1,282개 설치·해시 확인. 다른 모드 13개, options.txt, GPU 한도와 사용자 설정 유지. 한도는 max-packs 512, encoded-cache 512MiB, resident/prewarm systems 1237, GPU 1024MiB.
- 학교·야생 ItemsAdder 팩 재생성, 실제 HTTP 제공 ZIP 해시 일치. 야생 팩에 작물 모델 14종 포함. 아레나는 기존 학교 팩 URL을 사용한다.
- 테스트: Bridge 355, Fabric 412, Mythic 69, Wildlife 21, Discovery 35, Velocity 49, Kyudichi 18, Portable 서버 136 통과. Native 클라이언트 110개 중 106 통과·4 skip. 런처 테스트 8개 스크립트·lint·ASAR·업데이터·패키징 검증 통과.
- VFX 279팩·1,237시스템: 실제 로더 오류 0, 기존 DyeRinse 경고 1. 예외 허용 4팩은 정확한 해시에 한정. CPU 시뮬레이션은 통과했으나 실게임 FPS 측정은 하지 않았다.

## 최종 버전과 SHA256

| 모듈 | 버전 | SHA256 |
|---|---|---|
| Bridge | 0.31.1-integrated-20261020.1-fishing.1 | dc10b039ac6403a83c8b84b2e8eaf09f9a58e265d93120f6ca2e78126f56251a |
| Mythic | 0.2.0-integrated-20261020.1-aimpoint.1 | b09f024dbad86f7dd3a8bce492e56dffe4cd0a0fed6908411382cf2fb806f11d |
| Discovery | 0.3.0-integrated-20261019.1-mobskills.1 | b01387e3df006c82dbf87f58562ac4d1bd78ac390d12bc836c11469e7f084364 |
| Velocity party | 0.2.2-integrated-20261019.2-arenachat.1 | 7db6406a27224c555081104ba1112a0a0567c104486cf003d6a09f250a4d78f8 |
| Kyudichi | 0.1.2-integrated-20261019.1-combat.1 | 140b7e622653a0aa122b4eeb05f9f0c7ada812abde79306247384b778280c057 |
| Wildlife | 0.4.1-integrated-20261020.1-defaults.1 | eadbcec32ae6529aedbf68f159fb744ce1c06aa8e172e6dd96f2fba8ff0a844e |
| Portable 서버 | 3.2.1-integrated-20261019.2-arcana.1 | 3150b4b0e6b4959a204a30e8d64b3238715990779aafb0dc674cb05253c809fa |
| Portable 클라이언트 | 3.2.1-integrated-20261019.1-audited.1 | d3a7ec07aa9ee41c33a812e9ff78d47bb714c4748fb06e14beea8db0c791bc6d |
| Fabric UI | 0.40.1-integrated-20261020.1-fishing-icons22.1+mc1.21.4 | 0ba290808297f7cb842d748cf88feefa4e0f93a5650180b0f36007170de33a5e |

학교 팩 SHA256: `a8e98f42740290dcbd38199b022740dadfd4035874e940d7a25772016e088809`.
야생 팩 SHA256: `3e27af98b6418a7507f177c83772c1f427c432738d4f6eb75243ed95e1d7ba39`.

## 주요 보완과 유지 사항

- aa785a5는 6504a31의 **모든 바닐라 보스바 숨김과 64블록 보스 우선 표시를 유지**한다. 아이콘 중앙 정렬·호버 표시를 개선한다. 후속 좌표·로비·카메라 조준 수정도 함께 유지했다.
- 0d39e15에 있던 대상 없는 AIM 조준점 추적, 궤도 종료 후 조준점 방향 전환, 시전자 자신 추적 제외를 복원했다. 원본 대비 차이를 확인했고 기존 히트박스·VFX 변경을 건드리지 않았다.
- 첫 배포 후 플레이어용 Mythic 스킬 정의 누락을 발견해 보완했다. 기존 운영 정의와 겹치지 않는 신규 56개를 별도 파일로 추가했다. 최종 세 서버 모두 86개 주문 경로 누락·YAML 파싱 오류·중복 정의 0. 재발 방지 검증 도구 `deployment/verify_player_metaskills.py` 추가.
- 작물 기본 설정 상속 문제 수정. 자연 발생 확률 5%, 플레이어당 최대 3마리(동일 틱 예약 포함), 30초 수명 유지. 테스트용 확률 100% 스크립트는 실행하지 않았다.
- VFX 크기를 물리 입력 API로 넘기도록 공통 경로를 수정하고 프리뷰 7m/24m/8m 회귀 검증. 22개 승인 아이콘과 낚시 서버·UI 짝을 함께 배포했다. 별도 작업 중인 신규 몹 스킬 아이콘 24개는 포함하지 않았다.
- 보호한 Velocity 및 세 서버 Bridge 설정 4개는 해시 일치. 야생 worlds.yml은 Multiverse 기동 시 재직렬화되어 파일 해시가 바뀌었으나 실제 스폰 한도 50/8/10/15/4/4/4는 유지했다. 파일을 복원하거나 직접 변경하지 않았다.

## 미반영·수동 확인

- 합의 보류 14개 주문의 기능 설정은 그대로 유지했다. 175 drowsiness는 비활성, 212 cinder_skipring 직접 피해/배율은 0. 177/193은 장착형 패시브 유지.
- 새 Arcana 주문의 습득 조건·가격, 낚시 보상·장비 획득/수리·상점/하우징 연계 등 미확정 설계를 임의로 만들지 않았다. 활성 Arcana 습득 조건 32개는 검토 대기 상태다.
- 기존 Mythic 샘플 SkeletalKnight 로딩 경고와 야생 좀비 주민 3종의 villager type 경고가 남아 있다. 이번 보완 전 로그에도 있었으며 신규 주문 스킬 누락 오류는 없다. OS 성능 카운터·deprecated 이벤트·셰이더 호환 경고도 별도 기능 장애로 처리하지 않았다.
- 실게임에서 HUD 위치·64블록 경계·로비 마우스 조준·주문 충돌·모델 애니메이션·크기·낚시 조작/취소/도감·자연 작물 이벤트를 확인하지 않았다. 사용자 게임을 조작하거나 캡처하지 않았다.
- 수동 확인 시 `/wildlifeadmin status`로 버전을 보고, 플레이어가 `/야생관리 crop wheat [shiny]`로 작물 모델을 확인할 수 있다. 낚시는 `/낚시관리 미리보기 [민물|바다|얼음|동굴]`에서 보상·내구도·도감 기록 없이 조작을 확인하고, `/낚시도감`으로 UI를 확인한다. 자연 이벤트 검증을 위해 운영 확률을 올리지 않는다.

별도 백업·맵 복사·게임 강제 종료·실게임 캡처·비밀정보 출력 없이 진행했다. 필요한 승인이나 배포 차단 사항은 남아 있지 않다.

## 증거 위치

원격 호스트 `C:/Chacademi/artifacts/final-integration-20261019/`: `deployment-final-result.json`, `final-built-artifacts.json`, `final-server-deployment-plan.json`, `aimpoint-skill-correction-plan.json`, `runtime-skill-closure-audit.json`, `final-service-proof.json`, `existing-warning-baseline.json`, `worlds-post-start-observation.json`, `aimpoint-build.log`.

원격 런처 `C:/Chacademi/staging/temp-launcher-target-aim-20261019/`: `publication.json`, `public-http-verification.json`, `draft-verification.json`, `final-candidate.json`.

로컬 개인 설치 증거: `personal-final-installed.json`. 이전 `deployment-postcheck-draft-ko.md`는 배포 전 검토 초안이며 이 결과 문서가 최종 상태를 대체한다.
