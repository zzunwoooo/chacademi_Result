# 나니 다이아서버 — 인수인계

마지막 작업: 2026-11-06 (이 파일 작성 2026-11-16)

## 저장 위치

GitHub `zzunwoooo/chacademi_Result` 저장소.
- **작업 브랜치: `claude/diamond-server`** (2026-11-16 생성, 여기서 개발). 저쪽 작업 폴더는 `C:\ddm`.
- 처음 받은 자료 원본: `inbox-diamond-server-20261116` 브랜치 (그대로 둠).

## 한 줄 요약

방송 후원(별풍선) API와 연동되는 마인크래프트 4인 서버 "다이아 서버"의 후원 이벤트를 기획했고, 이벤트에 쓸 Blockbench 모델 13종과 강제 댄스 모션 1개까지 만들었다. **서버 플러그인·클라 모드 코드는 아직 한 줄도 없다.**

## 환경 (사용자가 정한 것)

- 서버: **Paper 1.21.4**
- 클라이언트: **Fabric 1.21.4 모드** 사용 예정 (HUD, 조작 반전, 룰렛·투표 화면 등)
- 플레이어 4명. 목표 다이아 개수를 채우는 방송 컨텐츠
- 모션: playerAnimator(KosmX 2.0.5) 형식 JSON

## 확정된 결정

- 이벤트 기획·가격표·디스코드 공지문 전체: [`docs/events.md`](docs/events.md)
- **이로치(shiny) 버전은 필요 없음.** 앞으로 만들 모델은 기본 버전만
- AI 플레이어는 **채팅·LLM 없이 광질만** 하는 Citizens NPC로 (Mindcraft 같은 LLM 봇은 안 씀)
- 100개 장난 풀은 위 15종으로 확정. 별도의 200개 구간을 새로 만들지 않음 (사용자 지시)
- 용어: "트랙터 빔" 말고 **"UFO 빔"**

## 아직 안 정한 것

- [ ] 3000 투표의 2번이 즉사면 4444를 싸게 사는 셈이 됨 → 2번을 "목표 +200"으로 바꾸자고 제안했지만 답 없음
- [ ] 즉사 시 인벤토리·목표 처리(초기화? 그냥 리스폰?)
- [ ] 투표 동점 처리
- [ ] 목표 다이아가 4명 공동인지 각자인지
- [ ] 별풍선·방송 채팅 수신 방법 (플랫폼 공식 API 또는 후원 알림 서비스)
- [ ] 리소스팩 배포 방식 (서버 리소스팩 vs 모드에 포함)

## 파일 구성

| 경로 | 내용 |
| --- | --- |
| `models/*.bbmodel` | 모델 13종 (텍스처 내장). 같은 이름 `.png`는 텍스처 단독본 |
| `models/hitboxes.json` | 엔티티로 쓰는 3종(까마귀·피냐타·운석) 히트박스 |
| `motions/forced_dance.json` | 강제 댄스 모션 (playerAnimator) |
| `motions/forced_dance_preview.html` | 모션 3D 미리보기, 브라우저로 열기 |
| `preview/diamond_models_viewer.html` | 모델 13종 3D 미리보기 + 애니메이션 재생 |
| `tools/models/` | 모델 생성 스크립트 (`models.py` = 처음 3종, `models2.py` = 나머지 10종) |
| `tools/motion/` | 모션 생성 스크립트 (`build_spec.py` → `motion_gen.py`) |
| `docs/events.md` | 이벤트 기획 확정본 + 디스코드 공지문 |
| `docs/open-questions.md` | 기획에서 아직 비어 있는 것 (2026-11-16 검토) |
| `plugin/` | 서버 플러그인 DiamondCore (Paper 1.21.4). **빈 뼈대** |
| `mod/` | 클라 모드 diamond-ui (Fabric 1.21.4, 모드 id `diamondserver`). **빈 뼈대** |

## 모델 13종

