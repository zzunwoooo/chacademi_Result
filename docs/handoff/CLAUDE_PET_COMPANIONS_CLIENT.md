# 동행 펫 — 클라 쪽 (메모 #81, 2026-10-26)

서버가 `magiccodex:pet_companions` (PetCompanionProtocol) 로 보낸 목록의 **모든 주인**(나·다른 사람)의 펫을 클라가 작게 그린다. 서버 몹 없음.

## 바꾼 파일 (magic-codex-fabric 만)
- `PetCompanions.java` (새로) — 채널 받기, 목록 관리, 틱마다 움직임, 월드 그리기, 발동 VFX.
- `PetCompanionMotion.java` (새로) — 움직임 계산만 (마인크래프트 클래스 없음).
- `PetModelRenderer.java` — 캐시 크기 지정(펫 창 3개 / 월드 24개), 월드 그리기 `drawWorld` (할당 없이 행렬·벡터 재사용, 텍스처 업로드는 예전 방식 그대로 한 프레임 한 장).
- `PetBbModel.java` — 애니메이션을 idle / walk(move·run) / fly(flying·glide) 따로 읽음 (없으면 idle). 모델 바닥 높이 `bottom`.
- `PetScreen.java` — 펫 카드(모델 칸·이름)에 마우스를 올리면 `Entry.info` 여러 줄 설명. 이 창의 기존 툴팁과 같은 색·글꼴, 모서리만 둥근 카드(`FishingArt.card`, 테두리 없음). info 가 비면 안 보임.
- `PetClient.java` — `/펫도감 새로고침` 이 월드 펫 모델도 다시 읽음, 안내 txt 문구 한 줄.
- `MagicCodexClient.java` — `PetCompanions.initialize()`.
- 테스트 `PetCompanionTest.java` (실행은 못 함).

## 동작
- 크기: `scale` = 보일 크기(블록), 모델의 가장 긴 변(`span`) 기준. 발밑이 모델 바닥.
- 땅(0): 주인 주변 2~3블록 안에서 가끔 새 지점으로 천천히 걸어감 → 멈추면 idle. 4블록 넘게 멀어지면 따라옴, 8블록 넘거나 세계가 바뀌면 바로 옆에 나타남. 높이는 블록 충돌면(`AimTargeting.Ground.top`)에 붙음.
- 날기(1): 주인 높이 +1.55 (둥둥 ±0.12) 에서 반지름 1.6 으로 천천히 돎 (약 12초에 한 바퀴). 땅보다 0.3 이상 위.
- 물(2): 주인이 물에 닿아 있으면 +0.5 높이 반지름 1.3, 아니면 +0.8 높이로 낮게 둥둥.
- 방향: 움직이는 쪽을 보고 부드럽게 돈다. 틱(20Hz)마다 계산, 그리기는 틱 사이를 섞음.
- 안 그림: 주인이 없음·관전자·투명·죽음, 64블록 밖. 접속 끊김/재접속/세계 바뀜이면 목록 비움. F1 이어도 그림 (월드 오브젝트).
- 빛: 펫 자리의 블록 밝기 (어두운 곳에서 어둡게).
- 발동: flags 에 FLAG_CAST 가 켜진 목록을 받으면 그 펫 발밑에 PortableVFX `claude:petpaw/circle` (펫 id 가 `_shiny` 로 끝나면 `claude:petpaw/circle_shiny`) 한 번. PortableVFX 모드가 없으면 생략.

## 게임에서 확인
1. 클라 `config/magiccodex/pets/models/` 에 펫 bbmodel 이 있는지 (펫 창 미리보기가 보이면 됨).
2. 서버(동행 펫 서버 쪽이 들어간 버전)에서 `/펫도감` → 땅 펫 하나 소환 → 주위를 걷다 멈추는지, 달려가면 따라오는지, 멀리 순간이동하면 옆에 나타나는지.
3. 나는 펫 하나, 다른 계정이 펫 하나 꺼냄 → 서로 상대 펫이 보이는지.
4. 날기 펫·물 펫 소환 → 어깨 높이에서 도는지, 물에 들어가면 몸 높이로 내려오는지.
5. 펫 창에서 모델 위에 마우스 → 설명 카드. 바닐라 펫은 없음.
6. 2·3성 펫 들고 싸우기 → 능력 발동 때 발밑 원 VFX (팩이 들어와 있을 때).
7. 투명 물약·관전자 모드 → 그 주인 펫이 안 보이는지.

## 추정한 것 (확인 필요)
- bbmodel 앞쪽은 -Z(북쪽) — 펫 창 미리보기(yaw 180 이 앞모습)와 같게 봄. 펫이 뒤로 걸으면 `PetModelRenderer.drawWorld` 에서 180° 돌리면 됨.
- `WorldRenderer.getLightmapCoordinates(world, pos)` 는 이 모드에서 처음 씀 (Yarn 1.21.4 이름, 빌드로 확인 필요).
- 렌더 레이어는 `RenderLayer.getEntityCutoutNoCull(텍스처)` (반투명 픽셀은 잘라냄).
- 땅 높이 함수가 물 표면도 땅으로 보므로, 땅 펫은 물 위를 걸어 다님.
- 프로토콜 주석의 scale "배율" 대신 오너 결정대로 "보일 크기(블록)" 로 씀 (서버 yml `size` 0.55/0.7/0.85 와 맞음).
- 이름에 `run` 이 들어간 다른 애니메이션(예: `rune_cast`)도 walk 로 잡힘. `swim` 이름 애니메이션은 따로 안 읽음(물 펫은 fly → 없으면 idle).
