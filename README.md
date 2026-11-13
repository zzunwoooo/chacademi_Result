# 쌍룡상사 런처 배포 정보 (distribution)

런처가 읽는 주소: `https://raw.githubusercontent.com/zzunwoooo/chacademi_Result/refs/heads/claude/ssangyong-launcher-distro/distribution.json`

- 서버 주소 바꾸기: `tools/ssangyong.json` 의 `address` 수정 → `perl tools/build_distro.pl` → 커밋/푸시 (런처 다시 빌드할 필요 없음)
- 쌍룡상사 UI 모드 업데이트: `mods/` 의 jar 를 새 것으로 바꾸고 → `perl tools/build_distro.pl` → 커밋/푸시
  (MD5 가 바뀌면 런처가 알아서 새로 받음)
- `options.txt` / `servers.dat` 는 MD5 를 일부러 비워 둠 → 처음 설치할 때만 깔리고, 유저가 바꾼 설정은 덮어쓰지 않음
  - servers.dat: 쌍룡상사 서버 하나, 리소스팩 자동 허용
- 나머지 모드(Fabric API, Plasmo Voice, 한글패치, 후원 모드 등)와 Fabric 로더는 원래 통합런처 배포에서 그대로 받음
- 런처 소스: `claude/ssangyong-launcher` 브랜치 (푸시하면 자동 빌드 → `claude/ssangyong-launcher-build` 브랜치에 설치 파일 조각)
