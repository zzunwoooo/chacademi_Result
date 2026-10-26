## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change (client_compat check OK, validate OK)

## JackInTheBox 사용법 (깜짝 상자 아티팩트, 2026-10-26)
- `pop` (static, 2.6 s, 1회): 플레이어가 쓰러진 자리 발밑에 서버가 재생 (`claude:jackinthebox/pop`, MagicCodexBridge `ArtifactMotions`).
  0~0.25 s 상자가 덜컹 → 0.25 s 뚜껑 열림(별 섬광·링·먼지·색종이 4색) → 용수철 광대 인형이 통통 튀고 좌우로 흔들림 → 1.8 s 연기 펑 + 반짝이 → 2.0 s 사라짐.
- 인형·상자는 8x8 플립북 한 장(`T_JackPop_Sheet`, 64 px 칸, 수명 2.0 s 동안 1회)을 카메라를 보는 빌보드 하나로 그린다. 플립북 시간은 텍스처 생성기의 LIFE 2.0 과 맞춰 두었으니 `01 Toy` 의 life 를 바꾸면 뚜껑 열림(프레임 8)과 섬광(0.25 s)이 어긋난다.
- 텍스처 7장, RGBA 약 1.2 MiB (512x512 플립북 1 MiB + 128 px 6장).
- 생성 스크립트·spec 은 저장소에 넣지 않았다 (anime-vfx-maker 툴킷, 2026-10-26 세션 작업 폴더).
