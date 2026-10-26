# 명찰 칭호 연동 v2 — 미배포 검토본

배포는 중단 상태다. 이 수정본은 별도 로컬 브랜치 `feat/nameplate-titles-20261022-task7`에서 구현·빌드·검증·미리보기만 준비했다. push, release 게시, 운영 JAR/설정 교체, 재시작, 로컬 클라이언트 교체를 하지 않았다.

## 확정된 표시 규칙

- 위 줄은 순수 표시 이름이다. 이름의 기본 폰트는 칭호 유무와 관계없이 **210px**이다. 긴 이름은 동일한 실제 폰트 폭 규칙으로만 축소한다.
- 칭호가 없으면 오른쪽 안전영역 가로·세로 중앙(x≈1245, y=355)에 이름을 둔다. 칭호가 있으면 같은 크기를 유지하며 이름 위치만 y=299로 옮긴다.
- 아래 줄은 **접두+접미를 추가 공백·구분자 없이 연결**한다. 예: 황준우 / 소환술 / 교수 → 위 `황준우`, 아래 `소환술교수`. 한쪽만 있으면 해당 값만, 둘 다 없으면 빈 줄이다. 칭호 정의 자체에 포함된 공백은 보존한다.
- 두 칭호는 기존 앞→뒤 슬롯 의미를 보존하며 각 정의의 RGB 색상을 유지한다. 전체 연결 문자열을 하나의 AWT TextLayout으로 측정·색상 래스터화해 1080px 폭에 함께 맞춘다. 따로 축소한 두 문자열을 대충 붙이지 않는다.
- 플레이어와 NPC에 같은 규칙을 사용한다. 이름에 현재 장착 칭호가 기존 접두/접미 표현으로 이미 붙었으면 정확한 단어 경계에서만 제거해 아래 줄과 중복되지 않게 한다. 일반 이름 부분 문자열은 제거하지 않는다.
- 기존 채팅·칭호창·플레이스홀더·다른 UI의 이름 조합은 바꾸지 않았다. 특히 기존 `TitleDefinition.composed`의 `접두 접미 이름` 공백 규칙은 그대로다.

## 기존 칭호 재사용

실제 `TitleBridge`, `TitleStore`, `TitleDefinition`, `TitleProtocol`을 확인했다. 플레이어는 기존 `codex_title_player`의 prefix_id/suffix_id 및 `codex_title_owned` 보유 검증을 사용한다. TitleBridge의 동일한 캐시와 `selected` 검증 결과를 반환하는 읽기 전용 API만 추가했다. 명찰이 SQL load/grant/select를 호출하거나 교수 칭호를 강제 지급/장착하지 않는다.

기존 칭호 장착·해제·회수의 durable commit 후 업데이트된 캐시가 다음 명찰 스냅샷(통상 1초 이내)에 반영된다. 칭호 정의 reload의 표시 이름·색상·enabled 변경도 같은 정의를 다시 읽어 반영한다. 다른 서버의 기존 공유 DB 캐시 갱신 주기 자체는 변경하지 않았다.

NPC는 플레이어 UUID 행에 넣지 않는다. 기존 명찰 영속 키(ChacaNPC character ID → Citizens UUID → 명시적 NPC의 영속 PDC)를 그대로 사용하며 `nameplates.yml`에 `prefix-id`/`suffix-id`만 별도 매핑한다. 등록된 활성 칭호 정의·side·색상을 그대로 재사용한다. 플레이어 보유·장착·기본 지급 데이터는 수정하지 않는다.

구형 자유 직책 문자열은 자동 칭호 생성/지급/대입하지 않는다. 파일 로드 시 legacyTitle로 보관하며 별도 자유 직책을 렌더하지 않는다. 기존 레코드를 관리자가 명시적으로 칭호 지정/초기화하기 전까지 데이터는 보존되고 보조 줄은 비어 있다. 저장소는 v2 schema로 저장하며 실패 시 원본을 유지한다. 임의 NPC 일괄 데이터 변경은 수행하지 않았다.

현재 운영에서 읽어 확인한 기본 카탈로그는 prefix_starlight/bond/forest/miracle, suffix_researcher/student/tamer/explorer 8개다. `소환술`·`교수` 문구는 요청된 레이아웃 설명용 미리보기 예시이며 운영 칭호로 새 등록/지급하지 않았다. 실제 교수 칭호를 쓰려면 별도 승인된 정의를 기존 칭호 시스템에 등록한 후 그 ID를 선택한다.

## 실제 명령

