# 린코 빚갚기 런처 배포 목록

런처(`claude/rinko-launcher`)가 읽는 `distribution.json` 과 린코 전용 파일.

- `mods/` 린코 전용 모드 (파일 이름 = `<이름>-<버전>.jar`, 지금은 건설 모드 rinko-build)
- `files/` 처음 한 번만 넣어 주는 설정 (지금은 `options.txt` = 한국어)
- `pack/generated.zip` 서버 ItemsAdder 리소스팩 (런처가 받아서 자동으로 켬)
- `server-icon.png` 서버 아이콘 (임시)
- 나머지(Fabric, Fabric API·Sodium·Iris·한글 패치, 쉐이더)는 차카데미 런처 배포 주소를 그대로 가리킨다.

서버 주소·자동 접속은 넣지 않는다 (`address` 는 빈 글자, `autoconnect` 는 false). 유저가 게임 안에서 서버를 직접 고른다.

## 모드를 새로 올릴 때
1. `mods/` 의 jar 를 새 것으로 바꾼다 (새 것을 먼저 복사한 뒤 옛 것을 지울 것). 이름에 `+` 는 쓰지 않는다.
2. `perl tools/build_distro.pl <바탕 distribution.json>` → `distribution.json` 다시 만들기
   (바탕 = 차카데미 또는 무한의 탑 런처 배포 목록. 무한의 탑 것: `claude/tower-launcher-distro` 의 distribution.json)
3. 커밋·푸시하면 런처가 다음 실행 때 받아 간다 (raw 주소 반영까지 몇 분).

## 리소스팩을 새로 올릴 때
1. 린코 서버 콘솔에서 `iazip` → `plugins/ItemsAdder/output/generated.zip`
2. 그 파일을 이 브랜치의 `pack/generated.zip` 으로 복사
3. `perl tools/build_distro.pl <바탕 distribution.json>` → 커밋·푸시
서버 ItemsAdder 는 팩을 직접 보내지 않는다 (no-host, auto_apply 끔).
