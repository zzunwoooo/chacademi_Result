# 할로윈 하늘 배포 정리 (규칙 세션용)

- 브랜치: `feature/halloween-sky-20261031` (https://github.com/zzunwoooo/chacademi_Result/tree/feature/halloween-sky-20261031)
- 최신 커밋: 이 문서가 들어간 커밋 (그 앞 7c94b08 = 오너 미리보기 느낌 반영)
- 폴더: `chaca-halloween-sky/` (설명 `README.md`, 셰이더 설명 `shader/README.md`)
- 서버 재시작 필요 없음. 게임 안 시험은 아직 안 함.

## 1. 빌드 (클라 모드 1개)
- 위치: `chaca-halloween-sky/mod` (Fabric 1.21.4, 스토리 모드와 같은 설정)
- 명령: `gradle --offline build -x test`
  - 저쪽에서는 `C:\dgw` 연결(신기능 worktree) + 설치된 gradle.bat, TEMP/TMP=`C:\dgtmp`, 샌드박스 밖에서 실행.
- 결과: `mod/build/libs/chaca-halloween-sky-0.1.0.jar` (약 630KB, id `chaca_sky`)
- 통째로 새 모드라 **표시 주석 주입 필요 없음**. 셰이더 변경분(`shader/halloween.diff`)과 호박달 그림은 빌드 때 jar 안에 같이 들어간다.

## 2. 런처 (클라이언트)
- `mods/chaca-halloween-sky-0.1.0.jar` 하나를 `distribution.json` 에 추가 (다른 모드처럼 GitHub 릴리스에 올리고 해시·크기 기입).
- 의존: fabric-api 만. Iris 는 있으면 자동 연동 (없어도 동작).
- **셰이더 zip 은 건드리지 않는다.** Complementary 라이선스상 수정본 재배포 불가 → 모드가 Iris 로딩 때 메모리에서만 변경분을 끼운다 (`ComplementaryUnbound_r5.5.1` 일 때만, 다른 셰이더는 그대로).

## 3. 서버 (학교·야생1·야생2)
- `chaca-halloween-sky/server/halloween-sky.sk` 를 세 서버 `plugins/Skript/scripts/halloween-sky.sk` 로 복사.
- 각 서버에서 `/sk reload halloween-sky` (재시작 없음).
- 사용법 (서버마다 따로): `/할로윈하늘 켜기`, `/할로윈하늘 끄기`. 권한 `chacademi.halloweensky` (OP 는 기본 있음).
- 켜진 동안 들어오는 사람(다른 서버에서 넘어온 사람 포함)도 3초 뒤 쓸리는 연출부터 본다 (오너 요청).
- 순서: 런처 먼저. 모드 없는 사람에겐 신호(소리 `chaca_sky:halloween_on/off`)가 무시될 뿐이라 순서가 바뀌어도 문제는 없다.

## 4. 반영 후 확인
- 클라 로그에 `chaca_sky` 믹스인 오류 없음, `[할로윈 하늘] 셰이더 파일 … 원본 그대로` 경고 없음.
- 셰이더 켠 상태 / 끈 상태 각각 `/할로윈하늘 켜기` → 서쪽 끝부터 7초 동안 쓸림, 호박달(동남동 하늘) 보임, `/할로윈하늘 끄기` → 되돌아감.
- 다른 서버로 옮기면 그 서버가 켜져 있지 않은 한 원래 하늘.
- 서버 시작 로그 확인 규칙대로 Skript 로드 오류 없는지 확인.

## 동작 요약
- 신호 = 소리 패킷 (`/playsound chaca_sky:halloween_on|off|now`), 모드가 가로채 재생 안 함.
- 셰이더 끈 사람: 바닐라 하늘 위에 반구로 덧그림 (남색→보라→자홍, 청록·분홍 성운, 십자 별, 호박달 + 달무리). 세상 밝기는 그대로, 안개·구름·하늘빛에 주황빛만 살짝.
- 셰이더 켠 사람: Iris 유니폼 `chacaHalloween`·`chacaSweep`·`chacaDay` 로 Complementary 하늘·구름·안개·반사·조명 색을 바꿈. 값이 0 이면 원본과 동일.
