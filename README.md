# 무협 RPG 개발 런처 (임시)

무협 RPG 개발 서버(마인크래프트 1.21.4, Fabric) 전용 실행기. 모드·무공 모션·VFX·쉐이더를 자동으로 받아 주고, 게임이 켜지면 서버로 바로 접속한다.
바탕: 레뮤의 썰매라이더 런처 1.0.1 (`claude/remyu-launcher` fad0ff8) + 사이렌 1.0.1 의 런처 자동 업데이트 켬. (2026-11-24)

- 배포 목록: `claude/wuxia-launcher-distro` 브랜치의 `distribution.json` (주소는 `app/assets/js/distromanager.js`). 서버 주소 211.53.196.231:25820·자동 접속도 거기서 정함.
- 저장 폴더: `%APPDATA%\.wuxia-launcher` (다른 런처와 따로). appId kr.wuxia.launcher, 실행 파일 Wuxia Launcher.
- 런처 업데이트 목록: 배포 브랜치의 `launcher/latest.yml` (generic, `electron-builder.yml` 의 publish). `index.js` checkForUpdate 가 실제로 확인함.
- 그림(코덱스, 임시, 무협RPG/런처/gen: run.sh·Art.java): 배경 `app/assets/images/backgrounds/bg.jpg` = 문파 산문과 검객 노을 장면, 아이콘 `app/assets/images/wuxia-icon.png`·`build/icon.png` = 매화와 검.
- 색: `app/assets/css/launcher.css` 끝의 "무협 RPG 개발" 묶음 (매화 분홍 포인트, 임시).

## 빌드 (호스팅)
1. 소스를 호스팅 staging 의 새 폴더에 풀고 `launcher-0.1.33-retry-20261109/node_modules` 복사
2. `tools/patch-helios-retry.cjs` 실행 (다운로드 다시 시도 패치)
3. `node node_modules/electron-builder/cli.js --win nsis --x64 --publish never`

결과: `dist/Wuxia-Launcher-setup-<버전>.exe`. 빌드 폴더는 끝나면 지운다.