기존 `/NPC관리 명찰 설정|조회|초기화` 형태를 유지한다. 설정의 마지막 값은 자유 직책 대신 등록된 칭호 ID(또는 정확히 일치하는 유일한 등록 표시 이름)다. ID의 side에 맞는 슬롯을 설정하며 반대 슬롯은 유지한다. `""`는 양쪽 칭호를 비운다. 존재하지 않거나 비활성인 칭호는 오류로 거부하며 같은 표시 이름이 여러 ID면 후보를 알려 준다.

```text
/NPC관리 명찰 설정 근처 "엘레나" suffix_researcher
/NPC관리 명찰 이름 근처 "엘레나"
/NPC관리 명찰 칭호 근처 앞 prefix_starlight
/NPC관리 명찰 칭호 근처 뒤 suffix_researcher
/NPC관리 명찰 칭호 근처 앞 비우기
/NPC관리 명찰 칭호 근처 뒤 비우기
/NPC관리 명찰 칭호 근처 초기화
/NPC관리 명찰 조회 근처
/NPC관리 명찰 초기화 근처
```

`칭호 … 초기화`는 이름을 보존하고 칭호만 비운다. 기존 `명찰 초기화`는 해당 명찰 매핑 전체를 없애 기존 이름표로 폴백한다. `칭호 <대상>`만 실행해도 조회한다. 대상에는 근처/이름/기존 명찰 이름/후보 ID를 사용하며, 이름에 공백이 있으면 따옴표를 쓴다. 기존 거리 24블록·중복 대상 거부·OP 허브와 `magiccodex.nameplates.admin` 권한을 유지한다.

앞/뒤 자동완성은 해당 슬롯의 활성 등록 칭호 ID만 제공하며 설명에는 표시 이름이 나온다. 잘못된 슬롯은 명확한 오류다. 기존 Unicode 단어 파서를 재사용하는 quoted-string 어댑터를 추가해 따옴표 없는 `근처`/한글 이름과 따옴표로 감싼 공백 포함 이름 모두를 실제 Brigadier 명령 실행 테스트로 검증했다.

## 프로토콜·검증·산출물

서버 권위 명찰 프로토콜 v2는 최대 두 개의 `{text, RGB}` run을 전달한다. 기존 v1은 호환되지 않으며 양쪽 버전이 다르면 명찰 해석을 중단해 기존 이름표로 폴백한다. 최대 32개 주변 대상이며 최악의 48-code-point 이름+48-code-point 접두/접미 두 개도 30KB 제한 안에 든다. 기존 자기 제외·UUID 검사·거리/팀/벽 가림·높이 API·3초 TTL 정책은 유지한다.

검토 산출물: SSH 호스트 `C:/Chacademi/artifacts/nameplate-titles-review-20261022-task7`.

`title-nameplates-preview.png`에는 네 기숙사와 NPC의 동일 이름에 대해 없음/접두만/접미만/둘 다 쌍이 있다. 아래 두 행은 긴 이름·긴 칭호 폭 검증용이다. 실제 원본 PNG·기존 Pretendard Bold와 런타임 측정/래스터 코드를 사용한 CPU 합성이다. 게임 GPU 화면이 아니다. Library 미리보기 ID: `libfile_5f71260ba3348191b1bbd2dfd111bab1`.

기존 칭호 소유·장착·재접속·리비전 충돌 테스트를 보존했다. 추가 회귀는 두 슬롯 무공백/색상, 이름 중복 제거, 동일 이름 기본 크기와 이동, 합친 실제 렌더 폭, 빈/잘못된/비활성/반대 슬롯 ID, 한글 인자·기존 설정 형태·자동완성·조회·초기화, 읽기 전용 캐시 변경/정의 reload, NPC legacy 데이터 보존과 플레이어 grant 미호출, v2 양색 패킷과 크기를 포함한다. 정확한 집계·새 JAR 해시는 verification.json 참조.

빌드 로그: 호스트 `C:/Chacademi/staging/nameplates-20261022-task7/titles-server-final.log`, `titles-client-final.log`. 실제 새 Bridge와 remapped Fabric JAR 교차 패킷 검증은 `protocol-compatibility.txt`에 기록한다. 수정본 버전은 `nameplates.2-titles.1`이며 **미배포**다.

## 중단 시점의 실제 배포 상태

이전 v1 배포 승인 중 작업을 시작했고, 사용자 중단 지시 당시 다음 상태였다. 진행 중 게시 Python을 중단하려는 첫 OS 프로세스 조회는 접근 거부였고, 다음 확인 시 게시 명령이 이미 완료되어 있었다. 임의 롤백하지 않았다.