| 파일 | 이벤트 | 애니메이션 (`animation.<id>.<name>`) |
| --- | --- | --- |
| `target_marker` | 1명 랜덤 이벤트 공용 (뽑힌 사람 머리 위) | spawn, idle, pick, leave |
| `diamond_crow` | 100 다이아 도둑 까마귀 | spawn, idle, walk(비행), attack(낚아채기), flee(물고 도망), death |
| `ride_rocket` | 100 로켓 발사 | spawn, idle, launch, fly. 탑승 위치 본 = `p_seat` |
| `giant_anvil` | 100 모루 낙하 | spawn, fall, impact, vanish |
| `stun_stars` | 모루 맞았을 때 | spawn, idle, leave |
| `curse_mark` | 100 저주받은 곡괭이 (3분간 머리 위) | spawn, idle, leave |
| `disco_ball` | 100 강제 댄스 | spawn, idle, leave |
| `diamond_pinata` | 300 피냐타 | spawn, idle, hit, burst, escape |
| `diamond_cloud` | 300 다이아 소나기 | spawn, idle, rain, vanish |
| `diamond_meteor` | 300 운석우 | spawn, idle, walk(낙하), attack(착탄), death |
| `golden_crown` | 300 미다스의 손 (10초간 머리 위) | spawn, idle, leave |
| `diamond_fountain` | 300 다이아 분수 | spawn, idle, burst, vanish |
| `grim_reaper` | 4444 즉사 | spawn, idle, attack(낫 휘두르기), leave |

- 포맷: Modded Entity, Box UV, 1블럭 = 16px, 정면 = north(-z)
- 큐브 회전은 22.5° 단위만 사용 (ModelEngine / BetterModel 호환 목적)
- 히트박스는 까마귀·피냐타·운석에만 있고 Blockbench에서 숨김 처리됨
- **UFO 모델은 사용자 파일**(`farm_ufo_beam.bbmodel`, idle/abduct 애니메이션)을 쓴다. 이 저장소에는 없음. 검토 의견: 지름 약 3.5블럭이라 1.5~2배 확대 권장, 빔이 얇은 선이라 파티클 기둥 보강 권장, 빔이 뚝 켜지므로 `beam_on` 애니메이션 추가 권장 (아직 안 함)
- Blockbench·게임에서 실제로 열어 본 적은 없음. 파이썬 소프트웨어 렌더러로만 검증

## 강제 댄스 모션

- ID `forced_dance`, 208틱(10.4초), 120BPM. 디스코 포인트 → 두 팔 흔들기 → 스텝 & 박수 → 트위스트 → 디스코 포인트 → 복귀
- 루프가 아니라 10초 단발 모션. 이벤트 시작 때 한 번 재생
- 조작 잠금은 서버 코드에서 따로 걸어야 함
- 게임 안 실행 테스트는 아직 안 함

## 다시 만들 때

스크립트는 출력 경로가 `/mnt/user-data/outputs/`로 박혀 있음. 필요하면 경로만 바꿔서 실행.

```bash
cd tools/models
python3 models.py            # 까마귀·피냐타·운석 (기본 + shiny도 같이 나옴, shiny는 버리면 됨)
python3 models2.py           # 나머지 10종. 특정 모델만: python3 models2.py grim_reaper disco_ball
python3 render.py <파일.bbmodel>   # preview.png 렌더 확인

cd ../motion
python3 build_spec.py && python3 motion_gen.py forced_dance_spec.json <출력폴더>
```

필요 패키지: Python3 + numpy + Pillow. 모션 검증 스크린샷(`shot.mjs`)은 Node + Playwright.

## 다음 할 일 (제안 순서)

1. 위 "아직 안 정한 것" 답 받기
2. 서버 플러그인 공통 시스템: 후원 수신기 → 이벤트 큐 → `/후원테스트 <개수> <닉네임>` 테스트 명령어
3. 모델 없이 되는 이벤트부터 구현 (목표 증감, 밥, 개미화·거인화, 로켓, 핫바·자리 셔플 등)
4. Fabric 모드 HUD: 이벤트 배너, 타이머, 대기열, 타겟 룰렛, 룰렛 판, 투표창, 조작 반전, 정전
5. 모델 이벤트 연결 (ModelEngine 또는 BetterModel)
6. AI 플레이어 (Citizens): 따라다니기 → 탐색·채굴 → 전달 → 복귀·퇴장 순으로

## 관련 링크 (claude.ai, 사용자 계정에서만 열림)

