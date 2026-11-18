# 린코 빚갚기 런처

린코 빚갚기 서버(마인크래프트 1.21.4, Fabric) 전용 실행기. 모드·리소스팩·쉐이더를 자동으로 받아 준다.

- 바탕: 무한의 탑 런처(`claude/tower-launcher`, 2029095) → 그 바탕은 차카데미 런처(`zzunwoooo/chacademi-launcher` release/launcher-0.1.33-retry-20261109, 027e1b0) → 공개 Helios Launcher (dscalzi/HeliosLauncher, MIT). `LICENSE.txt`, `THIRD-PARTY-NOTICES.md` 참고.
- 배포 목록: `claude/rinko-launcher-distro` 브랜치의 `distribution.json` (주소는 `app/assets/js/distromanager.js`).
- 저장 폴더: `%APPDATA%\.rinko-launcher` (차카데미·무한의 탑 런처와 따로).

## 차카데미 런처에서 바꾼 것
- 이름·아이콘·설치 파일 이름·저장 폴더·배포 목록 주소.
- 차카데미 전용 실행 전 검사(이펙트 묶음 zip, 통합 리소스팩)는 배포 목록에 그 항목이 있을 때만 돈다. 린코는 통합 리소스팩 자리에 ItemsAdder 팩을 넣는다.
- 런처는 서버 주소를 갖고 있지 않다: 서버 상태 표시·자동 접속 없음, 유저가 게임 안에서 서버를 직접 고른다.
- 차카데미 전용 키 배치(Shift 달리기 등)는 적용하지 않는다.
- 런처 자동 업데이트는 꺼 둠 (`index.js` 의 `checkForUpdate`).
- 아이콘은 임시 (상점 1호 검수 그림), 배경은 그림 없이 청록 그러데이션. 정식 그림이 나오면 `app/assets/images/rinko-icon.png`·`build/icon.png`·`app/assets/js/scripts/uibinder.js` 배경을 바꾼다.

## 빌드 (호스팅)
node 22 + 차카데미 런처 빌드에 쓰던 `node_modules` 를 그대로 쓴다.

```
node tools/patch-helios-retry.cjs
node node_modules/electron-builder/cli.js --win nsis --x64 --publish never
```

결과: `dist/Rinko-Launcher-setup-<버전>.exe`
