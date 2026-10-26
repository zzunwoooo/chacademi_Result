# CLAUDE_FIX_ECONOMY_20261021 — 재화·아이템 경계 수정 묶음

- 브랜치: `claude/fix-economy-20261021` (기준 `0cd1454`). push 안 함.
- 설계 근거: `claude-reply-to-gpt-20261021.md` 부록 1 (A01·A02·A03·A08), `05-life.md` 낚시 F1·F2, 오너 결정 1.
- **빌드 미검증: 문법 검사(parsecheck)만 통과.** 타입·import 는 손으로 확인했지만 Gradle 컴파일·테스트는 못 돌렸음.

## 커밋

| 커밋 | 내용 |
|---|---|
| ccc3539 | A01 연금술: 자리 없으면 제작 거절, 우편 경로 제거 |
| 4bc553c | A02 관리자 우편: 성공 확인 뒤에만 손 아이템 빼기 |
| e530a16 | A01 낚시: 영속 우편 대기열(outbox) + 멈춘 판매·꺼내기 관리자 정리 |
| e91cb75 | C09 낚시: rush 제거(프로토콜 v3), 최소 시간 검증 강화, 놀래키기 미끼 소모 |
| 046a2dd | A03 하우징 방 확장 결제 원장 + 종료 중 콜백 유실 수정 |
| 329e7e5 | C09 최소 시간 기준을 클라 묶음의 최대 감기 속도(0.975×)에 맞춤 |
| 098eb90 | A08 NPC 선물 영수증 + 관리자 우편 문구 테스트 |

---

## 1. A01 연금술 (오너 결정 1: 거절)
- **고친 것**: 재료를 뺀 뒤의 인벤토리(0~35칸)를 계산해 결과물이 다 안 들어가면 `REFUSED` + 알림("인벤토리 공간이 부족해요"). 재료·경험치 차감 없음. `mail()`/`drop()` 삭제. 경험치 부족 거절은 그대로.
- **파일**: `AlchemyBridge.java` (`fitsAfterTaking`, 순수 `roomFor`), `alchemy.yml`(mail-title/mail-body/mailed 삭제, `messages.no-room` 추가), `AlchemyCraftPolicyTest`.
- **배포 주의**: 서버의 기존 `alchemy.yml` 에는 `no-room` 이 없음 → 코드 기본 문구가 나감(문제 없음). 남아 있는 mail-* 키는 무시됨.
- **테스트**: `AlchemyCraftPolicyTest` (새 4개: 꽉 참 거절 / 재료 칸이 비면 허용 / 기존 스택에 합침 / 안 쌓이는 결과물).
- **실게임**: 인벤토리를 꽉 채우고 제작 → 거절 알림, 재료·경험치 그대로. 재료 칸만 비는 경우 → 제작됨.

## 2. A02 관리자 우편 (`/유저관리 <닉네임> 우편 보내기`)
- 손 아이템은 **우편 저장이 확인된 뒤**, 그 칸의 아이템이 바이트 단위로 같을 때만 뺌. 바뀌었거나 오프라인이면 빼지 않고 `MAIL ADMIN copy-retained` 경고 + 관리자에게 "사본 확인" 안내.
- 실패 문구: "우편을 보내지 못했어요 (첨부는 손에 그대로 있어요)". `MAIL ADMIN send` 로그는 성공 뒤에, 실패는 `send-failed`.
- 종료 중 콜백이 버려지면 아이템은 손에 그대로 남음(유실 대신 사본 위험 쪽, 설계대로).
- **테스트**: `AdminMailResultTest`. **실게임**: mailbox DB 를 읽기 전용으로 만들고 보내기 → 손에 남는지.

