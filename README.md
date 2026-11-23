# 레뮤의 썰매 레이스 런처

레뮤 썰매 서버(마인크래프트 1.21.4, Fabric) 전용 실행기. 모드·리소스팩·쉐이더를 자동으로 받아 주고, 게임이 켜지면 서버로 바로 접속한다.
바탕: 돈갚아요(린코) 런처 1.0.3 (`claude/rinko-launcher` 6c018e9) ← 무한의 탑 런처 ← 차카데미 런처(Helios 기반).

- 배포 목록: `claude/remyu-launcher-distro` 브랜치의 `distribution.json` (주소는 `app/assets/js/distromanager.js`). 서버 주소·자동 접속도 거기서 정함.
- 저장 폴더: `%APPDATA%\.remyu-launcher` (다른 런처와 따로).
- 런처 업데이트 목록: 배포 브랜치의 `launcher/latest.yml` (generic, `electron-builder.yml` 의 publish).
- 그림: 배경 `app/assets/images/backgrounds/bg.jpg` (게임 화면풍 밤 썰매 레이스 + 평면 제목), 아이콘 `app/assets/images/remyu-icon.png`·`build/icon.png` (썰매 탄 선수 얼굴, 고글에 오로라·링 비침) — 코덱스로 뽑음 (레뮤썰매런처gen: parts2.txt·parts3.txt·Art.java).
- 색: `app/assets/css/launcher.css` 끝의 "레뮤의 썰매 레이스" 묶음 (게임 레이스 화면과 같은 하늘색 포인트).

## 빌드 (호스팅)
1. 소스를 호스팅 staging 의 새 폴더에 풀고 `launcher-0.1.33-retry-20261109/node_modules` 복사
2. `tools/patch-helios-retry.cjs` 실행 (다운로드 다시 시도 패치)
3. `node node_modules/electron-builder/cli.js --win nsis --x64 --publish never`

결과: `dist/Remyu-Launcher-setup-<버전>.exe`. 빌드 폴더는 끝나면 지운다.
