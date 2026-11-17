# 무한의 탑 런처

무한의 탑 서버(마인크래프트 1.21.4, Fabric) 전용 실행기. 모드·이펙트·쉐이더를 자동으로 받아 서버에 바로 접속한다.

- 바탕: 차카데미 런처(`zzunwoooo/chacademi-launcher` release/launcher-0.1.33-retry-20261109, 027e1b0) → 그 바탕은 공개 Helios Launcher (dscalzi/HeliosLauncher, MIT). `LICENSE.txt`, `THIRD-PARTY-NOTICES.md` 참고.
- 배포 목록: `claude/tower-launcher-distro` 브랜치의 `distribution.json` (주소는 `app/assets/js/distromanager.js`).
- 저장 폴더: `%APPDATA%\.infinitetower-launcher` (차카데미 런처와 따로).

## 차카데미 런처에서 바꾼 것
- 이름·아이콘·설치 파일 이름·저장 폴더·배포 목록 주소.
- 차카데미 전용 실행 전 검사(이펙트 묶음 zip, 통합 리소스팩)는 배포 목록에 그 항목이 있을 때만 돈다.
- 차카데미 전용 키 배치(Shift 달리기 등)는 적용하지 않는다.
- 런처 자동 업데이트는 꺼 둠 (`index.js` 의 `checkForUpdate`). 전용 배포 주소가 정해지면 다시 켠다.
- 아이콘은 임시 (게임 UI 그림 `result_floor_badge.png`), 배경은 그림 없이 어두운 보라 그러데이션.

## 빌드 (호스팅)
node 22 + 차카데미 런처 빌드에 쓰던 `node_modules` 를 그대로 쓴다.

```
node tools/patch-helios-retry.cjs
node node_modules/electron-builder/cli.js --win nsis --x64 --publish never
```

결과: `dist/InfiniteTower-Launcher-setup-<버전>.exe`
