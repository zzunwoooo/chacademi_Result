# 3장 「지워진 이름」 반영 정리 (규칙 세션용)

- 브랜치: `feature/ch3-story-20261103` · 폴더 `ch3-story/`
- 대본(오너 확정): 바탕화면 `클로드\3장_스토리_설계.md` · 단계 15개(ch3-1~ch3-14 + ch3-end) · 대사 18개
- 학교 스토리는 설정만 바뀜(재시작 없음, `/서버관리 새로고침 스토리`). 코드 변경은 MagicCodexBridge 1곳(아래 5). 게임 안 시험은 안 함.

## 먼저 있어야 하는 것 (없으면 그 단계에서 멈춤)
| 단계 | 필요한 것 | 상태 (11/03) |
|---|---|---|
| 3-8 | 신규 150종 마법 운영 반영 | 반영 대기 (plan-nova150) |
| 3-9 | 던전 `ash_shadow` 「재의 그림자」 등록 | 보스 준비, 맵·등록 대기 |
| 3-12 | 던전 `horologe` 「시계탑지기 호로겐」(3인) 등록 | 보스 준비, 맵·등록 대기 |
| 3-14 | 던전 `mordian` 「모르디안의 원념」(3인) 등록 | 보스 준비, 맵·등록 대기 |

던전 ID 는 제안값. 다르게 등록하면 `steps-ch3.yml` 의 권한 이름(`chacademi.dungeon.enter.<ID>`)만 같이 바꾸면 된다.

## 1. 대사
`dialogues/ch3-*` 18개 폴더 → 학교 `plugins/ChacademyStory/dialogues/` (대사는 서버가 보냄, 런처 배포 없음)

## 2. 단계 (학교 `plugins/ChacademyStory/steps.yml`)
1. `steps-ch3.yml` 의 내용(`ch3-1` ~ `ch3-end`)을 `단계:` 아래 `ch2_5-end` 다음에 붙인다.
2. 3장 대기 단계 8개 `ch2-22`, `ch2_5-2` ~ `ch2_5-7`, `ch2_5-end` 를 파일 주석대로 바꾼다: `호출: {종류: 자동, 지연: 0}`, `다음: ch3-1` (시작명령은 그대로 둠). 그러면 2장을 끝낸 사람 전원이 바로 3-1 로 이어진다.
3. `config.yml` `chapter-release.chapters` 에 `'3': {open: true}` 추가.
4. `/서버관리 새로고침 스토리` → 콘솔 경고 확인. "신호 포악한몬스터처치/노엘메모/재의그림자클리어/호로겐클리어/원념클리어 는 자동으로 보내는 기능이 없음" 경고는 정상(명령으로 보내는 신호라서).

## 3. 책 (학교)
- `books-ch3.yml` 의 `noel_memo` → `plugins/MagicCodexBridge/books.yml` `books:` 아래 → `/서버관리 새로고침 책`
- 지하 던전 입구(로비 왼쪽 아래, 리치 연구실 포탈 근처) 블록을 바라보고 `/컨텐츠관리 책 연결 noel_memo`

## 4. 야생 처치 신호 (야생1·야생2)
- `story-ch3-hunt.sk` → 두 서버 `plugins/Skript/scripts/` → `/sk reload story-ch3-hunt`
- 3-3 단계 사람만 (권한 `chacademi.story.ch3hunt`, 3-3 시작 때 주고 3-4 시작 때 회수) MythicMobs 몹 처치마다 학교로 신호.

## 5. 새 마법 수 (MagicCodexBridge, 학교)
- `bridge-patch.md` 대로 `%chacastory_newspells%` 추가 (운영 소스에 [기능] 표시) → 빌드 → 학교 반영 (재시작 필요할 수 있음 → 다음 정기 재시작에 묶기)
- `new-spells.txt` (신규 150종 키) → 학교 `plugins/MagicCodexBridge/story-new-spells.txt`

## 6. 던전 등록 때 (ChacaDungeon `dungeons.yml`)
각 던전 `clear-commands` 에 신호 한 줄:
| 던전 | permission | clear-commands |
|---|---|---|
| 재의 그림자 | `chacademi.dungeon.enter.ash_shadow` | `유저관리 {player} 스토리신호 재의그림자클리어` |
| 시계탑지기 호로겐 | `chacademi.dungeon.enter.horologe` | `유저관리 {player} 스토리신호 호로겐클리어` |
| 모르디안의 원념 | `chacademi.dungeon.enter.mordian` | `유저관리 {player} 스토리신호 원념클리어` |
`lock-message` 예: "3장 이야기를 진행하면 열려요."

## 7. 반영 후 확인
- `/유저관리 <닉> 스토리진행 단계 ch3-1` 로 시험 계정 이동 → 대사·NPC 클릭 순서 확인
- 3-3: 야생에서 미몹 1마리 → 학교 퀘스트창 숫자 오름
- 3-5: 지하 던전 입구 출구 자리(347.95, 119, -30.61) 3칸 안에 들어가면 대사
- 시작 로그 확인 규칙대로 5대 확인

## NPC 번호 (학교 Citizens)
그레고르 15 · 하르바 25 · 이솔데 21 · 보미 35 · 노엘 42
