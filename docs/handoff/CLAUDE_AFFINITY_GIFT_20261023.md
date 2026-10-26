# 인계: 호감도 0~100 · 30/60/90 상한 · 선물 창 · 일지 NPC 카테고리 (Claude 2026-10-23)

브랜치 `claude/npc-buttons-20261023` (이 저장소) + chacademi-server `claude/story-ch1-20261020` (NPC yml 85개).

## 꼭 같이 배포
- **chaca-npc-protocol VERSION 2** (C_GIFT=5, S_GIFTS=8): ChacaNPC 와 MagicCodex(Fabric) 를 함께 올려야 함.
- **JournalProtocol 와이어 버전 2** (Entry 에 `group` 추가): MagicCodexBridge 와 MagicCodex(Fabric) 를 함께. 새 클라이언트는 1·2 둘 다 읽음.
- 선물 아이템 100개(`item:gift_*`)는 ItemsAdder 정의가 따로 필요. lore 1줄 `선물`, 2줄 등급(`일반`/`고급`/`유일`/`전설`, 색 코드 무시).

## 규칙
- 호감도 0~100. 상한 = 30 → (NPC 퀘스트로 heart 1) 60 → (heart 2) 90 → (heart 3) 100. 해제는 기존 `custom heart <npc> <n>` 보상.
- 상한에 닿아 있으면 선물을 받지 않음: `gift_full` 대사("지금은 괜찮아~"), 아이템·하루 횟수 차감 없음 (`AffinityStore.beginGift` → FULL, 하루 횟수보다 먼저 판정).
- 점수: 그 NPC `gift_likes.liked` 에 있으면 일반+1 고급+2 유일+3 전설+10, 아니면 -1 (config `gifts.grades`, `gifts.not-liked`).
- 단계(AffinityStage): 0 / 10 아는 사이 / 30 친한 사이 / 60 단짝 / 90 특별.
- Shift+우클릭 선물 삭제. 마법 힌트 시스템은 그대로 (버튼에서만 빠짐).

## 흐름
대화 버튼 3번 `id: gift` → 서버 `GiftService.offer` (상한이면 gift_full, 없으면 안내) → `S_GIFTS "칸:등급,..."` → 클라 `NpcTalkScreen.drawGifts` (금색 이중 테두리, 6×2 칸, 페이지) → `C_GIFT 칸` → `GiftService.giveSlot` 이 칸·등급·수량 재검증 후 1개 차감.
채팅 대화창은 `/대화선택 선물 <칸>` 클릭 목록.

## 일지 NPC 카테고리
`quests.yml` 퀘스트에 `npc: 엘레나` 를 적으면 일지 진행중/완료에 **"NPC 엘레나"** 카테고리로 따로 묶임 (메인·일지와 같은 높이). 표시 전용, 저장 데이터 변화 없음. NPC 퀘스트 내용·보상은 아직 안 만듦.

## 서버 저장소 (chacademi-server)
`npc/ChacaNPC/npcs/*.yml` 85개: 버튼 3번 → `gift` (반말 "선물 줄 거 있어" / 존댓말 "선물 드릴 게 있어요" / 선생님 "제가 드릴 선물이 있어요"), `gift_likes.liked` 10개씩(골고루 배정), `fallback_lines.gift_full`.

## 확인 못 한 것
이 환경에 Paper API 가 없어 플러그인 전체 빌드는 못 함 (문법 검사와 프로토콜·일지 트리 단독 테스트만 통과). 빌드 후 `AffinityStoreTest`, `CoreTest` 확인 부탁.
