# NOTE — diamond-server (2026-11-16)

**무엇**: `diamond-server/` 폴더 추가. 차카데미와 별개 프로젝트인 "나니 다이아서버"(방송 후원 이벤트 4인 서버)의 리소스.
- Blockbench 모델 13종 (`models/`), 강제 댄스 모션 (`motions/`, playerAnimator), 3D 미리보기, 생성 스크립트, 이벤트 기획 확정본
- 인수인계: `diamond-server/HANDOFF.md`

**왜**: 오너 요청으로 작업 결과를 깃에 보관하고 다른 세션이 이어받을 수 있게 함.

**영향**: 차카데미 서버·런처 파일에는 영향 없음. 빌드·배포 대상 아님.
`motions/forced_dance.json`은 playerAnimator 형식이지만 차카데미 모션 모드(`chacademy_motion`)에 넣으라는 뜻은 아님.
