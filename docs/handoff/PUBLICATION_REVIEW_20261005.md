# 공개 준비 및 이력 정리 — 2026-10-05

기존 저장소 이력 교체는 자동 승인 검토에서 차단됐으며 실행되지 않았습니다. 새 비파괴 대안을 사용자가 승인하여 zzunwoooo/chacademi-magiccodex-source를 새 공개 저장소로 생성합니다. 기존 chacademi-server 저장소의 브랜치·태그·이력·PRIVATE 상태를 유지하며, 현재 정리된 소스만 일반 최초 푸시합니다. 원본 private bundle도 호스트에 보존합니다. 이번 Git 작업의 운영 서버·DB·프로세스 변경은 없습니다.

제외 자료: mob-biome-catalog-v1/models의 공유 제한 출처 모델 JS 51개(Ogres, LostAssets Farmstead/FairyLake/HerbGarden, LunarStudios Aquatic 원본 및 이로치 뷰어). 이미 삭제한 ogre_club.js도 새 이력에는 없습니다. mob-pet-asset-review-v1/config-texts.json에서 해당 묶음 및 Boxpix Easter 원본 설정 29항목을 제외하고 나머지 822항목은 유지했습니다.

근거는 mob-pet-asset-review-v1/detail.json에 기록된 원본 Guide.txt/Readme.txt/TOS-README.txt입니다. Ogres는 신뢰하는 서버 팀 외 공유 금지, LostAssets는 재배포 금지, LunarStudios는 제3자 다운로드 금지, Boxpix Easter는 공유 금지 문구입니다. 모델이 없는 Easter는 원본 설정만 제외했습니다. 출처 메타데이터와 사용자 작성 기능·모델 ID 참조는 보존했습니다.

기능 모듈 전체 1447 파일을 이전 최신 커밋의 Git archive와 바이트 비교해 동일함을 확인했습니다. 여기에는 Fabric·Bridge·protocol·DB·Portable VFX·기존 서버 모듈 및 Pet/Shiny/UI 자산이 포함됩니다. 승인 PNG SHA256: 4D84CD5341E5BE54A7B3372A4C7F064E18107D73AA71F6D6E1CEEB18DEEAD575.

현재 트리의 자격증명/개인 키 형식 및 DB 실데이터/비밀 설정/외부 유료 JAR 종류를 점검합니다. 비밀 값은 출력하지 않습니다. 최소 점검이며 절대적인 무비밀 보증은 아닙니다.

일부 참고 모델 미리보기가 제외됩니다. 게임 소스나 운영 모델 설치를 제거한 것은 아닙니다. reference/source-manifest.json은 과거 인수 자료 목록이며 현재 파일 존재를 보장하지 않습니다.

GitHub 캐시·타인의 clone·이전 다운로드에서 과거 객체가 완전히 삭제되는 것은 보장할 수 없습니다. 원격 브랜치/태그/PR refs 및 최종 commit/visibility를 확인합니다. 오래된 SHA의 캐시 제거에는 GitHub 지원 절차가 별도로 필요할 수 있습니다.