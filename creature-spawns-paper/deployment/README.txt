Wildlife 0.5.0-spawner-20261023 -- 자체 스폰 루프 (MythicMobs RandomSpawns 대체)

무엇이 바뀌었나
- 야생 몹 첫 스폰을 MythicMobs RandomSpawns가 아니라 이 플러그인이 직접 한다.
  몹은 그대로 미몹 몹(CA_Wild_<id> / CA_Wild_<id>_shiny)으로 소환(SpawnReason.NATURAL)되므로
  모델·스킬·무리 추가·자연 표시·디스폰 정리는 전부 기존 그대로.
- 루프: 야생 월드(config worlds)의 플레이어(관전 모드 제외, 크리에이티브 포함)마다 5초에 한 번 차례, 차례마다 3번 시도.
  시도 1번 = 플레이어 24~80칸 링의 로드된 청크 한 지점 → 청크 상한(4) → 지표/물속/동굴 바닥 높이
  → 그 바이옴 종 중 시간·계절·높이·지상/수중이 맞는 종만 남겨 등급 가중치로 1종 추첨
  → 기존 서식지 전체 검사(동종 상한·지역 24·보호 구역·구조물 포함) → 소환.
  한 틱 전체 시도는 최대 40번(서버 부담 상한). 청크 로드·생성·구조물 탐색 없음. 전부 메인 스레드.
- 등급(habitats.yml 각 종의 rarity: 1 / 2 / 3 / boss). 예전 RandomSpawns 시도확률을 그대로 비율로 사용:
  1성 4.5 : 2성 1.8 : 3성 0.4 : boss 0.06 (config spawner.rarity-weights). 종 하나만 따로 조절하려면
  habitats.yml 그 종에 weight: <숫자> 를 넣으면 등급 가중치 대신 그 값으로 추첨.
  운영 habitats.yml에 rarity가 없으면 플러그인에 들어 있는 기본 habitats.yml의 등급을 자동으로 씀.
- 청크(16x16 세로 기둥)당 커스텀 야생 몹 최대 4마리 (chunk-cap 6 → 4).

배포 (야생 서버만)
1. 야생 서버 종료.
2. plugins의 예전 chacademia-wildlife JAR 제거 → 새 JAR 설치.
3. MythicMobs RandomSpawns에서 야생 규칙 파일 제거(이중 스폰 방지):
   plugins/MythicMobs/RandomSpawns/chacademia_wildlife.yml (CA_Natural_* 138개) 를 서버 밖으로 옮겨 보관.
   다른 RandomSpawns 파일은 건드리지 않는다.
4. plugins/ChAcademiaWildlife/config.yml 을 레포 config.yml로 교체하고 worlds·excluded-areas만 운영 값으로 되돌림
   (2026-10-23 바뀐 값: area-cap 12, chunk-cap 4, shiny-denominator 2048, rarity-model-scale, spawner의
    rarity-attempt-chance·similar-nearby-*). 교체가 어렵다면 아래 최소 수정:
   - "chunk-cap": 6 → 4
   - spawner 항목은 없어도 기본값(켜짐, 5초, 3회, 틱당 40, 4.5/1.8/0.4/0.06)으로 동작.
     값을 바꾸고 싶으면 레포 src/main/resources/config.yml의 "spawner" 블록을 복사해 넣고 수정.
5. plugins/ChAcademiaWildlife/biome-compatibility.yml 을 레포 최신 파일로 교체 (필수).
   새 terralith-biomes 섹션이 yellowstone 등 서식지 없던 Terralith 바이옴 35개를 비슷한 바이옴에 연결함.
   기존 파일은 자동으로 덮어쓰지 않음.
5-1. (선택) habitats.yml을 레포 최신본으로 교체하면 rarity 줄이 파일에 보여서 운영자가 직접 고치기 쉬움.
   교체 안 해도 등급은 자동 적용됨.
6. 서버 시작.

확인
- /서버관리 상태 → "야생몹 0.5.0-spawner-20261023 · 켜짐 · 자체 스폰 켜짐 · 청크 제한 4"
- 야생 서식지(바이옴 맞는 곳)에서 생존 모드로 몇 분 돌아다닌 뒤
  /월드관리 야생몹 상태 → "자체 스폰 시도=… 소환=… 결과={OK=…, 바이옴/시간대/… 실패 이유별 개수}"
  /월드관리 야생몹 진단 → 내 주변 48칸에서 나올 수 있는 몹 목록
  /월드관리 야생몹 확률 → 내가 선 자리에서 종별로 뽑힐 확률(%)과 이 자리 가능 여부
- "(구) 미몹 랜덤스폰 조건 호출=…" 줄이 보이면 3번 RandomSpawns 파일이 아직 남아 있는 것.
- 더 이상 필요 없는 예전 확인 항목: Mythic SpawningLimit / GenerateSpawnPoints / MaxLocalMobs
  (자체 루프는 이 값들을 거치지 않음). 야생 몹 기본 타입이 HUSK이므로 난이도 Peaceful에서는
  여전히 안 나올 수 있음 → /difficulty easy 이상 확인.

롤백: 서버를 멈춘 상태에서 예전 JAR, 예전 config.yml, 보관해 둔 chacademia_wildlife.yml을 함께 되돌린다.
