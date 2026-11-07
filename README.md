# 운빨깨굴랜드 런처 배포 정보 (distribution)

런처가 읽는 주소: `https://raw.githubusercontent.com/zzunwoooo/chacademi_Result/refs/heads/claude/kkaegul-launcher-distro/distribution.json`

- 서버 주소 바꾸기: `tools/kkaegul.json` 의 `address` 수정 → `python3 tools/build_distro.py` → 커밋/푸시 (런처 다시 빌드할 필요 없음)
- 깨굴 UI 모드 업데이트: `mods/` 의 jar 를 새 것으로 바꾸고 → `python3 tools/build_distro.py` → 커밋/푸시
  (MD5 가 바뀌면 런처가 알아서 새로 받음)
- `options.txt` / `servers.dat` 는 MD5 를 일부러 비워 둠 → 처음 설치할 때만 깔리고, 유저가 바꾼 설정은 덮어쓰지 않음
  - options.txt: 마인크래프트 기본 조작키 + 음성 설정 V + 깨굴 메뉴 M (저니맵 M 은 해제)
  - servers.dat: 깨굴랜드 서버 하나, 리소스팩 자동 허용
- 나머지 모드(Fabric API, Plasmo Voice, NoChatReports, 한글패치, 후원 모드 등)와 Fabric 로더는 원래 통합런처 배포(mchdistro/zzunwoo)에서 그대로 받음