## 3. A01 낚시 보상 — 영속 우편 대기열 (안 B)
- **새 파일** `FishMailOutbox.java`: `plugins/MagicCodexBridge/fish-mail-outbox/*.properties` (레코드 하나 = 우편 한 통, tmp→fsync→ATOMIC_MOVE).
- `FishingBridge.give/deliver` 순서: 인벤토리에 넣기 → 남은 것을 **대기열 파일에 먼저 저장** → 같은 틱에 PDC 영수증 `fish_mail_<hash>` + `saveData()` → `sendSystem(source)` → **우편 DB 저장이 확인되면** 파일 삭제·영수증 삭제.
  - source(우편 `source_key`, UNIQUE)는 레코드에 저장된 값을 그대로 다시 쓰므로 몇 번 다시 보내도 우편은 한 통.
  - 실패·종료 중: 레코드 유지. 1분 뒤 최대 3번 재시도 + 서버 시작 + 수령인 접속 때 재전송.
  - 시작: 오프라인 수령인은 디스크 플레이어 파일에 영수증이 있을 때만 재전송(없으면 접속 때 판단). 접속: PDC 영수증 있으면 재전송, 없으면 "지급 전에 멈춤"으로 레코드 폐기.
  - 대기열 파일 쓰기 자체가 실패하면(디스크) 발밑에 떨어뜨리고 SEVERE 로그 + 알림 (사라지지 않게).
  - 깨진 파일은 자동 처리 안 함(콘솔 SEVERE, 관리자 확인).
- 적용 경로: 낚시 보상(보물), 장비 고장 반환, 장비 교체 반환, 도감 보상, 관리자 지급, 수조 꺼내기(관리자·유저).
- **수조 꺼내기**: 우편으로 가면 `tank.finish(ITEMIZE,true)` 를 **우편 확인 뒤** 대기열이 부름(레코드에 물고기 id·token 저장). 인벤토리로 들어가면 기존처럼 바로 완료.
- **관리 명령**(OP, 기존 허브): `/유저관리 <닉네임> 낚시 우편대기` (목록) · `우편대기 다시보내기`.
- **배포 주의**: 이 서버 폴더 전용이라 레코드는 항상 이 서버(origin) 것. 서버를 옮길 때 `fish-mail-outbox` 폴더를 지우지 말 것.
- **테스트**: `FishMailOutboxTest`, `FishTankActionsTest`(give→deliver 검증으로 수정).
- **실게임**: 인벤토리를 꽉 채우고 보물 낚기 → 우편 1통. MariaDB 를 잠시 끊고 낚기 → 콘솔 `FISHING OUTBOX send failed (kept)`, 폴더에 파일 남음 → DB 복구 후 1분 뒤 또는 재접속 때 우편 1통, 파일 사라짐.

## 4. 물고기 판매 SELLING(·TAKING) 정리
- `/유저관리 <닉네임> 낚시 수조 멈춘처리` : 판매 중(SELLING)·꺼내는 중(TAKING) 물고기 목록 (수조 칸 차지 표시, 우편 대기열에 있는지 표시).
- `… 멈춘처리 <번호> 지급완료 확인` (SELLING→SOLD, TAKING→ITEM) / `수조로 확인` (→TANK). 콘솔 감사 로그 `FISH TANK ADMIN resolve admin=… was→to ok=…`.
- 우편 대기열에 있는 꺼내기 건은 `수조로` 를 막음(복제 방지).
- `수조 보기` 에 멈춘 수 한 줄, 서버 시작 때 서버 전체 멈춘 수를 콘솔 경고.
- 판단 기준: 판매는 콘솔 `PAYMENT_RESULT confirmed=true` 가 있으면 지급완료.

## 5. C09 낚시 결과 서버 검증
- **프로토콜 v3** (`FishingProtocol.VERSION=3`): `Bite.rush` 삭제. 바늘 가속은 클라가 `FishingProtocol.rushes(zone)` (zone < .135 = 전설 폭 .12)로 계산 → 체감 동일. **모드와 플러그인을 함께 배포**해야 함(버전이 다르면 낚시 패킷 무시).
- 최소 시간 `minimumCatchMillis(bite, reelPower, reelBonus)`: 접근(1.25s, 상자 0) + .5s + 놀림 1.25s×n + .25s + 감도 .2s + 바늘이 영역에 닿는 .25×sweep, 그다음 **클라 최대 감기 속도 0.975×reelSpeed**(지친 물고기 .75×1.3, 클라 묶음 FishMood) × 낚싯대 보너스(sea_reel×magic_reel) × 물고기 최소 당김(힘·strain·패턴 최저치를 .6으로 제한·체력 0)으로 깊이를 감는 시간.
  - **여유(마진): (가장 빠른 정직한 잡기 시간 × 0.9) − 400ms.** 서버는 보낸 시각부터 재므로 왕복 지연은 유저에게 유리하게만 더해짐.
  - 너무 빠르면 기존처럼 REFUSED + 콘솔 `FISHING too fast … session … fish …`.
