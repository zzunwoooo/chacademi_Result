# 무한의 탑 런처 배포 목록

런처(`claude/tower-launcher`)가 읽는 `distribution.json` 과 탑 전용 파일.

- `mods/` 탑 전용 모드 (파일 이름 = `<이름>-<버전>.jar`)
- `files/` 처음 한 번만 넣어 주는 설정 (지금은 `options.txt` = 한국어)
- `server-icon.png` 서버 아이콘 (임시)
- 나머지(Fabric, Sodium·Iris·한글 패치·Fabric API, PortableVFX 와 이펙트 파일, 쉐이더)는 차카데미 런처 배포 주소를 그대로 가리킨다.

## 모드를 새로 올릴 때
1. `mods/` 의 jar 를 새 것으로 바꾼다 (새 것을 먼저 복사한 뒤 옛 것을 지울 것).
2. `perl tools/build_distro.pl <차카데미 distribution.json>` → `distribution.json` 다시 만들기
3. 커밋·푸시하면 런처가 다음 실행 때 받아 간다.

서버 주소·자동 접속은 넣지 않는다 (`address` 는 빈 글자, `autoconnect` 는 false). 유저가 게임 안에서 서버를 직접 고른다.
