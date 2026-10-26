

## PortableVFX client compatibility (tools/client_compat.py fix)

- no visual change

## WaypointGlobe 사용법 (모드 연결)
- `idle` (static, loop): 웨이포인트 지점(블록 바닥 중앙)에 상시 재생. 처음 ~1.5초 동안 마법진·지구본이 그려지며 나타나고 이후 무한 루프. 제거 시 stop 신호(남은 파티클은 수명대로 소멸).
- `activate` (static, one-shot 2.0 s + 꼬리 ~1.8 s): 플레이어가 웨이포인트를 지정/발견하는 순간 같은 지점에 1회 재생. idle 은 그대로 둔다(겹쳐서 재생되도록 설계).
  - 0 ~ 0.6 s: 링·빛줄기 수렴, 지구본 고속 회전 / 0.6 s: 섬광 → 나침반 문장, 하늘 빛기둥(약 16 m), 지면·공중 파동, 포탈 소용돌이.
- 지구본 중심 높이 1.85 m, 바닥 마법진 반경 1.5 m. 런타임 입력 없음(위치 + 서버 수평 방향만).
- held(유지) 레이어는 수명 L 의 두 사본을 L/2 간격으로 교차 페이드(rate = 2/L, 삼각 alpha) — 회전 속도 = (2π/n)/(L/2) 이라 사본이 정확히 겹침. 처음 사본은 t=0 burst(등장 연출).
