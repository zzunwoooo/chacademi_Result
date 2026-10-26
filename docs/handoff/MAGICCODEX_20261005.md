# MagicCodex 통합 증거 — 2026-10-05

최종 닉네임 staging 완료 확인: HANDOFF.txt, INTEGRATION-VERIFICATION.json 및 담당 작업 완료 기록. 승인 PNG는 `assets/magiccodex/textures/gui/nickname/panel.png`에 포함됨. 1536×1024 RGBA, crop (31,51,1476,925), 64px nine-slice, 1080×720 논리 UI. 기존 title 입력/미리보기/취소/저장/닫기 자산 재사용.

PNG SHA256: 4D84CD5341E5BE54A7B3372A4C7F064E18107D73AA71F6D6E1CEEB18DEEAD575

최종 산출물(클라이언트 및 school/wild 파일 복사 완료, 실게임 미검증):

- Client SHA256: A832BB20DB2C1B4E035D41EA9B598597E8AB848A91F15406EDCEADC1ECD01CC1
- Bridge SHA256: 0E731DF857DB7EAA620B121A0E2689B1153ED1CD79467B6686DCAA0B1C421999
- 호스트: C:\Chacademi\staging\nickname-friends-20261005-task7\artifacts
- school/wild에 설치된 Bridge SHA256은 위 0E731DF8…와 일치함을 직접 확인했습니다. 클라이언트 A832BB20… 복사는 담당 인수인계 기준입니다. 이번 Git 작업에서 JAR 교체나 서버 재시작을 수행한 것은 아닙니다.

staged 검증: 서버 6/6, focused UI 14/14 통과. 기존 Bridge entries 653, client entries 1100, asset entries 737 보존. 보호 Pet/Shiny 소스, vanilla-shiny.json, shiny PNG 186개를 해시 검증함.

저장소 재검증: Bridge 142/142 테스트 통과 및 jar 성공. Portable VFX Paper 77/77 테스트 통과 및 jar 성공. Fabric 전체 176개 중 175개 통과; SpellYamlLoaderTest는 외부 ../../spell-integration-audit/catalog-staging/hud/spells fixture 부재로 실패(기준 staging에도 부재). 실패 테스트를 삭제하거나 숨기지 않음. 최종 PNG focused 재빌드 결과는 아래에 추가.

프로토콜: NicknameProtocol.REQUEST = magiccodex:nickname_request, RESPONSE = magiccodex:nickname_response, version marker 0x4E494301, MAX_BYTES 1024. 클라이언트/서버는 같은 공통 소스를 사용함. DB는 서버 전용.

실게임 입력·플레이어 상호작용·실제 viewport 시각 검증은 미완료. 새 테이블 생성과 운영 닉네임 저장 성공은 아직 미확인.
최종 PNG 포함 저장소 Fabric focused 테스트: 14/14 통과, remapJar 성공. 기존 전체 테스트의 외부 fixture 실패 기록은 위에 보존함. 운영 파일 복사 완료, 실게임 미검증.