- 학교: 기존 PID 24900을 정상 콘솔 stop으로 월드/청크 I/O 저장 완료 후 Bridge만 교체, PID **20504**로 정상 시작. `11:30:48 Enabling MagicCodexBridge ...nameplates.1`, `11:30:57 Done (54.785s)` 확인. 설치 Bridge SHA256 **f2a7e2a60f53d452cbffeb7da398c707d555923e82991b031eaa3e79f60ead8a**.
- 야생 PID **30464**, 아레나 PID **23616**: 교체/재시작하지 않았다. 기존 Bridge SHA256 두 곳 모두 **d9ef1c5f9a0a46860a297b66bbc7c91a518efaad22cde97da068f745b17bef68**.
- Velocity PID **25368**, supervisor PID **23584**: 변경/재시작하지 않았다. 모든 서버 alive=true였다. 전체 Control Stop 명령은 사용하지 않았다.
- 이전 제한 배포는 학교 기존 JAR의 MagicCodexBridge 초기화 연결과 plugin.yml 및 신규 명찰 클래스만 변경했다. ChacaNPC 플러그인/DB 이관은 범위 밖이며 변경하지 않았다. 원본 전체 JAR/월드 대용량 백업 대신 두 개의 기존 ZIP 항목만 작은 rollback zip에 보관했다.
- 소스 원격 `zzunwoooo/chacademi-magiccodex-source`의 **codex/operating-nameplates-20261022-task7**, 커밋 **36780a031155f21dc4e743a77f785ced3558f14e** 게시까지 완료했다. 당시 최신 호스트 Story 통합 트리도 이 기준으로 fast-forward했다. 이 커밋은 검증된 v1 `93f35b3b`의 후손이다.
- 임시 런처 `zzunwoooo/chacademi-launcher` main은 이미 **b6a43db853078b2d30c74a41ac909a62c1fedbc7**로 갱신됐고 `content-nameplates-20261022-task7` 릴리스가 공개됐다. UI **252a959f0c7a9efd169b4fe62f3eb508c739a0d517ecd3a78d75a8cf4757ae8e**, 332367227 bytes. 공개 JAR와 두 manifest 재다운로드 검증 완료. NPC/Story/채팅 등 다른 manifest 모듈은 동일했다.
- 실제 로컬 프로필 `C:/Users/matil/AppData/Roaming/.zzunwoo/instances/launcher_v2-1.21.4`: **교체하지 않음**. 중단 확인에서 14개 JAR의 추가/변경 0, Java 실행 0. 로컬 기존 UI는 SHA256 **6d8d29f1143a347c67047592cc0dcaa0dd2fa9aa5eb753916b050d11e51d960d**였다. 게임 강제 종료·새 파일 잠금 회피를 수행하지 않았다.
- 보존 모드: NPC 모델 0.1.1-npcmodel.2 `45422cc05798f0a399581c007692b103619d0994ebd5c16d88bf70b923572203`, Story 0.1.4-quest.4-visual.1 `359385ab72eb5a2c15963f0361e1ebe697db3edbc35b9d7eea3b2e9114ba4133`, 채팅 1.6.0-commands.1 `43a196dec5f6d1ccd648a648525bbb219337d85619f897882cd44f0d9125955b`.

현재 v2 작업의 기준은 **36780a03**이다. Story 소스 `cdca568`, 콘텐츠 `edb56ad`, 모델 3개는 별도 후속 묶음으로 대기 중이다. 이 수정은 SchoolBridge를 바꾸지 않았다. 최종 통합 시 해당 Story의 **분류 대화 ID ch1-15** 변경을 반드시 보존하고 오래된 SchoolBridge로 덮어쓰지 않는다. 이 브랜치를 현재 통합 소스 전체로 무조건 교체하거나 v1의 역사적 PatchNameplates 도구로 곧바로 게시하면 안 된다. 새 TitleBridge 읽기 API·v2 프로토콜·색상 래스터 캐시를 포함해 최신 묶음 기준으로 제한 배포 패키지를 다시 구성·검증해야 한다.

사용자가 애니메이션 검수를 마친 뒤 전체 묶음 재배포를 승인하기 전까지 push/게시/서버 교체/재시작/로컬 교체를 계속 보류한다. 실제 인게임 GPU·모델별 위치·이동·칼라/가림 QA는 아직 수행하지 않았다.
