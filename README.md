# 레뮤의 썰매라이더 런처 배포 목록

런처(`claude/remyu-launcher`)가 읽는 `distribution.json` 과 레뮤 전용 파일.

- `mods/` 레뮤 전용 모드 (파일 이름 = `<이름>-<버전>.jar`, 지금은 썰매 모드 remyu-sled). 이름에 `+` 는 쓰지 않는다.
- `files/` 처음 한 번만 넣어 주는 설정 (`options.txt` = 한국어)
- `pack/generated.zip` 서버 ItemsAdder 리소스팩 (런처가 받아서 자동으로 켬)
- `server-icon.png` 서버 아이콘
- `launcher/latest.yml` 런처 앱 업데이트 목록
- 나머지(Fabric, Fabric API·Sodium·Iris·한글 패치, 쉐이더, 부가 모드)는 차카데미 쪽 배포 주소를 그대로 가리킨다. 저니맵은 뺌 (레이스 화면 미니맵과 겹침).

서버 주소 211.53.196.231:26000, 게임이 켜지면 바로 접속 (`autoconnect`).

## 모드·리소스팩을 새로 올릴 때
1. `mods/` 의 jar 를 새 것으로 바꾸거나, 서버에서 `iazip` 한 `plugins/ItemsAdder/output/generated.zip` 을 `pack/generated.zip` 으로 복사
2. `perl tools/build_distro.pl <바탕 distribution.json>` (바탕 = `claude/tower-launcher-distro` 의 distribution.json, 부가 모드는 다이아 배포 목록에서)
3. 커밋·푸시하면 런처가 다음 실행 때 받아 간다 (raw 주소 반영까지 몇 분).