- 세션 검증: 기존대로 세션 id + 토큰 + BITING 상태에서만 결과 받음.
- **동작 변경(판단 포함)**: 입질 뒤 놀래켜 쫓기(SPOOKED)도 미끼 1개 소모 (`wearAndBait`). 공짜 리롤 방지용, F1 설계. 되돌리려면 `baitUsed` 줄에서 SPOOKED 만 빼면 됨.
- **남은 정보 누출(솔직히)**: zone(.22/.18/.15/.12)·sweep·공격 횟수는 바늘 게이지·연출에 필요해 여전히 패킷에 있음 → 변조 클라는 챔질 전에 등급을 추정할 수 있음. 이번 수정은 명시적 전설 표시 제거 + 놀래키기 비용 + 감기 시간 하한으로 "싸게 리롤·미니게임 건너뛰기"를 막는 수준. 완전한 차단은 게이지 수치에 잡음을 섞는 등 체감이 바뀌는 변경이 필요해 보류(오너 결정 필요).
- **테스트**: `magic-codex-fabric` `FishingProtocolTest` (v3 왕복, rushFromZone, minimumCatchScalesWithDifficulty — 0.975× 로 계속 감는 정직한 잡기가 거절되지 않음 포함).
- **실게임**: 평소처럼 여러 등급 낚기 → `too fast` 로그가 안 나와야 함. 특히 깊은 곳·감기 보너스 낚싯대·지친 물고기 연속 감기.

## 6. A03 하우징 방 확장
- **새 파일** `ExpandLedger.java`, 테이블 `housing_expand_tx` (자동 생성, 추가 전용).
- 순서: `prepared` 기록(실패하면 출금 안 함) → Vault 출금 → 거절 `cancelled` / 예외 `uncertain` / 성공 `withdrawn` → 한 트랜잭션으로 방 크기+단계(`applied` 또는 크기가 이미 바뀌면 `refund_pending`) → `applied→done` / 입금 확인 시 `done`, 입금 실패·모름 `uncertain`.
- `Economy.withdrawResult/depositResult`: OK / REFUSED(돈 안 움직임) / UNCERTAIN(예외). "돈이 부족해요"는 거절일 때만, "돌려드렸어요"는 입금 확인 때만.
- 시작 시: 이 서버(`config server` 이름)의 prepared·withdrawn·refund_pending → uncertain (자동 환불·재출금 없음), applied → done. 콘솔 SEVERE.
- `HousingStore.async`: 종료 중(onDisable, isEnabled=false) 끝난 작업의 콜백을 버리지 않고 `close()` 가 작업 스레드를 기다린 뒤 메인 스레드에서 실행. 종료 뒤 새 요청은 null(=DB 오류)로 바로 알림. 종료 중에는 환불(입금)을 하지 않고 다음 시작 때 uncertain 으로.
- **관리 명령**: `/유저관리 <닉네임> 방 확장기록`, `방 확장처리 <번호> 환불 확인 | 완료 확인` (uncertain 만). 감사 로그 `HOUSING EXPAND ADMIN`.
- **배포 주의**: 학교·야생의 housing `server` 이름이 서로 달라야 함(origin 구분). `build.gradle` 에 테스트용 H2 추가.
- **테스트**: `ExpandLedgerTest` (H2): 반영/크기 다름 → refund_pending 한 번 / 재시작 → uncertain, 다른 서버 건 그대로 / 문구.
- **실게임**: 돈 부족 → "돈이 부족해요". 경제 플러그인 정상 → 확장. 두 창에서 동시에 확장 → 하나는 환불 안내. `확장기록` 으로 단계 확인.

