# 스토리 컷신 배치 (런처)

각 폴더를 **런처 `config/chaca_cutscene/<폴더 이름>/`** 에 그대로 넣는다 (cutscene.yml + PNG + bgm.ogg). 서버에는 필요 없음.
컷신 id = 폴더 이름 = steps.yml 의 `컷신:` 값. 목록·기획: [docs/plans/story-cutscenes-ch1-ch2.md](../../docs/plans/story-cutscenes-ch1-ch2.md)

| 폴더 | 단계 | 상태 |
|---|---|---|
| `ch1_harbor_arrival` | ch1-7 (브람 "출항이다!" 다음) → 끝나면 ch1-8 `essentials:warp harbor` | **새로 배포** (오너 납품 2026-10-26) |
| `ch1_ashen_night` | 튜토리얼 맨 처음 (tutorial 단계) | 런처에 이미 있음 (그대로, 건드리지 않음) |
| `ch1_chronicle` | 1장 끝 ch1-23 (연대기 세 권 읽은 뒤) → ch1-24 엔딩 | **새로 배포** (오너 그림 7장 + 음악, 약 84초, 2026-10-26) |
| `magic_tutorial` | ch1-4 | 런처에 이미 있음 (그대로) |


