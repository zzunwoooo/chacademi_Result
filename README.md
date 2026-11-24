# 무협 RPG 개발 서버 런처 배포 목록

런처(`claude/wuxia-launcher`)가 읽는 `distribution.json` 과 무협 전용 파일. (2026-11-24, 레뮤 배포 목록에서 갈라 만듦)

- `mods/` 무협 전용 모드 (파일 이름 = `<이름>-<버전>.jar`, 지금은 무공 모션 모드 wuxia-motion). 이름에 `+` 는 쓰지 않는다.
- `vfx/` 무공 VFX 20묶음 (보관함 `mc-asset-vault/vfx/wuxia-rpg-vfx` 의 회피·평타·입문공) → 게임 폴더 `config/portablevfx/effects/`
- `files/` 처음 한 번만 넣어 주는 설정 (`options.txt` = 깨굴 런처 기본 설정에서 깨굴 키만 뺌, 한국어)
- `server-icon.png` 서버 아이콘
- `launcher/latest.yml` 런처 앱 업데이트 목록 (1.0.0 릴리스를 올린 뒤 넣는다)
- 나머지(Fabric, Fabric API·Sodium·Iris·한글 패치·PortableVFX, 쉐이더, PortableVFX 메모리 설정)는 무한의 탑 배포 목록,
  부가 모드(authme·blurserveraddress·donation·journeymap·keyeventmod·nochatreports·plasmovoice·playeranimator·wi_zoom)는 다이아 배포 목록의 주소를 그대로 가리킨다.

서버 주소 211.53.196.231:25820, 게임이 켜지면 바로 접속 (`autoconnect`). 리소스팩은 지금 서버 ItemsAdder 가 직접 내려줌(100MB 이하).

## 모드·VFX 를 새로 올릴 때
1. `mods/` 의 jar 를 새 것으로 바꾸거나, `vfx/<묶음>/` 을 보관함 새 판으로 교체 (서버 `plugins/PortableVFX/effects/<소문자 묶음>/` 도 같이)
2. VFX 를 바꿨으면 클라 PortableVFX 로더 검사(`hb2/n1103/pvxc` 의 H.java + 배포 중인 클라 jar)로 errors=0 확인
3. `perl tools/build_distro.pl /c/dtd/distribution.json`
4. 커밋·푸시하면 런처가 다음 실행 때 받아 간다 (raw 주소 반영까지 몇 분).