- 리소스 정리 문서: https://claude.ai/artifact/RLBML2JYL7o7k8pzLGeBgG — **초기 기획 기준이라 이벤트 목록이 옛날 것.** 최신은 `docs/events.md`
- 모델 13종 3D 미리보기: https://claude.ai/artifact/HxxpagX9BkPJgfbPECVt2S

## 2026-11-16 검토·준비 (Claude)

- 기획 빈칸 목록: [`docs/open-questions.md`](docs/open-questions.md). 서버 기본 규칙(목표 시작값·세는 법·달성 시 결과·맵)과 이벤트 대상이 정해져야 기능 개발을 시작할 수 있다.
- `plugin/`, `mod/` 는 빌드만 되는 빈 뼈대다. 기능 코드는 없다.
- 빌드 (저쪽): `C:\ddm\diamond-server\plugin`, `...\mod` 에서 설치된 gradle 8.14.3 의 `gradle.bat build --offline`. 결과는 `build/libs/DiamondCore.jar`, `build/libs/diamond-ui-<버전>.jar`.
- 별풍선 수신: 깨굴·쌍룡에서 쓴 donation 플러그인(Colla-Bot, `DonationEvent` = 받은 플레이어·후원자 닉네임·개수·플랫폼)을 쓰면 된다. `plugins/donation/config.yml` 의 `acceptedDonations` 에 적은 개수만 전달된다. **일반 방송 채팅은 주지 않으므로 3000 채팅 투표는 따로 만들어야 한다.**
- `tools/` 스크립트는 저쪽에 Python·Node 가 없어 실행할 수 없다. 참고용. 모델 수정은 bbmodel 을 직접 고친다.
- 모델 13종은 Modded Entity 형식 + `animation.<id>.<name>` 이름이라 ModelEngine 에 넣으려면 변환이 필요하다 (차카데미 때와 같은 작업).
- 서버 폴더 (호스팅): `C:\Chacademi\diamond` — 2026-11-16 생성. 쌍룡 서버(Purpur 1.21.4)에서 서버 파일·기본 플러그인 25개·설정을 복사하고 월드·기록·쌍룡 전용 파일은 뺌. 포트 **25800**, 생존·보통 난이도, `Start.cmd`(4GB)로 실행. `plugins/donation/config.yml` 에 가격표 11개를 적어 둠. **아직 한 번도 켜 보지 않았고**, 방화벽 25800 은 오너가 열어야 한다. DiamondCore.jar 는 아직 안 넣음(빈 뼈대라서).

## 런처 (2026-11-16, 쌍룡상사 런처와 같은 방식)

- 이름 "나니 다이아서버", 설치 파일 `NaniDiamond-setup-1.0.0.exe`, 유저 PC 저장 폴더 `.nanidiamond`.
- 런처 소스: `claude/diamond-launcher` 브랜치 (저쪽 `C:\ddl`). 푸시하면 깃허브가 자동으로 빌드해서 `claude/diamond-launcher-build` 브랜치에 설치 파일을 90MB 조각으로 올린다.
- 배포 목록: `claude/diamond-launcher-distro` 브랜치 (저쪽 `C:\ddd`). 서버 주소(211.53.196.231:25800)는 `tools/diamond.json`, 모드는 `mods/` 에 jar 를 넣고 `perl tools/build_distro.pl` → 커밋·푸시. 런처를 다시 빌드할 필요 없이 유저가 켤 때 새로 받는다.
- 들어 있는 모드: 원래 통합런처 기본 모드(Fabric API, 후원 Donation, Plasmo Voice, 한글패치 등) + Player Animator(강제 댄스용) + `diamond-ui`(지금은 빈 뼈대 0.0.1).
- 아이콘·배경은 임시 그림(보석)이다. 정식 그림이 오면 `app/assets/images/icon.png`·`icon.ico`·`backgrounds/0.jpg`, `build/icon.png` 를 바꾼다.
- 첫 빌드(b067419) 성공: 설치 파일 98.5MB, 저쪽 `클로드\다이아서버\런처\NaniDiamond-setup-1.0.0.exe` (조각 2개를 이어 붙이고 SHA256 일치 확인). 설치·실행 시험은 안 함. 내려받기 주소: https://github.com/zzunwoooo/chacademi_Result/releases/download/diamond-launcher-1.0.0/NaniDiamond-setup-1.0.0.exe (런처를 다시 빌드하면 gh release upload --clobber 로 교체).