## 7. A08 ChacaNPC 선물
- `codex_affinity_gift` 에 `origin` 열 추가(ALTER, 기본 ''). origin = Bridge `mailbox-origin.txt` (우편과 같은 서버 ID).
- 차감: 새 facade `NpcSocialFacade.takeGift(player, slot, item, token)` 가 손 칸 확인 → 1개 차감 → PDC `gift_receipt_<token>` → `saveData()` 를 같은 틱에. ChacaNPC `GiftService` 는 이걸 부름(옛 Bridge 면 선물 안 함, `MagicCodexLink.giftReceipts()`).
- 칸이 바뀜 → `cancelGift` 결과 확인(실패해도 영수증 없음 → 다음 접속 때 취소). 차감 결과 모름 → 취소·확정 안 함.
- 확정 실패 → **환불 안 함**, 3초 뒤 확정 재시도(최대 2번 더). 끝내 실패하면 pending+영수증 유지 → 접속 때 확정. `refund()` 삭제.
- 시작 시 일괄 확정(`recoverPendingGifts`) 삭제. 접속 때 **시작한 서버에서만**: 영수증 없음 → 취소(하루 횟수 돌려줌) / 영수증이 디스크에도 있음 → 확정 / 메모리에만 → 대기 / 다른 서버 → 건드리지 않음 / origin 없는 옛 기록 → 관리자. 접속 시각 이전에 시작된 건만 봄(진행 중 선물 보호).
- **관리 명령**: `/유저관리 <닉네임> 호감도 선물기록`, `호감도 선물처리 <번호> 확정 확인 | 취소 확인`. 감사 로그 `NPC GIFT ADMIN`. 시작 때 옛 기록 수 콘솔 경고.
- `affinity.yml` 의 `recover-pending-gift-minutes` 는 더 이상 쓰지 않음(주석만 바꿈).
- **배포 주의**: MagicCodexBridge 와 ChacaNPC 를 **같이** 배포. 새 ChacaNPC + 옛 Bridge = 선물 꺼짐(안전), 옛 ChacaNPC + 새 Bridge = 옛 방식(영수증 없음 → 접속 때 취소되어 점수가 안 오를 수 있음)이므로 같이 올릴 것.
- **테스트**: `AffinityStoreTest` (66~67 새 정책: 시간만 지난 pending 은 자동 확정 안 됨, giftRecoveryPolicy, receiptlessGiftCancelsAndReturnsDailyCount).
- **실게임**: 선물 → 점수 반영·아이템 1개 감소. 선물 직후 서버 강제 종료 → 재접속 시 아이템이 빠져 있으면 점수 반영, 안 빠져 있으면 취소(같은 날 다시 선물 가능).

---

## 못 한 것·보류
- C09: zone/sweep/공격 횟수에 의한 등급 추정은 남음(위 5번). 게이지 잡음·재입질 쿨타임은 체감 변경이라 보류.
- A03 원장은 `withdrawn` 상태를 별도 쓰기로 남김(출금 직후). 이 쓰기가 실패해도 반영 트랜잭션은 prepared 에서도 진행함.
- 낚시 대기열의 영수증 판단은 "이 서버 플레이어 데이터" 기준. 플레이어 데이터를 서버 간 공유하는 구성이 생기면 다시 봐야 함.
- 유저 수조 꺼내기는 빈 칸을 미리 확인하므로 보통 우편으로 가지 않음.

## 컴파일 위험 지점 (확인 부탁)
- `HousingStore.async` 의 `if (...) try {...} catch (...) {}` 형태(문법상 유효), `ExpandLedger` 의 `switch` 문자열 상수 case.
- `AffinityStore` 생성자의 빈 본문 try-with-resources (`var r` 미사용 경고만 예상).
- H2 에서 `state`/`owner`/`size` 열 이름(예약어 아님으로 판단).
- `FishingBridge.deliver` 람다 안 `all[0] &= ok` (Boolean 언박싱).
- 다른 묶음과 겹침: 클라 묶음(`claude/fix-client-20261021`)도 `FishingBridge`·`FishTankBridge`·`FishingScreen`·`FishingProtocol`(주석 한 줄)을 고침 → 합칠 때 문맥 충돌 가능. 이 묶음의 `FishingScreen` 변경은 `bite.rush()` 두 줄뿐.
